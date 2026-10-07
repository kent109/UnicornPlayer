package com.bullhead.equalizer;

import android.content.Context;
import android.media.audiofx.BassBoost;
import android.media.audiofx.Equalizer;
import android.media.audiofx.PresetReverb;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.util.concurrent.atomic.AtomicBoolean;

public class AudioEffectManager {
    private static final String TAG = "AudioEffectManager";
    private static volatile Equalizer sEqualizer = null;
    private static volatile BassBoost sBassBoost = null;
    private static volatile PresetReverb sPresetReverb = null;
    private static volatile int sAudioSessionId = 0;
    private static final AtomicBoolean sIsInitialized = new AtomicBoolean(false);

    // ===== 效果参数渐变（播放中开关切换场景；冷启动由 AudioProcessor 采样级音量淡入负责） =====
    private static final Handler sMainHandler = new Handler(Looper.getMainLooper());
    /** 渐变步进间隔：HAL 每次参数替换是一次小阶跃，步子越密听感越连续 */
    private static final long RAMP_INTERVAL_MS = 20L;
    /** 首步延迟：等 AudioTrack 信号稳定流出，避免与起播瞬态混叠 */
    private static final long RAMP_START_DELAY_MS = 120L;
    /** 播放中手动打开开关的渐入时长 */
    private static final long RAMP_TO_SETTINGS_DURATION_MS = 600L;
    /** 关闭开关的渐出时长：渐回中性后再 disable，消除关闭瞬间的断层 */
    private static final long SOFT_DISABLE_DURATION_MS = 500L;
    private static Runnable sRampRunnable = null;

    public static synchronized void initialize(Context context, int audioSessionId) {
        if (sIsInitialized.get()) {
            Log.w(TAG, "AudioEffectManager already initialized");
            return;
        }

        sAudioSessionId = audioSessionId;
        sIsInitialized.set(true);

        // 冷启动：仅创建效果对象（EQ/低音保持 disable，实际增益全部由 App 内
        // AudioProcessor 承担——系数在信号开始前落定，配合采样级起播淡入呈现
        // 恒定音色的"拧音量旋钮"式纯响度渐入；混响按需启用）
        createAudioEffects(context, audioSessionId);
    }

    public static synchronized void destroy() {
        cancelRamp();
        if (sIsInitialized.get()) {
            releaseAudioEffects();
            sIsInitialized.set(false);
        }
    }

    public static synchronized void enableEffects(Context context) {
        if (!sIsInitialized.get()) {
            Log.e(TAG, "AudioEffectManager not initialized");
            return;
        }

        if (sEqualizer != null && sBassBoost != null && sPresetReverb != null) {
            // 对象已存在：频段增益与低音已收编进 App 内 AudioProcessor，系统
            // Equalizer/BassBoost 一律保持 disable（OPPO 等设备上"音轨存在前 enable"
            // 的效果在效果链随音轨迁移时进入半激活状态，表现为起播无 EQ、片刻后突变）。
            // 仅按需对齐 PresetReverb。
            Log.d(TAG, "Audio effects already created, syncing reverb enable state");
            syncReverbEnableState();
            return;
        }

        try {
            // 用户在均衡器页面交互（或播放中重新打开开关）触发的创建：
            // 创建后立即写入完整参数再 enable
            createAudioEffects(context, sAudioSessionId);
        } catch (Exception e) {
            Log.e(TAG, "Failed to create audio effects", e);
            releaseAudioEffects();
        }
    }

    public static synchronized void disableEffects() {
        // 服务内部路径（恢复时 EQ 关闭等）：立即取消渐变并 disable，无听感顾虑
        cancelRamp();
        if (sEqualizer != null) {
            sEqualizer.setEnabled(false);
        }
        if (sBassBoost != null) {
            sBassBoost.setEnabled(false);
        }
        if (sPresetReverb != null) {
            sPresetReverb.setEnabled(false);
        }
    }

    public static synchronized boolean areEffectsEnabled() {
        return sEqualizer != null && sBassBoost != null && sPresetReverb != null;
    }

    public static synchronized int getAudioSessionId() {
        return sAudioSessionId;
    }

