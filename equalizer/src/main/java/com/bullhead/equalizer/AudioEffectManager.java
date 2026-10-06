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

    // ===== 冷启动软启用（消除系统效果链首帧满增益切入的爆音与断层） =====
    private static final Handler sMainHandler = new Handler(Looper.getMainLooper());
    /** 是否存在等待软渐变到目标值的新建效果链 */
    private static boolean sPendingSoftApply = false;
    /** 渐变步进间隔：HAL 每次参数替换是一次小阶跃，步子越密听感越连续 */
    private static final long RAMP_INTERVAL_MS = 20L;
    /** 首步延迟：等 AudioTrack 信号稳定流出，避免与起播瞬态混叠 */
    private static final long RAMP_START_DELAY_MS = 120L;
    /** 冷启动渐入总时长：约 1 秒，人耳感知为音色缓慢进入 */
    private static final long SOFT_APPLY_DURATION_MS = 1000L;
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

        // 服务冷启动恢复路径：中性参数 enable + 待软渐变（消除首帧爆音）
        createAudioEffects(context, audioSessionId, true);
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
            Log.w(TAG, "Audio effects already created, re-enabling based on Settings.isEqualizerEnabled");
            boolean enabled = Settings.isEqualizerEnabled;
            sEqualizer.setEnabled(enabled);
            sBassBoost.setEnabled(enabled);
            sPresetReverb.setEnabled(enabled);
            // 注意：这里不能取消 pending 或立即写满参数——冷启动恢复路径会在 prepare 前
            // 调到本方法，满参数写入会绕过软渐变重新引入首帧爆音。用户手动开关由
            // EqualizerFragment 走 rampToSettings/softDisable 接管。
            return;
        }

        try {
            // 用户在均衡器页面交互（或播放中重新打开开关）触发的创建：
            // 信号已在流动且用户期待即时生效，按原行为创建后立即写入完整参数
            createAudioEffects(context, sAudioSessionId, false);
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
     * 冷启动/新建效果链后，由播放器在真正 play()（AudioTrack start）之后调用：
     * 效果对象创建时以中性参数（EQ 0mB / bass 0 / reverb NONE）enable，首帧切入链路
     * 不产生任何增益阶跃；出声 120ms 后用约 1 秒 smoothstep 曲线把 EQ 电平与低音强度
     * 缓慢渐变到用户设置值，最后应用混响预设。幂等，重复调用安全。
     */
    public static synchronized void softApplyPendingSettings() {
        if (!sPendingSoftApply || sEqualizer == null) {
            return;
        }
        sPendingSoftApply = false;
        startRampLocked(SOFT_APPLY_DURATION_MS, false);
        Log.i(TAG, "Soft apply scheduled: ramp " + SOFT_APPLY_DURATION_MS + "ms after "
                + RAMP_START_DELAY_MS + "ms delay");
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

    /** 是否有渐变在进行（或待启动）。渐变期间 UI 只更新显示，不得直写系统效果参数。 */
    public static synchronized boolean isRampActive() {
        return sRampRunnable != null || sPendingSoftApply;
    }

    /** 取消进行中/待启动的渐变（用户主动接管或销毁时调用）。 */
    public static synchronized void cancelRamp() {
        if (sRampRunnable != null) {
            sMainHandler.removeCallbacks(sRampRunnable);
            sRampRunnable = null;
        }
        sPendingSoftApply = false;
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

    private static void createAudioEffects(Context context, int audioSessionId, boolean soft) {
        try {
            sEqualizer = new Equalizer(0, audioSessionId);
            sBassBoost = new BassBoost(0, audioSessionId);
            sPresetReverb = new PresetReverb(0, audioSessionId);

            boolean enabled = Settings.isEqualizerEnabled;
            // 无论软硬路径，enable 前先写入中性参数，避免链路使能瞬间带增益
            applyNeutralSettings();
            sEqualizer.setEnabled(enabled);
            sBassBoost.setEnabled(enabled);
            sPresetReverb.setEnabled(enabled);

            if (enabled) {
                if (soft) {
                    // 冷启动：标记待软渐变，等调用方在 AudioTrack start 后 softApplyPendingSettings()
                    sPendingSoftApply = true;
                    Log.i(TAG, "Audio effects created with NEUTRAL settings, pending soft apply, session ID: "
                            + audioSessionId);
                } else {
                    // 播放中由用户操作创建：立即写入完整参数
                    sPendingSoftApply = false;
                    applyFullSettings();
                    Log.d(TAG, "Audio effects created and fully applied, session ID: " + audioSessionId);
                }
            } else {
                sPendingSoftApply = false;
                Log.d(TAG, "Audio effects created but disabled, session ID: " + audioSessionId);
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to create audio effects", e);
            releaseAudioEffects();
        }
    }

    /**
     * 立即把 Settings 中的完整目标参数写入系统效果（播放中用户交互路径）。
     * 冷启动路径不要调用本方法——目标参数必须经 softApplyPendingSettings() 渐变。
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

    /**
     * 写入完全中性的参数：EQ 各频段 0mB、低音强度 0、混响 NONE（整条链近似直通）。
     */
    private static void applyNeutralSettings() {
        try {
            short numberOfBands = sEqualizer.getNumberOfBands();
            for (short i = 0; i < numberOfBands; i++) {
                sEqualizer.setBandLevel(i, (short) 0);
            }
        } catch (RuntimeException e) {
            Log.e(TAG, "Neutral equalizer apply failed", e);
        }
        try {
            sBassBoost.setStrength((short) 0);
        } catch (RuntimeException e) {
            Log.e(TAG, "Neutral bass apply failed", e);
        }
        try {
            sPresetReverb.setPreset(PresetReverb.PRESET_NONE);
        } catch (RuntimeException e) {
            Log.e(TAG, "Neutral reverb apply failed", e);
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

            sPendingSoftApply = false;
            sRampRunnable = null;
            Log.d(TAG, "Audio effects released");
        } catch (Exception e) {
            Log.e(TAG, "Failed to release audio effects", e);
        }
    }
}
