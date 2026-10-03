package com.bullhead.equalizer;

import android.util.Log;

/**
 * 10 段均衡器频段电平应用桥。
 *
 * equalizer 库模块不能反向依赖 app 模块的 TenBandEqualizerProcessor，
 * 通过此桥接类，app 在 MusicService.onCreate 时注入实现，
 * EqualizerFragment 在 SeekBar 拖动 / 预设切换 / 配置导入 时回调。
 *
 * 频段电平采用毫贝（mB）：1 dB = 100 mB，范围 -1500 ~ +1500，长度固定为 10。
 */
public final class TenBandEqBridge {
    private static final String TAG = "TenBandEqBridge";

    /**
     * 由 app 模块实现的频段电平应用器。
     */
    public interface BandLevelApplier {
        /**
         * 把 10 段毫贝值应用到自研 AudioProcessor。
         *
         * @param levelsMb 长度必须为 10，每个元素范围 -1500 ~ +1500
         */
        void applyBandLevels(int[] levelsMb);
    }

    private static volatile BandLevelApplier sApplier;

    private TenBandEqBridge() {
    }

    public static void setApplier(BandLevelApplier applier) {
        sApplier = applier;
    }

    /**
     * 把 10 段毫贝值（int[10]，范围 -1500~+1500）应用到自研 AudioProcessor。
     * 调用方：EqualizerFragment 在 SeekBar 拖动 / 预设切换 / 配置导入 时调用。
     *
     * @param levelsMb 长度必须为 10
     */
    public static void applyBandLevels(int[] levelsMb) {
        if (levelsMb == null || levelsMb.length != 10) {
            Log.w(TAG, "applyBandLevels: invalid levels (len="
                    + (levelsMb == null ? "null" : levelsMb.length) + ")");
            return;
        }
        BandLevelApplier applier = sApplier;
        if (applier != null) {
            try {
                applier.applyBandLevels(levelsMb);
            } catch (Exception e) {
                Log.e(TAG, "applyBandLevels failed", e);
            }
        }
    }
}