    public static synchronized Equalizer getEqualizer() {
        return sEqualizer;
    }

    public static synchronized BassBoost getBassBoost() {
        return sBassBoost;
    }

    public static synchronized PresetReverb getPresetReverb() {
        return sPresetReverb;
    }

    /**
     * 播放中手动打开均衡器开关：从当前系统参数值平滑渐变到 Settings 目标值，
     * 避免一次性写入造成的音色断层。调用前效果对象需已 setEnabled(true)。
     */
    public static synchronized void rampToSettings() {
        if (sEqualizer == null || sBassBoost == null || !Settings.isEqualizerEnabled) {
            return;
        }
        startRampLocked(RAMP_TO_SETTINGS_DURATION_MS, false);
    }

    /**
     * 关闭均衡器：先把系统参数渐变回中性（EQ 0mB / bass 0 / reverb NONE），
     * 再 disable 三个效果。直接 disable 会让增益瞬间消失产生断层。
     */
    public static synchronized void softDisable() {
        if (sEqualizer == null || sBassBoost == null) {
            return;
        }
        startRampLocked(SOFT_DISABLE_DURATION_MS, true);
    }

    /** 是否有渐变在进行。渐变期间 UI 只更新显示，不得直写系统效果参数。 */
    public static synchronized boolean isRampActive() {
        return sRampRunnable != null;
    }

    /** 取消进行中的渐变（用户主动接管或销毁时调用）。 */
    public static synchronized void cancelRamp() {
        if (sRampRunnable != null) {
            sMainHandler.removeCallbacks(sRampRunnable);
            sRampRunnable = null;
        }
    }

    /**
     * 渐变引擎：从系统效果当前值出发，用 smoothstep 曲线在 durationMs 内渐变到
     * Settings 目标值（EQ 频段 + 低音强度）。disableAtEnd=true 时到达中性后
     * disable 全部效果并复位混响；否则最后一步应用混响预设目标。
     * 调用方必须已持有 AudioEffectManager.class 锁。
     */
    private static void startRampLocked(long durationMs, boolean disableAtEnd) {
        // 任何新渐变启动前先终止旧渐变
        if (sRampRunnable != null) {
            sMainHandler.removeCallbacks(sRampRunnable);
            sRampRunnable = null;
        }

        // 快照起点（系统当前实际值）与目标（Settings 值），渐变期间 Settings 变化不影响本次
        final short numberOfBands = sEqualizer.getNumberOfBands();
        final short[] startBands = new short[numberOfBands];
        final short[] targetBands = new short[numberOfBands];
        for (short i = 0; i < numberOfBands; i++) {
            try {
                startBands[i] = sEqualizer.getBandLevel(i);
            } catch (RuntimeException e) {
                startBands[i] = 0;
            }
            if (Settings.seekbarpos != null && i < Settings.seekbarpos.length) {
                targetBands[i] = (short) Settings.seekbarpos[i];
            } else {
                targetBands[i] = startBands[i];
            }
        }
        final short startBass;
        try {
            startBass = sBassBoost.getProperties().strength;
        } catch (RuntimeException e) {
            // getProperties 失败说明效果已不可用，放弃本次渐变
            Log.e(TAG, "startRamp: bassBoost.getProperties failed", e);
            return;
        }
        final short targetBass;
        if (Settings.equalizerModel != null) {
            short b = Settings.equalizerModel.getBassStrength();
            targetBass = (b < 0 || b > 1000) ? 0 : b;
        } else {
            targetBass = 0;
        }
        final short targetReverb;
        if (Settings.equalizerModel != null) {
            short r = Settings.equalizerModel.getReverbPreset();
            targetReverb = (r < 0 || r > 6) ? PresetReverb.PRESET_NONE : r;
        } else {
            targetReverb = PresetReverb.PRESET_NONE;
        }

        final int steps = (int) Math.max(1, durationMs / RAMP_INTERVAL_MS);

        sRampRunnable = new Runnable() {
            private int step = 0;

            @Override
            public void run() {
                synchronized (AudioEffectManager.class) {
                    if (sEqualizer == null || sBassBoost == null || sRampRunnable != this) {
                        return;
                    }
                    try {
                        step++;
                        // smoothstep：t^2(3-2t)，两端斜率为 0，渐入渐出均无起止顿挫
                        float t = (float) step / steps;
                        float fraction = t * t * (3f - 2f * t);
                        for (short i = 0; i < numberOfBands; i++) {
                            short level = (short) Math.round(
                                    startBands[i] + (targetBands[i] - startBands[i]) * fraction);
                            sEqualizer.setBandLevel(i, level);
                        }
                        short bass = (short) Math.round(
                                startBass + (targetBass - startBass) * fraction);
                        sBassBoost.setStrength(bass);
                        if (step < steps) {
                            sMainHandler.postDelayed(this, RAMP_INTERVAL_MS);
                        } else {
                            if (disableAtEnd) {
                                // 已渐变到中性，安全关闭
                                try {
                                    sPresetReverb.setPreset(PresetReverb.PRESET_NONE);
                                } catch (RuntimeException e) {
                                    Log.e(TAG, "Ramp end: reverb reset failed", e);
                                }
                                if (sEqualizer != null) {
                                    sEqualizer.setEnabled(false);
                                }
                                if (sBassBoost != null) {
                                    sBassBoost.setEnabled(false);
                                }
                                if (sPresetReverb != null) {
                                    sPresetReverb.setEnabled(false);
                                }
                                Log.i(TAG, "Soft disable finished");
                            } else {
                                // 混响最后一步才切入（预设无法渐变；NONE 时本就无效果）
                                if (targetReverb != PresetReverb.PRESET_NONE) {
                                    sPresetReverb.setPreset(targetReverb);
                                }
                                Log.i(TAG, "Ramp finished: bass=" + targetBass
                                        + ", reverb=" + targetReverb);
                            }
                            sRampRunnable = null;
                        }
                    } catch (RuntimeException e) {
                        Log.e(TAG, "Ramp step " + step + " failed", e);
                        sRampRunnable = null;
                    }
                }
            }
        };
        sMainHandler.postDelayed(sRampRunnable, RAMP_START_DELAY_MS);
    }

