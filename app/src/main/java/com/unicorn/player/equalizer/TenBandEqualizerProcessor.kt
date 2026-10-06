package com.unicorn.player.equalizer

import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.util.UnstableApi
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * 10 段参数均衡器 AudioProcessor，基于级联 biquad 滤波器实现。
 *
 * 频点固定：31, 62, 125, 250, 500, 1k, 2k, 4k, 8k, 16k Hz
 * 增益范围：-15 ~ +15 dB
 * 滤波器类型：第 0 段 LowShelf、第 9 段 HighShelf、中间 8 段 Peaking
 * Q 值：peaking 段 sqrt(2)≈1.414（1 octave 带宽），shelf 段 0.707（Butterworth 最大平坦）
 *
 * 算法参考：RBJ Audio EQ Cookbook（https://www.musicdsp.org/en/latest/Filters/197-rbj-audio-eq-cookbook.html）
 * 实现：Direct Form II Transposed（对 IIR 数值稳定性最好）
 *
 * 线程安全：setBandLevels 可在 UI 线程调用，音频处理在播放线程执行。
 * 增益变更通过 volatile 标志同步，系数重算在音频线程完成，不阻塞 UI。
 */
@OptIn(markerClass = [UnstableApi::class])
class TenBandEqualizerProcessor : AudioProcessor {

    companion object {
        private const val TAG = "TenBandEqProcessor"
        const val NUM_BANDS = 10

        /** 固定频点（Hz） */
        val FREQUENCIES = intArrayOf(31, 62, 125, 250, 500, 1000, 2000, 4000, 8000, 16000)

        /** 频段标签（供 UI 使用） */
        val BAND_LABELS = arrayOf(
            "31Hz", "62Hz", "125Hz", "250Hz", "500Hz",
            "1kHz", "2kHz", "4kHz", "8kHz", "16kHz"
        )

        private const val MIN_GAIN_DB = -15f
        private const val MAX_GAIN_DB = 15f

        // Peaking Q 值：1 octave 带宽 → Q = sqrt(2)
        private const val PEAKING_Q = 1.4142135f

        // Shelf Q 值：Butterworth（最大平坦响应）
        private const val SHELF_Q = 0.70710677f

        /**
         * 增益变更时的系数渐变帧数（44.1kHz 下约 46ms）。
         * 人耳对 <20ms 的突变感知为"咔哒"爆音，40~50ms 的线性渐变可完全消除 zipper noise，
         * 同时短到不会让人察觉音色在"拖动"。
         */
        private const val COEFF_RAMP_FRAMES = 2048

        /** 诊断统计日志间隔（帧，约 2s） */
        private const val STATS_LOG_INTERVAL_FRAMES = 96000

        /**
         * 冷启动淡入帧数（44.1kHz 下约 23ms）。
         * 全新 AudioTrack start 瞬间，输入从数字静音跳到 seek 点的任意大振幅样本：
         * 零状态 biquad 链会对该阶跃产生振铃，挂在同一 session 的系统 Equalizer/BassBoost
         * 也会放大它——表现为冷启动起播的一声爆音。前 23ms 线性淡入把阶跃抹成斜坡，
         * 人耳不可察，且只在全新 configure（冷启动/真正换格式）时触发，切歌 seek 不受影响。
         */
        private const val FADE_IN_FRAMES = 1024

        private val EMPTY_BUFFER =
            ByteBuffer.allocateDirect(0).order(ByteOrder.nativeOrder())

        // 未配置格式的哨兵值：三字段均为 Format.NO_VALUE(-1)。
        // Media3 在重置音频管线时会以 NOT_SET 调用 configure()，处理器应原样返回它。
        // 注意：1.4.x 常量名为 NOT_SET，1.5+ 才更名为 NOT_SPECIFIED。
        private val EMPTY_FORMAT = AudioProcessor.AudioFormat.NOT_SET

        /** 全局实例引用，供 EqualizerFragment UI 线程访问（由 MusicService.onCreate 设置） */
        @JvmStatic
        var instance: TenBandEqualizerProcessor? = null
    }

