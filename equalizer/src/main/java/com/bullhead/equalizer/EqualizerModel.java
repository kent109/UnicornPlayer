package com.bullhead.equalizer;

import java.io.Serializable;

/**
 * Created by Harjot on 09-Dec-16.
 */

public class EqualizerModel implements Serializable {
    private boolean isEqualizerEnabled;
    private int[] seekbarpos = new int[5];
    private int presetPos;
    private short reverbPreset;
    private short bassStrength;

    public EqualizerModel() {
        isEqualizerEnabled = true;
        // 默认值必须落在系统合法范围内：
        // - reverbPreset: PresetReverb.PRESET_NONE = 0（合法范围 [0, 6]）
        // - bassStrength: 0（合法范围 [0, 1000]）
        // 否则 BassBoost.setProperties / PresetReverb.setPreset 会抛 RuntimeException 导致 crash。
        reverbPreset = 0;
        bassStrength = 0;
    }

    public boolean isEqualizerEnabled() {
        return isEqualizerEnabled;
    }

    public void setEqualizerEnabled(boolean equalizerEnabled) {
        isEqualizerEnabled = equalizerEnabled;
    }

    public int[] getSeekbarpos() {
        return seekbarpos;
    }

    public void setSeekbarpos(int[] seekbarpos) {
        this.seekbarpos = seekbarpos;
    }

    public int getPresetPos() {
        return presetPos;
    }

    public void setPresetPos(int presetPos) {
        this.presetPos = presetPos;
    }

    public short getReverbPreset() {
        return reverbPreset;
    }

    public void setReverbPreset(short reverbPreset) {
        this.reverbPreset = reverbPreset;
    }

    public short getBassStrength() {
        return bassStrength;
    }

    public void setBassStrength(short bassStrength) {
        this.bassStrength = bassStrength;
    }
}
