package com.unicorn.player.equalizer

/**
 * 参数渐变（线性 ramp）：驱动 BiquadCoeffs 从旧系数平滑过渡到新系数。
 */
internal class ParamRamp(initial: Float) {
    var value = initial
        private set
    private var from = initial
    private var to = initial
    private var left = 0
    private var total = 1

    fun set(target: Float, rampFrames: Int) {
        to = target
        if (rampFrames <= 0) {
            value = target
            from = target
            left = 0
        } else {
            from = value
            total = rampFrames
            left = rampFrames
        }
    }

    fun advance() {
        if (left > 0) {
            val t = 1f - left.toFloat() / total
            value = from + (to - from) * t
            left--
            if (left == 0) {
                value = to
            }
        }
    }
}

/**
 * Biquad 滤波器系数容器，带从旧到新系数的线性渐变（ramp）。
 * 音频帧处理过程中每帧推进一次插值，避免增益跳变。
 */
internal class BiquadCoeffs {
    // 当前实际生效的系数（每帧由 from→to 渐变更新；无渐变时恒等于 to）
    var b0 = 1f
    var b1 = 0f
    var b2 = 0f
    var a1 = 0f
    var a2 = 0f

    // 渐变起点（系数重算瞬间的当前值）
    private var fromB0 = 1f
    private var fromB1 = 0f
    private var fromB2 = 0f
    private var fromA1 = 0f
    private var fromA2 = 0f

    // 渐变目标（最新增益对应的系数）
    private var toB0 = 1f
    private var toB1 = 0f
    private var toB2 = 0f
    private var toA1 = 0f
    private var toA2 = 0f

    // 剩余/总渐变帧数（0 表示当前系数已是目标值）
    private var rampRemaining = 0
    private var rampTotal = 1

    /** 直接落定为目标系数（首次 configure / 信号尚未开始时使用，不需要渐变）。 */
    fun snapTo(nb0: Float, nb1: Float, nb2: Float, na1: Float, na2: Float) {
        b0 = nb0; b1 = nb1; b2 = nb2; a1 = na1; a2 = na2
        fromB0 = nb0; fromB1 = nb1; fromB2 = nb2; fromA1 = na1; fromA2 = na2
        toB0 = nb0; toB1 = nb1; toB2 = nb2; toA1 = na1; toA2 = na2
        rampRemaining = 0
        rampTotal = 1
    }

    /**
     * 从当前生效系数渐变到新系数（播放中途改变增益时调用，避免旧状态配新系数的瞬时跳变）。
     * @param rampFrames 渐变时长（音频帧数）
     */
    fun rampTo(nb0: Float, nb1: Float, nb2: Float, na1: Float, na2: Float, rampFrames: Int) {
        fromB0 = b0; fromB1 = b1; fromB2 = b2; fromA1 = a1; fromA2 = a2
        toB0 = nb0; toB1 = nb1; toB2 = nb2; toA1 = na1; toA2 = na2
        rampTotal = rampFrames.coerceAtLeast(1)
        rampRemaining = rampTotal
    }

    /** 每帧推进一次系数插值；返回 false 表示渐变已结束。 */
    fun advanceRamp(): Boolean {
        if (rampRemaining <= 0) return false
        val t = 1f - rampRemaining.toFloat() / rampTotal
        b0 = fromB0 + (toB0 - fromB0) * t
        b1 = fromB1 + (toB1 - fromB1) * t
        b2 = fromB2 + (toB2 - fromB2) * t
        a1 = fromA1 + (toA1 - fromA1) * t
        a2 = fromA2 + (toA2 - fromA2) * t
        rampRemaining--
        if (rampRemaining == 0) {
            b0 = toB0; b1 = toB1; b2 = toB2; a1 = toA1; a2 = toA2
        }
        return true
    }
}

/** Biquad 滤波器的延迟状态（Direct Form II Transposed 实现）。 */
internal class BiquadState {
    var z1 = 0f
    var z2 = 0f

    fun reset() {
        z1 = 0f
        z2 = 0f
    }
}
