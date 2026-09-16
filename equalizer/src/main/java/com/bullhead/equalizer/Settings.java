package com.bullhead.equalizer;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import java.util.Arrays;
import java.util.stream.Collectors;

public class Settings {
    public static boolean isEqualizerEnabled = true;
    public static boolean isEqualizerReloaded = true;
    public static int[] seekbarpos = new int[5];
    public static int presetPos;
    // 默认值必须为合法范围 [0, 6] 内的 PRESET_NONE(0)，
    // 否则在用户开 EQ 但未触摸虚拟旋钮时，-1 会被 savePlaybackState 持久化进 DataStore，
    // 下次启动读到 -1 又传给 PresetReverb.setPreset 导致 crash。
    public static short reverbPreset = 0;
    // 同上，BassBoost 合法范围 [0, 1000]，默认 0 而非 -1。
    public static short bassStrength = 0;
    public static EqualizerModel equalizerModel;
    public static double ratio = 1.0;
    public static boolean isEditing = false;

    private static final String TAG = "Settings";
    private static final String PREFS_NAME = "EqualizerPrefs";
    private static final String KEY_CUSTOM_PRESET = "custom_preset";
    private static final String KEY_PRESET_POS = "preset_pos";
    private static final String KEY_BASS_PROGRESS = "bass_progress";
    private static final String KEY_REVERB_PROGRESS = "reverb_progress";

    public static void saveCustomPreset(Context context, int[] posArr) {
        if (posArr == null || posArr.length == 0) {
            return;
        }
        String join = Arrays.stream(posArr).mapToObj(String::valueOf).collect(Collectors.joining(","));
        try {
            SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
            SharedPreferences.Editor editor = prefs.edit();
            editor.putString(KEY_CUSTOM_PRESET, join);
            editor.apply();
            Log.d(TAG, "Saved custom preset: " + join);
        } catch (Exception e) {
            Log.e(TAG, "Failed to save custom preset", e);
        }
    }

    public static int[] loadCustomPreset(Context context) {
        try {
            SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
            String saved = prefs.getString(KEY_CUSTOM_PRESET, null);
            if (saved != null) {
                Log.d(TAG, "Loaded custom preset: " + saved);
                return Arrays.stream(saved.split(",")).mapToInt(Integer::parseInt).toArray();
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to load custom preset", e);
        }
        return null;
    }

    public static void savePresetPos(Context context, int pos) {
        if (pos < 0) {
            return;
        }
        try {
            SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
            SharedPreferences.Editor editor = prefs.edit();
            editor.putInt(KEY_PRESET_POS, pos);
            editor.apply();
            Log.d(TAG, "Saved preset pos: " + pos);
        } catch (Exception e) {
            Log.e(TAG, "Failed to save preset pos", e);
        }
    }

    public static int loadPresetPos(Context context) {
        try {
            SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
            return prefs.getInt(KEY_PRESET_POS, -1);
        } catch (Exception e) {
            Log.e(TAG, "Failed to load preset pos", e);
        }
        return -1;
    }

    public static void saveBassProgress(Context context, int bass) {
        if (bass < 0) {
            return;
        }
        try {
            SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
            SharedPreferences.Editor editor = prefs.edit();
            editor.putInt(KEY_BASS_PROGRESS, bass);
            editor.apply();
            Log.d(TAG, "Saved bass: " + bass);
        } catch (Exception e) {
            Log.e(TAG, "Failed to save bass", e);
        }
    }

    public static void saveReverbProgress(Context context, int reverb) {
        if (reverb < 0) {
            return;
        }
        try {
            SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
            SharedPreferences.Editor editor = prefs.edit();
            editor.putInt(KEY_REVERB_PROGRESS, reverb);
            editor.apply();
            Log.d(TAG, "Saved reverb: " + reverb);
        } catch (Exception e) {
            Log.e(TAG, "Failed to save reverb", e);
        }
    }

    public static int loadBassProgress(Context context) {
        try {
            SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
            return prefs.getInt(KEY_BASS_PROGRESS, -1);
        } catch (Exception e) {
            Log.e(TAG, "Failed to load bass", e);
        }
        return -1;
    }

    public static int loadReverbProgress(Context context) {
        try {
            SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
            return prefs.getInt(KEY_REVERB_PROGRESS, -1);
        } catch (Exception e) {
            Log.e(TAG, "Failed to load reverb", e);
        }
        return -1;
    }
}