    private static void createAudioEffects(Context context, int audioSessionId) {
        try {
            sEqualizer = new Equalizer(0, audioSessionId);
            sBassBoost = new BassBoost(0, audioSessionId);
            sPresetReverb = new PresetReverb(0, audioSessionId);

            boolean enabled = Settings.isEqualizerEnabled;
            // 频段增益与低音均已收编进 App 内 AudioProcessor（10 段 biquad + 低音低架），
            // 系统 Equalizer/BassBoost 仅保留对象兼容 UI，一律不再 enable——避免
            // "音轨存在前 enable 的效果"在效果链迁移时进入半激活状态（起播无 EQ、
            // 片刻后突变、摩擦音）。PresetReverb 仍承担混响（无增益阶跃风险）。
            if (enabled) {
                applyFullSettings();
            }
            sEqualizer.setEnabled(false);
            sBassBoost.setEnabled(false);
            syncReverbEnableState();

            Log.d(TAG, "Audio effects created (EQ/bass in-app, reverbOn="
                    + (enabled && getReverbPresetSafe() != PresetReverb.PRESET_NONE)
                    + "), session ID: " + audioSessionId);
        } catch (Exception e) {
            Log.e(TAG, "Failed to create audio effects", e);
            releaseAudioEffects();
        }
    }

    /** 按开关状态与混响预设对齐 PresetReverb 的 enable（EQ 开且预设非 NONE 才启用）。 */
    public static void syncReverbEnableState() {
        if (sPresetReverb == null) {
            return;
        }
        short reverbPreset = getReverbPresetSafe();
        try {
            sPresetReverb.setPreset(reverbPreset);
        } catch (IllegalArgumentException e) {
            Log.e(TAG, "syncReverbEnableState: invalid reverb preset " + reverbPreset, e);
            try {
                sPresetReverb.setPreset(PresetReverb.PRESET_NONE);
            } catch (IllegalArgumentException ignored) {
            }
        }
        try {
            sPresetReverb.setEnabled(Settings.isEqualizerEnabled
                    && reverbPreset != PresetReverb.PRESET_NONE);
        } catch (RuntimeException e) {
            Log.e(TAG, "syncReverbEnableState: setEnabled failed", e);
        }
    }

