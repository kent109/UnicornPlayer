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
        inputFormat = inputAudioFormat
        outputFormat = inputAudioFormat
        active = true

        val channelCount = inputAudioFormat.channelCount
        sampleRate = inputAudioFormat.sampleRate
        states = Array(channelCount) { Array(NUM_BANDS) { BiquadState() } }

        // 同步 pending → active 并计算初始系数
        synchronized(lock) {
            for (i in 0 until NUM_BANDS) {
                bandLevelsActive[i] = bandLevelsPending[i]
            }
            bandLevelsChanged = false
        }
        recalculateCoefficients()
        Log.d(
            TAG,
            "Configured: sampleRate=$sampleRate, channels=$channelCount, encoding=$encoding"
        )
        return outputFormat
    }

    override fun isActive(): Boolean = active

    override fun queueInput(inputBuffer: ByteBuffer) {
        if (!active || inputFormat == EMPTY_FORMAT) return

        // 检查增益是否变化，重新计算系数
        if (bandLevelsChanged) {
            synchronized(lock) {
                for (i in 0 until NUM_BANDS) {
                    bandLevelsActive[i] = bandLevelsPending[i]
                }
                bandLevelsChanged = false
            }
            recalculateCoefficients()
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
        for (channel in states.indices) {
            for (band in states[channel].indices) {
                states[channel][band].reset()
            }
        }
        ended = false
        buffer = EMPTY_BUFFER
    }

    override fun reset() {
        flush()
        inputFormat = EMPTY_FORMAT
        outputFormat = EMPTY_FORMAT
        active = false
        sampleRate = 0
        states = emptyArray()
    }

    // ===== PCM 处理 =====

    private fun process16Bit(
        input: ByteBuffer,
        output: ByteBuffer,
        numFrames: Int,
        channelCount: Int
    ) {
        (0 until numFrames)
            .forEach { _ ->
                for (ch in 0 until channelCount) {
                    val sample = input.getShort().toInt()
                    // 16-bit signed → float [-1, 1)
                    var f = sample / 32768f
                    // 级联 10 个 biquad
                    for (band in 0 until NUM_BANDS) {
                        f = processBiquad(f, states[ch][band], coeffs[band])
                    }
                    // float → 16-bit signed
                    var out = (f * 32767f).toInt()
                    if (out > 32767) out = 32767
                    if (out < -32768) out = -32768
                    output.putShort(out.toShort())
                }
            }
    }

    private fun processFloat(
        input: ByteBuffer,
        output: ByteBuffer,
        numFrames: Int,
        channelCount: Int
    ) {
        (0 until numFrames).forEach { _ ->
            for (ch in 0 until channelCount) {
                var f = input.getFloat()
                for (band in 0 until NUM_BANDS) {
                    f = processBiquad(f, states[ch][band], coeffs[band])
                }
                output.putFloat(f)
            }
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

    private fun recalculateCoefficients() {
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
                    coeffs[i].set(
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
                    coeffs[i].set(
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
                    coeffs[i].set(
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

    // ===== 内部数据类 =====

    private class BiquadCoeffs {
        var b0 = 1f
        var b1 = 0f
        var b2 = 0f
        var a1 = 0f
        var a2 = 0f

        fun set(b0: Float, b1: Float, b2: Float, a1: Float, a2: Float) {
            this.b0 = b0
            this.b1 = b1
            this.b2 = b2
            this.a1 = a1
            this.a2 = a2
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
