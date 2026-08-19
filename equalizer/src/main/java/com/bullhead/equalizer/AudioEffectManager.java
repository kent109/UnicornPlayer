package com.bullhead.equalizer;

import android.content.Context;
import android.media.audiofx.BassBoost;
import android.media.audiofx.Equalizer;
import android.media.audiofx.PresetReverb;
import android.util.Log;

import java.util.concurrent.atomic.AtomicBoolean;

public class AudioEffectManager {
    private static final String TAG = "AudioEffectManager";
    private static volatile Equalizer sEqualizer = null;
    private static volatile BassBoost sBassBoost = null;
    private static volatile PresetReverb sPresetReverb = null;
    private static volatile int sAudioSessionId = 0;
    private static final AtomicBoolean sIsInitialized = new AtomicBoolean(false);

    public static synchronized void initialize(Context context, int audioSessionId) {
        if (sIsInitialized.get()) {
            Log.w(TAG, "AudioEffectManager already initialized");
            return;
        }

        sAudioSessionId = audioSessionId;
        sIsInitialized.set(true);

        createAudioEffects(context, audioSessionId);
    }

    public static synchronized void destroy() {
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
            return;
        }

        try {
            createAudioEffects(context, sAudioSessionId);
        } catch (Exception e) {
            Log.e(TAG, "Failed to create audio effects", e);
            releaseAudioEffects();
        }
    }

    public static synchronized void disableEffects() {
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

    private static void createAudioEffects(Context context, int audioSessionId) {
        try {
            sEqualizer = new Equalizer(0, audioSessionId);
            sBassBoost = new BassBoost(0, audioSessionId);
            sPresetReverb = new PresetReverb(0, audioSessionId);

            boolean enabled = Settings.isEqualizerEnabled;
            sEqualizer.setEnabled(enabled);
            sBassBoost.setEnabled(enabled);
            sPresetReverb.setEnabled(enabled);

            if (enabled) {
                loadEqualizerSettings(context);
            }

            Log.d(TAG, "Audio effects created and enabled, session ID: " + audioSessionId);
        } catch (Exception e) {
            Log.e(TAG, "Failed to create audio effects", e);
            releaseAudioEffects();
        }
    }

    private static void loadEqualizerSettings(Context context) {
        try {
            if (Settings.equalizerModel == null) {
                Settings.equalizerModel = new EqualizerModel();
                Settings.equalizerModel.setReverbPreset(PresetReverb.PRESET_NONE);
                Settings.equalizerModel.setBassStrength((short) (1000 / 19));
            }

            if (Settings.presetPos == 0) {
                short numberOfBands = sEqualizer.getNumberOfBands();
                for (short bandIdx = 0; bandIdx < numberOfBands; bandIdx++) {
                    int savedLevel = Settings.seekbarpos[bandIdx];
                    sEqualizer.setBandLevel(bandIdx, (short) savedLevel);
                }
            } else {
                sEqualizer.usePreset((short) (Settings.presetPos - 1));
            }

            if (sBassBoost != null) {
                BassBoost.Settings bassBoostSetting = sBassBoost.getProperties();
                BassBoost.Settings bassBoostSettingTemp = new BassBoost.Settings(bassBoostSetting.toString());
                bassBoostSettingTemp.strength = Settings.equalizerModel.getBassStrength();
                sBassBoost.setProperties(bassBoostSettingTemp);
            }

            if (sPresetReverb != null) {
                try {
                    sPresetReverb.setPreset(Settings.equalizerModel.getReverbPreset());
                } catch (IllegalArgumentException e) {
                    Log.e(TAG, "Invalid reverb preset value: " + Settings.equalizerModel.getReverbPreset());
                    sPresetReverb.setPreset(PresetReverb.PRESET_NONE);
                }
            }

            Log.d(TAG, "Equalizer settings loaded successfully");
        } catch (Exception e) {
            Log.e(TAG, "Failed to load equalizer settings", e);
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

            Log.d(TAG, "Audio effects released");
        } catch (Exception e) {
            Log.e(TAG, "Failed to release audio effects", e);
        }
    }
}