    private static short getReverbPresetSafe() {
        if (Settings.equalizerModel == null) {
            return PresetReverb.PRESET_NONE;
        }
        return Settings.equalizerModel.getReverbPreset();
    }

    /**
     * 立即把 Settings 中的完整目标参数写入系统效果（创建效果链 / 播放中用户交互路径）。
     */
    private static void applyFullSettings() {
        try {
            if (Settings.equalizerModel == null) {
                Settings.equalizerModel = new EqualizerModel();
                Settings.equalizerModel.setReverbPreset(PresetReverb.PRESET_NONE);
                Settings.equalizerModel.setBassStrength((short) (1000 / 19));
            }

            // 系统 EQ 频段按其段数上限写入（通常 5 段；自研 Processor 为 10 段，值在 seekbarpos 中）
            short numberOfBands = sEqualizer.getNumberOfBands();
            for (short bandIdx = 0; bandIdx < numberOfBands; bandIdx++) {
                if (bandIdx < Settings.seekbarpos.length) {
                    sEqualizer.setBandLevel(bandIdx, (short) Settings.seekbarpos[bandIdx]);
                }
            }

            if (sBassBoost != null) {
                BassBoost.Settings bassBoostSetting;
                try {
                    BassBoost.Settings bassBoostSettingTemp = sBassBoost.getProperties();
                    bassBoostSetting = new BassBoost.Settings(bassBoostSettingTemp.toString());
                } catch (Exception e) {
                    Log.e(TAG, "bassBoost.getProperties error:" + e.getMessage());
                    bassBoostSetting = new BassBoost.Settings();
                }
                BassBoost.Settings bassBoostSettingTemp = new BassBoost.Settings(bassBoostSetting.toString());
                short safeBassStrength = Settings.equalizerModel.getBassStrength();
                if (safeBassStrength < 0 || safeBassStrength > 1000) {
                    Log.w(TAG, "Invalid bassStrength=" + safeBassStrength + ", clamping to 0");
                    safeBassStrength = 0;
                    Settings.equalizerModel.setBassStrength(safeBassStrength);
                }
                bassBoostSettingTemp.strength = safeBassStrength;
                try {
                    sBassBoost.setProperties(bassBoostSettingTemp);
                } catch (RuntimeException e) {
                    Log.e(TAG, "sBassBoost.setProperties failed, strength=" + safeBassStrength, e);
                }
            }

            if (sPresetReverb != null) {
                short safeReverbPreset = Settings.equalizerModel.getReverbPreset();
                if (safeReverbPreset < 0 || safeReverbPreset > 6) {
                    Log.w(TAG, "Invalid reverbPreset=" + safeReverbPreset + ", clamping to PRESET_NONE");
                    safeReverbPreset = PresetReverb.PRESET_NONE;
                    Settings.equalizerModel.setReverbPreset(safeReverbPreset);
                }
                try {
                    sPresetReverb.setPreset(safeReverbPreset);
                } catch (IllegalArgumentException e) {
                    Log.e(TAG, "Invalid reverb preset value: " + safeReverbPreset);
                    sPresetReverb.setPreset(PresetReverb.PRESET_NONE);
                }
            }

            Log.d(TAG, "Equalizer settings applied fully");
        } catch (Exception e) {
            Log.e(TAG, "Failed to apply full equalizer settings", e);
        }
    }

    private static void releaseAudioEffects() {
        try {
            if (sEqualizer != null) {
                sEqualizer.release();
                sEqualizer = null;
            }

            if (sBassBoost != null) {
                sBassBoost.release();
                sBassBoost = null;
            }

            if (sPresetReverb != null) {
                sPresetReverb.release();
                sPresetReverb = null;
            }

            sRampRunnable = null;
            Log.d(TAG, "Audio effects released");
        } catch (Exception e) {
            Log.e(TAG, "Failed to release audio effects", e);
        }
    }
}