    // ===== AudioProcessor 接口状态 =====
    private var inputFormat = EMPTY_FORMAT
    private var outputFormat = EMPTY_FORMAT
    private var active = false
    private var ended = false

    // 输出缓冲区：queueInput 写入，getOutput 读取后清空
    private var buffer: ByteBuffer = EMPTY_BUFFER

    // ===== 增益控制（线程安全） =====
    // UI 线程写入 pending，音频线程在 queueInput 开头同步到 active 并重算系数
    private val bandLevelsPending = FloatArray(NUM_BANDS) // dB，默认 0
    private val bandLevelsActive = FloatArray(NUM_BANDS)  // dB，音频线程读

    @Volatile
    private var bandLevelsChanged = true // 初始为 true 以触发首次系数计算
    private val lock = Any()

    // ===== Biquad 系数（所有通道共享，增益/采样率变更时重算） =====
    private val coeffs = Array(NUM_BANDS) { BiquadCoeffs() }

    // ===== 每通道每频段的滤波器状态（Direct Form II Transposed） =====
    private var states: Array<Array<BiquadState>> = emptyArray()

    // ===== 采样率缓存 =====
    private var sampleRate = 0

    // ===== 诊断统计（仅用于定位爆音来源，约 2s 一条日志） =====
    private var framesSinceStats = 0L
    private var clippedSamples = 0L
    private var peakMagnitude = 0f

    // ===== 冷启动淡入剩余帧数（0 = 无淡入） =====
    private var fadeInRemaining = 0

    // ===== 公共 API =====

    /**
     * 设置 10 段增益值（毫贝 mB，1 dB = 100 mB，范围 -1500 ~ +1500）。
     * 可在 UI 线程调用，下次 queueInput 时生效。
     */
    fun setBandLevels(levels: IntArray) {
        synchronized(lock) {
            for (i in 0 until minOf(levels.size, NUM_BANDS)) {
                bandLevelsPending[i] =
                    (levels[i] / 100f).coerceIn(MIN_GAIN_DB, MAX_GAIN_DB)
            }
            bandLevelsChanged = true
        }
    }

    /**
     * 获取当前增益值副本（dB）。
     */
    fun getBandLevels(): FloatArray {
        synchronized(lock) {
            return bandLevelsPending.copyOf()
        }
    }

    // ===== AudioProcessor 接口实现 =====

    override fun configure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        if (inputAudioFormat == EMPTY_FORMAT) {
            // 管线重置：按 Media3 契约停用处理器并清空格式，返回 NOT_SPECIFIED
            active = false
            inputFormat = EMPTY_FORMAT
            outputFormat = EMPTY_FORMAT
            return EMPTY_FORMAT
        }
        val encoding = inputAudioFormat.encoding
        if (encoding != C.ENCODING_PCM_16BIT && encoding != C.ENCODING_PCM_FLOAT) {
            Log.w(TAG, "Unsupported encoding: $encoding")
            throw AudioProcessor.UnhandledAudioFormatException(inputAudioFormat)
        }
        val channelCount = inputAudioFormat.channelCount
        val newSampleRate = inputAudioFormat.sampleRate
        // 格式完全相同的重复 configure（同一 AudioTrack 的管线重配）必须保留滤波器状态：
        // 此时信号仍在流动，把 z1/z2 清零会让下一帧输出瞬间跳变，表现为"啪"的爆音。
        // 只有格式真正变化（采样率/声道数不同）才需要全新的滤波器组。
        val sameFormat = active &&
            inputFormat.sampleRate == newSampleRate &&
            inputFormat.channelCount == channelCount &&
            inputFormat.encoding == encoding &&
            states.isNotEmpty()
        inputFormat = inputAudioFormat
        outputFormat = inputAudioFormat
        active = true

        sampleRate = newSampleRate
        if (!sameFormat) {
            states = Array(channelCount) { Array(NUM_BANDS) { BiquadState() } }
        }

        // 同步 pending → active 并计算初始系数
        synchronized(lock) {
            for (i in 0 until NUM_BANDS) {
                bandLevelsActive[i] = bandLevelsPending[i]
            }
            bandLevelsChanged = false
        }
        // 全新格式：信号尚未开始，系数直接落定；同格式重配：保留现有系数与状态，不重算
        if (!sameFormat) {
            recalculateCoefficients(initial = true)
            // 全新管线：给前 FADE_IN_FRAMES 帧淡入，消除 AudioTrack start 时
            // 数字静音→大振幅阶跃经零状态滤波器和系统效果链产生的冷启动爆音
            fadeInRemaining = FADE_IN_FRAMES
            Log.i(TAG, "Fresh pipeline armed: ${FADE_IN_FRAMES}-frame fade-in")
        }
        Log.d(
            TAG,
            "Configured: sampleRate=$sampleRate, channels=$channelCount, encoding=$encoding" +
                (if (sameFormat) " (same format, filter states preserved)" else " (new format, fresh states)")
        )
        return outputFormat
    }

    override fun isActive(): Boolean = active

    override fun queueInput(inputBuffer: ByteBuffer) {
        if (!active || inputFormat == EMPTY_FORMAT) return

        // 检查增益是否变化，重新计算系数（信号可能已在流动，必须走渐变而非直接换系数）
        if (bandLevelsChanged) {
            synchronized(lock) {
                for (i in 0 until NUM_BANDS) {
                    bandLevelsActive[i] = bandLevelsPending[i]
                }
                bandLevelsChanged = false
            }
            Log.i(TAG, "Band levels changed mid-stream, ramping coefficients")
            recalculateCoefficients(initial = false)
        }

        val remaining = inputBuffer.remaining()
        val bytesPerSample = if (inputFormat.encoding == C.ENCODING_PCM_16BIT) 2 else 4
        val channelCount = inputFormat.channelCount
        val frameSize = bytesPerSample * channelCount
        val numFrames = remaining / frameSize

        // 确保输出缓冲区足够大（输入输出格式一致，大小相同）
        if (buffer.capacity() < remaining) {
            buffer = ByteBuffer.allocateDirect(remaining).order(ByteOrder.nativeOrder())
        }
        buffer.clear()

        if (inputFormat.encoding == C.ENCODING_PCM_16BIT) {
            process16Bit(inputBuffer, buffer, numFrames, channelCount)
        } else {
            processFloat(inputBuffer, buffer, numFrames, channelCount)
        }

        buffer.flip()
        inputBuffer.position(inputBuffer.limit())
    }

    override fun queueEndOfStream() {
        ended = true
    }

    override fun isEnded(): Boolean = ended && buffer.remaining() == 0

    override fun getOutput(): ByteBuffer {
        val output = buffer
        buffer = EMPTY_BUFFER
        return output
    }

    // media3 1.9.0 将无参 flush() 标为 deprecated（新增 flush(StreamMetadata) 重载，
    // 其默认实现会委托调用本方法）。保留此实现是 reset() 与历史调用链路所必需的。
    @Suppress("OVERRIDE_DEPRECATION")
    override fun flush() {
        // 故意不清零滤波器状态：flush 发生在 seek、切歌等信号不连续点，此时下游紧接着
        // 仍要播放新位置的大振幅样本，若把 z1/z2 清零，下一帧滤波器输出会瞬间跳变，
        // 产生"啪"的爆音（切歌衔接处最明显）。保留状态后旧能量自然衰减（约数十 ms），
        // 行为与模拟均衡器一致，听感平滑。真正的管线销毁走 reset()，那里会重建状态。
        ended = false
        buffer = EMPTY_BUFFER
        // 重新挂 23ms 淡入：seek/切歌点同样是"旧内容→新内容"的不连续点，淡入可统一消除
        // 起头阶跃（听感上 23ms 不可察）；同时保证冷启动若存在"预缓冲被 flush 丢弃"，
        // 最终 start 时淡入仍然在册
        fadeInRemaining = FADE_IN_FRAMES
        Log.i(TAG, "flush() called, filter states preserved, fade-in rearmed")
    }

    override fun reset() {
        for (channel in states.indices) {
            for (band in states[channel].indices) {
                states[channel][band].reset()
            }
        }
        ended = false
        buffer = EMPTY_BUFFER
        inputFormat = EMPTY_FORMAT
        outputFormat = EMPTY_FORMAT
        active = false
        sampleRate = 0
        states = emptyArray()
        framesSinceStats = 0L
        clippedSamples = 0L
        peakMagnitude = 0f
        fadeInRemaining = 0
        Log.i(TAG, "reset() called, filter states cleared")
    }

    // ===== PCM 处理 =====

    private fun process16Bit(
        input: ByteBuffer,
        output: ByteBuffer,
        numFrames: Int,
        channelCount: Int
    ) {
        repeat(numFrames) {
            // 每帧推进一次系数渐变（所有通道共享同一组系数）
            advanceAllRamps()
            // 冷启动/seek 淡入增益（无淡入时恒为 1，分支可预测，开销可忽略）
            val frameGain = nextFadeInGain()
            for (ch in 0 until channelCount) {
                val sample = input.getShort().toInt()
                // 16-bit signed → float [-1, 1)
                var f = sample / 32768f
                // 级联 10 个 biquad
                for (band in 0 until NUM_BANDS) {
                    f = processBiquad(f, states[ch][band], coeffs[band])
                }
                f *= frameGain
                if (f < 0f) {
                    if (f < -1f) {
                        clippedSamples++
                        f = -1f
                    }
                } else if (f > 1f) {
                    clippedSamples++
                    f = 1f
                }
                val mag = if (f < 0f) -f else f
                if (mag > peakMagnitude) peakMagnitude = mag
                // float → 16-bit signed
                var out = (f * 32767f).toInt()
                if (out > 32767) out = 32767
                if (out < -32768) out = -32768
                output.putShort(out.toShort())
            }
        }
        logStatsIfDue(numFrames)
    }

    private fun processFloat(
        input: ByteBuffer,
        output: ByteBuffer,
        numFrames: Int,
        channelCount: Int
    ) {
        repeat(numFrames) {
            advanceAllRamps()
            val frameGain = nextFadeInGain()
            for (ch in 0 until channelCount) {
                var f = input.getFloat()
                for (band in 0 until NUM_BANDS) {
                    f = processBiquad(f, states[ch][band], coeffs[band])
                }
                f *= frameGain
                // 下游 AudioTrack 转定点时会硬削波，这里统计越界样本（诊断削波爆音用）
                if (f < 0f) {
                    if (f < -1f) {
                        clippedSamples++
                        if (f > -peakMagnitude) peakMagnitude = -f
                    } else if (-f > peakMagnitude) {
                        peakMagnitude = -f
                    }
                } else {
                    if (f > 1f) {
                        clippedSamples++
                        if (f > peakMagnitude) peakMagnitude = f
                    } else if (f > peakMagnitude) {
                        peakMagnitude = f
                    }
                }
                output.putFloat(f)
            }
        }
        logStatsIfDue(numFrames)
    }

    /** 每帧推进所有频段的系数渐变；无渐变时开销极小（10 次布尔判断）。 */
    private fun advanceAllRamps() {
        for (band in 0 until NUM_BANDS) {
            coeffs[band].advanceRamp()
        }
    }

    /** 每帧返回淡入增益：0 → 1 线性，淡入结束后恒为 1。 */
    private fun nextFadeInGain(): Float {
        if (fadeInRemaining <= 0) return 1f
        val g = (FADE_IN_FRAMES - fadeInRemaining).toFloat() / FADE_IN_FRAMES
        fadeInRemaining--
        return g
    }

    private fun logStatsIfDue(framesProcessed: Int) {
        framesSinceStats += framesProcessed
        if (framesSinceStats >= STATS_LOG_INTERVAL_FRAMES) {
            Log.i(
                TAG,
                "stats: frames=$framesSinceStats, clippedSamples=$clippedSamples, peak=$peakMagnitude"
            )
            framesSinceStats = 0L
            clippedSamples = 0L
            peakMagnitude = 0f
        }
    }

    // ===== Biquad 处理（Direct Form II Transposed） =====
    // y[n] = b0 * x[n] + z1
    // z1' = b1 * x[n] - a1 * y[n] + z2
    // z2' = b2 * x[n] - a2 * y[n]

    private fun processBiquad(x: Float, s: BiquadState, c: BiquadCoeffs): Float {
        val y = c.b0 * x + s.z1
        s.z1 = c.b1 * x - c.a1 * y + s.z2
        s.z2 = c.b2 * x - c.a2 * y
        return y
    }

    // ===== 系数计算（RBJ Audio EQ Cookbook） =====

    private fun recalculateCoefficients(initial: Boolean) {
        if (sampleRate <= 0) return
        val fs = sampleRate.toFloat()

        for (i in 0 until NUM_BANDS) {
            val f0 = FREQUENCIES[i].toFloat()
            val gainDb = bandLevelsActive[i]
            val a = 10f.pow(gainDb / 40f) // 振幅增益 A = 10^(dB/40)
            val w0 = 2f * Math.PI.toFloat() * f0 / fs
            val cosw0 = cos(w0)
            val sinw0 = sin(w0)

            when (i) {
                0 -> {
                    // Low Shelf（低频搁架）
                    val alpha = sinw0 / (2f * SHELF_Q)
                    val sqrtA = sqrt(a)
                    val a0 = (a + 1f) + (a - 1f) * cosw0 + 2f * sqrtA * alpha
                    applyToBand(
                        i, initial,
                        b0 = (a * ((a + 1f) - (a - 1f) * cosw0 + 2f * sqrtA * alpha)) / a0,
                        b1 = (2f * a * ((a - 1f) - (a + 1f) * cosw0)) / a0,
                        b2 = (a * ((a + 1f) - (a - 1f) * cosw0 - 2f * sqrtA * alpha)) / a0,
                        a1 = (-2f * ((a - 1f) + (a + 1f) * cosw0)) / a0,
                        a2 = ((a + 1f) + (a - 1f) * cosw0 - 2f * sqrtA * alpha) / a0
                    )
                }
                NUM_BANDS - 1 -> {
                    // High Shelf（高频搁架）
                    val alpha = sinw0 / (2f * SHELF_Q)
                    val sqrtA = sqrt(a)
                    val a0 = (a + 1f) - (a - 1f) * cosw0 + 2f * sqrtA * alpha
                    applyToBand(
                        i, initial,
                        b0 = (a * ((a + 1f) + (a - 1f) * cosw0 + 2f * sqrtA * alpha)) / a0,
                        b1 = (-2f * a * ((a - 1f) + (a + 1f) * cosw0)) / a0,
                        b2 = (a * ((a + 1f) + (a - 1f) * cosw0 - 2f * sqrtA * alpha)) / a0,
                        a1 = (2f * ((a - 1f) - (a + 1f) * cosw0)) / a0,
                        a2 = ((a + 1f) - (a - 1f) * cosw0 - 2f * sqrtA * alpha) / a0
                    )
                }
                else -> {
                    // Peaking EQ（峰化均衡）
                    val alpha = sinw0 / (2f * PEAKING_Q)
                    val a0 = 1f + alpha / a
                    applyToBand(
                        i, initial,
                        b0 = (1f + alpha * a) / a0,
                        b1 = (-2f * cosw0) / a0,
                        b2 = (1f - alpha * a) / a0,
                        a1 = (-2f * cosw0) / a0,
                        a2 = (1f - alpha / a) / a0
                    )
                }
            }
        }
    }

    /**
     * 将新系数应用到指定频段：
     * - 首次 configure（信号未开始）：直接落定，无渐变；
     * - 播放中途（用户调节/预设切换/延迟恢复）：从当前系数线性渐变 [COEFF_RAMP_FRAMES] 帧，
     *   避免旧滤波器状态搭配突变系数产生的 zipper 爆音。
     */
    private fun applyToBand(
        band: Int,
        initial: Boolean,
        b0: Float,
        b1: Float,
        b2: Float,
        a1: Float,
        a2: Float
    ) {
        if (initial) {
            coeffs[band].snapTo(b0, b1, b2, a1, a2)
        } else {
            coeffs[band].rampTo(b0, b1, b2, a1, a2, COEFF_RAMP_FRAMES)
        }
    }

    // ===== 内部数据类 =====

    private class BiquadCoeffs {
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

    private class BiquadState {
        var z1 = 0f
        var z2 = 0f

        fun reset() {
            z1 = 0f
            z2 = 0f
        }
    }
}
