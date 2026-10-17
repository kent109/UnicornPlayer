package com.unicorn.player.equalizer

import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.util.UnstableApi
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.log10
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

        // 低音增强低架滤波器（App 内实现，替代系统 BassBoost：strength 0-1000 → 0..+15dB）
        private const val BASS_SHELF_FREQ_HZ = 100f
        private const val BASS_MAX_GAIN_DB = 15f

        /** 限幅器阈值（≈ -1.4 dBFS）：输出超过此电平即开始压缩，防止硬削波失真 */
        private const val LIMITER_THRESHOLD = 0.85f

        /** 限幅器释放时间常数（秒）：峰值回落后增益缓慢恢复，避免泵感 */
        private const val LIMITER_RELEASE_S = 0.25f

        /**
         * 余量增益估计的探测频点（Hz）：覆盖全部频段中心及低频 shelf 重叠区，
         * 在这些点上评估 10 段 + 低音低架的级联幅频响应取峰值
         */
        private val HEADROOM_PROBE_FREQS = floatArrayOf(
            25f, 31f, 40f, 50f, 62f, 80f, 100f, 125f, 160f, 250f, 350f,
            500f, 700f, 1000f, 1400f, 2000f, 2800f, 4000f, 5600f, 8000f, 11000f, 16000f
        )

        /**
         * 节目余量（dB）：级联频响峰值是按"最坏情况满幅信号在峰值频点"计算的，
         * 真实母带真峰值通常在 -6 ~ -9dBFS 且能量不集中于单一频点，全额衰减会过度
         * 压低响度。从峰值中扣除本值作为静态衰减量，残余瞬态由限幅器偶尔兜底。
         */
        private const val PROGRAM_HEADROOM_DB = 4f

        // ===== 软件环绕（虚拟音效）：FDN 人工混响，锚点与系统 PresetReverb 0-6 对齐 =====
        // NONE / SMALLROOM / MEDIUMROOM / LARGEROOM / MEDIUMHALL / LARGEHALL / PLATE
        /** 旋钮原始刻度上限（0=关闭，19=最大）；刻度经 vp = level*6/19 映射到锚点间连续插值 */
        const val SURROUND_MAX_LEVEL = 19

        /** 混响衰减时间 RT60（秒）：梳状反馈增益由它换算 */
        private val SURROUND_RT60_S = floatArrayOf(0f, 0.35f, 0.55f, 0.8f, 1.2f, 1.8f, 1.4f)

        /** 预延迟（毫秒）：直达声与第一反射的间隔，决定空间尺度感 */
        private val SURROUND_PRE_DELAY_MS = floatArrayOf(0f, 5f, 10f, 15f, 22f, 30f, 4f)

        /** 湿声混合电平 */
        private val SURROUND_WET_LEVEL = floatArrayOf(0f, 0.14f, 0.18f, 0.22f, 0.26f, 0.30f, 0.26f)

        /** 梳状环路高频阻尼（0=明亮如板式，越大越暗） */
        private val SURROUND_DAMP_COEF = floatArrayOf(0f, 0.30f, 0.30f, 0.35f, 0.50f, 0.55f, 0.08f)

        /** FDN 梳状延迟线长度（ms）：非公倍数长度抑制模式着色 */
        private val SURROUND_COMB_MS = floatArrayOf(23.7f, 28.1f, 35.3f, 43.1f)

        /** 串联全通扩散器长度（ms） */
        private val SURROUND_ALLPASS_MS = floatArrayOf(5.0f, 1.7f)
        private const val SURROUND_ALLPASS_A = 0.5f

        /**
         * 预设（0..6）→ 该档刻度区间的中心刻度（0..19）。库的派生公式为
         * `preset = level * 6 / 19`（整数除法），各预设的刻度区间是
         * {0..3}/{4..6}/{7..9}/{10..12}/{13..15}/{16..18}/{19}；取区间中心
         * 才能保证中心刻度经该公式原样映射回同一预设（NONE 特判为 0=关闭）。
         * 用于导入配置/无历史刻度时把连续强度落到与 reverbPreset 一致的锚点。
         */
        @JvmStatic
        fun surroundAnchorLevel(preset: Int): Int {
            val p = preset.coerceIn(0, 6)
            if (p == 0) return 0
            return (((p * 2 + 1) * SURROUND_MAX_LEVEL + 6) / 12).coerceAtMost(SURROUND_MAX_LEVEL)
        }

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

    // ===== 低音增强（对应原系统 BassBoost；strength 0-1000 → 0..+15dB 低架 @100Hz） =====
    private var bassStrengthPending = 0
    private var bassStrengthActive = 0
    private var bassStrengthChanged = false
    private val bassShelfCoeffs = BiquadCoeffs() // 默认恒等直通

    // ===== 软件环绕（虚拟音效；取代被部分机型旁路的系统 PresetReverb） =====
    // UI/服务线程写 pending（原始刻度 0..19），音频线程在 queueInput 同步 active
    // 并在锚点（0..6 预设）间连续插值重算网络参数
    private var surroundLevelPending = 0
    private var surroundLevelActive = 0
    private var surroundLevelChanged = false

    // FDN 网络状态（仅音频线程访问；缓冲在 configure 分配，立体声且预设>0 时参与处理）
    private var surroundEngaged = false
    private var preDelayBuf: FloatArray? = null
    private var preDelayIdx = 0
    private var preDelayFrames = 0
    private val combBuf = arrayOfNulls<FloatArray>(4)
    private val combIdx = IntArray(4)
    private val combDampState = FloatArray(4)
    private val combG = Array(4) { ParamRamp(0f) }
    private val apBuf = arrayOfNulls<FloatArray>(2)
    private val apIdx = IntArray(2)
    private val wetRamp = ParamRamp(0f)
    private val dampRamp = ParamRamp(0f)
    private val combOutScratch = FloatArray(4)
    private var surroundWetL = 0f
    private var surroundWetR = 0f

    // ===== 每通道每频段的滤波器状态（Direct Form II Transposed） =====
    private var states: Array<Array<BiquadState>> = emptyArray()
    private var bassShelfStates: Array<BiquadState> = emptyArray()

    // ===== 采样率缓存 =====
    private var sampleRate = 0

    // ===== 诊断统计（仅用于定位爆音来源，约 2s 一条日志） =====
    private var framesSinceStats = 0L
    private var clippedSamples = 0L
    private var peakMagnitude = 0f

    // ===== 输出限幅器（全通道链接，瞬时攻击/指数释放） =====
    // 兜底的极端峰值保护。常态防削波由下方静态余量增益承担——限幅器若频繁触发，
    // 其增益随节拍压放会产生"一高一低"的泵浦感（pumping）。
    private var limiterEnvelope = 0f
    private var limiterReleaseCoef = 0f
    private var limitedFrames = 0L

    // ===== 静态余量增益（headroom，防削波主力） =====
    // EQ 正增益会把满幅母带推过 ±1.0。按当前曲线级联频响的峰值增益对输出做等量
    // 预衰减（精确按 biquad 系数在探测频点上评估，而非"最大频段+低音"粗放相加，
    // 响度少降 2~4dB），使常态信号始终低于限幅阈值，限幅器只在极端峰值时偶尔
    // 介入——音色不变，响度略降但稳定，无泵浦感。
    // 余量跳变（开关均衡器/切换预设可致 ~10dB 响度差）复用冷启动淡入的 smoothstep
    // 包络思路做 500ms 长渐变，消除开关时的响度断层；小幅变化（拖动滑条微调）
    // 仍走 46ms 短渐变保持即时感。
    private var preGain = 1f
    private var preGainTarget = 1f
    private var preGainFrom = 1f
    private var preGainFramesTotal = 0
    private var preGainFramesLeft = 0

    // 单帧各通道样本暂存：先滤波求帧内峰值，再统一乘限幅增益后写出
    private var frameValues = FloatArray(2)

    // ===== 冷启动淡入剩余帧数（0 = 无淡入） =====
    private var fadeInRemaining = 0

    // ===== 起播 PCM 级音量淡入（采样级包络，遮盖 EQ 效果链切入的响度/音色突变） =====
    // 主线程经 requestStartupFadeIn() 请求，音频线程在 queueInput 消费——淡入从真正
    // 出声的第一帧开始，与音频流严格同步，不受主线程 Handler 抖动影响
    @Volatile
    private var pendingStartupFadeMs = 0L
    private var startupFadeRemaining = 0
    private var startupFadeTotal = 0

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

    /**
     * 请求一次起播音量淡入：从下一个被处理的音频缓冲起，以 smoothstep 曲线把采样
     * 增益从 0 渐变到 1（[durationMs] 毫秒）。仅在请求后确有音频帧流过时才启动，
     * 与实际出声严格同步。重复调用以最后一次为准。可在任意线程调用。
     */
    fun requestStartupFadeIn(durationMs: Long) {
        if (durationMs > 0) {
            pendingStartupFadeMs = durationMs
        }
    }

    // ===== AudioProcessor 接口实现 =====

    /**
     * 设置低音增强强度（0-1000，对应原系统 BassBoost 的 strength）。
     * 可在任意线程调用；音频线程在下一缓冲应用（中途变化走 46ms 系数渐变）。
     */
    fun setBassStrength(strength: Int) {
        val s = strength.coerceIn(0, 1000)
        synchronized(lock) {
            if (s != bassStrengthPending) {
                bassStrengthPending = s
                bassStrengthChanged = true
            }
        }
    }

    /**
     * 设置虚拟音效强度（旋钮原始刻度 0..19）。刻度经 `vp = level*6/19` 映射到
     * 系统 PresetReverb 锚点（NONE/SMALLROOM/.../PLATE，0..6）之间连续插值
     * RT60/预延迟/湿电平/阻尼，同档内微调也能听到空间渐变。App 内 FDN 人工混响
     * 实现，不依赖平台音效，全设备生效。可在任意线程调用；中途变更走 46ms 参数渐变。
     */
    fun setSurroundLevel(level: Int) {
        val l = level.coerceIn(0, SURROUND_MAX_LEVEL)
        synchronized(lock) {
            if (l != surroundLevelPending) {
                surroundLevelPending = l
                surroundLevelChanged = true
            }
        }
    }

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
            states.isNotEmpty() &&
            bassShelfStates.isNotEmpty()
        inputFormat = inputAudioFormat
        outputFormat = inputAudioFormat
        active = true

        sampleRate = newSampleRate
        if (!sameFormat) {
            states = Array(channelCount) { Array(NUM_BANDS) { BiquadState() } }
            bassShelfStates = Array(channelCount) { BiquadState() }
            limiterEnvelope = 0f
            limiterReleaseCoef = exp(-1f / (LIMITER_RELEASE_S * newSampleRate))
            initSurroundBuffers(newSampleRate)
        }

        // 同步 pending → active 并计算初始系数
        synchronized(lock) {
            for (i in 0 until NUM_BANDS) {
                bandLevelsActive[i] = bandLevelsPending[i]
            }
            bandLevelsChanged = false
            bassStrengthActive = bassStrengthPending
            bassStrengthChanged = false
            surroundLevelActive = surroundLevelPending
            surroundLevelChanged = false
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
                (if (sameFormat) " (same format, filter states preserved)" else " (new format, fresh states)") +
                ", bands=${bandLevelsActive.contentToString()}, bass=$bassStrengthActive" +
                ", surround=$surroundLevelActive/$SURROUND_MAX_LEVEL, preGain=$preGain"
        )
        return outputFormat
    }

    override fun isActive(): Boolean = active

    override fun queueInput(inputBuffer: ByteBuffer) {
        if (!active || inputFormat == EMPTY_FORMAT) return

        // 检查参数是否变化，重新计算系数（信号可能已在流动，必须走渐变而非直接换系数）
        var paramsChanged = false
        synchronized(lock) {
            if (bandLevelsChanged) {
                for (i in 0 until NUM_BANDS) {
                    bandLevelsActive[i] = bandLevelsPending[i]
                }
                bandLevelsChanged = false
                paramsChanged = true
            }
            if (bassStrengthChanged) {
                bassStrengthActive = bassStrengthPending
                bassStrengthChanged = false
                paramsChanged = true
            }
            if (surroundLevelChanged) {
                surroundLevelActive = surroundLevelPending
                surroundLevelChanged = false
                paramsChanged = true
            }
        }
        if (paramsChanged) {
            recalculateCoefficients(initial = false)
            Log.i(
                TAG,
                "EQ params changed mid-stream: bands=${bandLevelsActive.contentToString()}, " +
                    "bass=$bassStrengthActive, surround=$surroundLevelActive/$SURROUND_MAX_LEVEL, " +
                    "preGainTarget=$preGainTarget, ramping coefficients"
            )
        }

        // 消费起播淡入请求（若有）：本缓冲第一帧即包络起点
        consumePendingStartupFade()

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
        // 进行中的起播淡入一并终止：seek 是新的信号不连续点，交给上面的短防爆淡入
        startupFadeRemaining = 0
        // FDN 缓冲必须清空：混响尾音长达 1~2s，若保留会"渗"进 seek 点之后的新内容，
        // 与 EQ 滤波器的"保留状态防咔哒"策略不同，长延迟线的跨内容残留听感是脏音
        clearSurroundState()
        Log.i(TAG, "flush() called, filter states preserved, surround cleared, fade-in rearmed")
    }

    override fun reset() {
        for (channel in states.indices) {
            for (band in states[channel].indices) {
                states[channel][band].reset()
            }
        }
        for (s in bassShelfStates) {
            s.reset()
        }
        ended = false
        buffer = EMPTY_BUFFER
        inputFormat = EMPTY_FORMAT
        outputFormat = EMPTY_FORMAT
        active = false
        sampleRate = 0
        states = emptyArray()
        bassShelfStates = emptyArray()
        preDelayBuf = null
        preDelayFrames = 0
        preDelayIdx = 0
        for (k in 0 until 4) {
            combBuf[k] = null
        }
        combIdx.fill(0)
        combDampState.fill(0f)
        for (k in 0 until 2) {
            apBuf[k] = null
        }
        apIdx.fill(0)
        wetRamp.set(0f, 0)
        dampRamp.set(0f, 0)
        for (g in combG) {
            g.set(0f, 0)
        }
        surroundWetL = 0f
        surroundWetR = 0f
        surroundEngaged = false
        framesSinceStats = 0L
        clippedSamples = 0L
        peakMagnitude = 0f
        limitedFrames = 0L
        limiterEnvelope = 0f
        preGain = preGainTarget
        preGainFramesLeft = 0
        fadeInRemaining = 0
        pendingStartupFadeMs = 0L
        startupFadeRemaining = 0
        startupFadeTotal = 0
        Log.i(TAG, "reset() called, filter states cleared")
    }

    // ===== PCM 处理 =====

    private fun process16Bit(
        input: ByteBuffer,
        output: ByteBuffer,
        numFrames: Int,
        channelCount: Int
    ) {
        if (frameValues.size < channelCount) {
            frameValues = FloatArray(channelCount)
        }
        repeat(numFrames) {
            // 每帧推进一次系数渐变（所有通道共享同一组系数）
            advanceAllRamps()
            // 冷启动/seek 淡入增益（无淡入时恒为 1，分支可预测，开销可忽略）
            val frameGain = nextFadeInGain()
            // 第一遍：级联 10 个 biquad + 低音低架（低音在 App 内实现，替代系统
            // BassBoost）+ 淡入，记录各通道样本并统计帧内峰值
            var framePeak = 0f
            if (surroundEngaged) {
                // 立体声专属分支（surroundEngaged 蕴含 channelCount==2）：两通道
                // 滤波后取 mid 送 FDN 网络，去相关湿尾叠加回 L/R
                var l = input.getShort().toInt() / 32768f
                var r = input.getShort().toInt() / 32768f
                for (band in 0 until NUM_BANDS) {
                    l = processBiquad(l, states[0][band], coeffs[band])
                    r = processBiquad(r, states[1][band], coeffs[band])
                }
                l = processBiquad(l, bassShelfStates[0], bassShelfCoeffs)
                r = processBiquad(r, bassShelfStates[1], bassShelfCoeffs)
                processSurround((l + r) * 0.5f)
                val g = frameGain * preGain
                frameValues[0] = (l + surroundWetL) * g
                frameValues[1] = (r + surroundWetR) * g
                val magL = if (frameValues[0] < 0f) -frameValues[0] else frameValues[0]
                val magR = if (frameValues[1] < 0f) -frameValues[1] else frameValues[1]
                framePeak = if (magL > magR) magL else magR
            } else {
                for (ch in 0 until channelCount) {
                    val sample = input.getShort().toInt()
                    // 16-bit signed → float [-1, 1)
                    var f = sample / 32768f
                    for (band in 0 until NUM_BANDS) {
                        f = processBiquad(f, states[ch][band], coeffs[band])
                    }
                    f = processBiquad(f, bassShelfStates[ch], bassShelfCoeffs)
                    f *= frameGain * preGain
                    frameValues[ch] = f
                    val mag = if (f < 0f) -f else f
                    if (mag > framePeak) framePeak = mag
                }
            }
            // 第二遍：限幅 + 写出（16-bit 下游无法表示 >1 的样本，越界即硬削波）
            val limiterGain = advanceLimiter(framePeak)
            for (ch in 0 until channelCount) {
                var f = frameValues[ch] * limiterGain
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
        if (frameValues.size < channelCount) {
            frameValues = FloatArray(channelCount)
        }
        repeat(numFrames) {
            advanceAllRamps()
            val frameGain = nextFadeInGain()
            // 第一遍：滤波 + 淡入，记录各通道样本并统计帧内峰值
            var framePeak = 0f
            if (surroundEngaged) {
                // 立体声专属分支：两通道滤波后取 mid 送 FDN 网络，湿尾去相关叠加
                var l = input.getFloat()
                var r = input.getFloat()
                for (band in 0 until NUM_BANDS) {
                    l = processBiquad(l, states[0][band], coeffs[band])
                    r = processBiquad(r, states[1][band], coeffs[band])
                }
                l = processBiquad(l, bassShelfStates[0], bassShelfCoeffs)
                r = processBiquad(r, bassShelfStates[1], bassShelfCoeffs)
                processSurround((l + r) * 0.5f)
                val g = frameGain * preGain
                frameValues[0] = (l + surroundWetL) * g
                frameValues[1] = (r + surroundWetR) * g
                val magL = if (frameValues[0] < 0f) -frameValues[0] else frameValues[0]
                val magR = if (frameValues[1] < 0f) -frameValues[1] else frameValues[1]
                framePeak = if (magL > magR) magL else magR
            } else {
                for (ch in 0 until channelCount) {
                    var f = input.getFloat()
                    for (band in 0 until NUM_BANDS) {
                        f = processBiquad(f, states[ch][band], coeffs[band])
                    }
                    f = processBiquad(f, bassShelfStates[ch], bassShelfCoeffs)
                    f *= frameGain * preGain
                    frameValues[ch] = f
                    val mag = if (f < 0f) -f else f
                    if (mag > framePeak) framePeak = mag
                }
            }
            // 第二遍：限幅 + 写出（下游 AudioTrack 转定点时 >1 的样本会被硬削波）
            val limiterGain = advanceLimiter(framePeak)
            for (ch in 0 until channelCount) {
                val f = frameValues[ch] * limiterGain
                val mag = if (f < 0f) -f else f
                if (mag > peakMagnitude) peakMagnitude = mag
                if (mag > 1f) clippedSamples++
                output.putFloat(f)
            }
        }
        logStatsIfDue(numFrames)
    }

    /**
     * 帧级链接限幅器：包络上行瞬时跟随（无过冲）、下行按释放系数指数回落，
     * 返回本帧输出增益。各通道取峰值统一压缩（保持声像稳定），阈值以上按比例
     * 压缩而非硬切顶，消除 EQ 正增益叠加导致的削波震颤。
     */
    private fun advanceLimiter(framePeak: Float): Float {
        limiterEnvelope = if (framePeak > limiterEnvelope) {
            framePeak
        } else {
            framePeak + (limiterEnvelope - framePeak) * limiterReleaseCoef
        }
        return if (limiterEnvelope > LIMITER_THRESHOLD) {
            limitedFrames++
            LIMITER_THRESHOLD / limiterEnvelope
        } else {
            1f
        }
    }

    /** 每帧推进所有频段、低音低架与 FDN 网络参数的渐变；无渐变时开销极小。 */
    private fun advanceAllRamps() {
        for (band in 0 until NUM_BANDS) {
            coeffs[band].advanceRamp()
        }
        bassShelfCoeffs.advanceRamp()
        wetRamp.advance()
        dampRamp.advance()
        for (g in combG) {
            g.advance()
        }
        if (preGainFramesLeft > 0) {
            preGainFramesLeft--
            // smoothstep：两端斜率为 0，起止无顿挫（与冷启动淡入/开关渐变同一包络）
            val t = 1f - preGainFramesLeft.toFloat() / preGainFramesTotal.toFloat()
            val fraction = t * t * (3f - 2f * t)
            preGain = preGainFrom + (preGainTarget - preGainFrom) * fraction
            if (preGainFramesLeft == 0) preGain = preGainTarget
        }
    }

    /** 消费待处理的起播淡入请求（音频线程，本缓冲含有效数据时触发）。 */
    private fun consumePendingStartupFade() {
        val ms = pendingStartupFadeMs
        if (ms <= 0) return
        pendingStartupFadeMs = 0
        if (sampleRate <= 0) return
        startupFadeTotal = ((ms * sampleRate) / 1000L).toInt().coerceAtLeast(1)
        startupFadeRemaining = startupFadeTotal
        // 启动包络起点同为增益 0，已覆盖 23ms 防爆淡入的作用，清掉避免双重包络
        fadeInRemaining = 0
        Log.i(TAG, "Startup fade-in armed: ${ms}ms = ${startupFadeTotal} frames @ ${sampleRate}Hz")
    }

    /**
     * 每帧返回综合包络增益：短防爆淡入（线性）× 起播音量淡入（smoothstep），
     * 均无活动时恒为 1（快速路径）。
     */
    private fun nextFadeInGain(): Float {
        var g = 1f
        if (fadeInRemaining > 0) {
            g = (FADE_IN_FRAMES - fadeInRemaining).toFloat() / FADE_IN_FRAMES
            fadeInRemaining--
        }
        if (startupFadeRemaining > 0) {
            val t = 1f - startupFadeRemaining.toFloat() / startupFadeTotal
            g *= t * t * (3f - 2f * t) // smoothstep：起止斜率为 0，无顿挫
            startupFadeRemaining--
            if (startupFadeRemaining == 0) {
                Log.i(TAG, "Startup fade-in finished")
            }
        }
        return g
    }

    private fun logStatsIfDue(framesProcessed: Int) {
        framesSinceStats += framesProcessed
        if (framesSinceStats >= STATS_LOG_INTERVAL_FRAMES) {
            Log.i(
                TAG,
                "stats: frames=$framesSinceStats, limitedFrames=$limitedFrames, " +
                    "clippedSamples=$clippedSamples, peak=$peakMagnitude"
            )
            framesSinceStats = 0L
            limitedFrames = 0L
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

        // 目标系数暂存（索引 0..9 为频段，10 为低音低架），供级联频响峰值估计用
        val tB0 = FloatArray(NUM_BANDS + 1) { 1f }
        val tB1 = FloatArray(NUM_BANDS + 1)
        val tB2 = FloatArray(NUM_BANDS + 1)
        val tA1 = FloatArray(NUM_BANDS + 1)
        val tA2 = FloatArray(NUM_BANDS + 1)
        fun stash(idx: Int, b0v: Float, b1v: Float, b2v: Float, a1v: Float, a2v: Float) {
            tB0[idx] = b0v
            tB1[idx] = b1v
            tB2[idx] = b2v
            tA1[idx] = a1v
            tA2[idx] = a2v
        }

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
                    val b0v = (a * ((a + 1f) - (a - 1f) * cosw0 + 2f * sqrtA * alpha)) / a0
                    val b1v = (2f * a * ((a - 1f) - (a + 1f) * cosw0)) / a0
                    val b2v = (a * ((a + 1f) - (a - 1f) * cosw0 - 2f * sqrtA * alpha)) / a0
                    val a1v = (-2f * ((a - 1f) + (a + 1f) * cosw0)) / a0
                    val a2v = ((a + 1f) + (a - 1f) * cosw0 - 2f * sqrtA * alpha) / a0
                    stash(i, b0v, b1v, b2v, a1v, a2v)
                    applyToBand(i, initial, b0v, b1v, b2v, a1v, a2v)
                }
                NUM_BANDS - 1 -> {
                    // High Shelf（高频搁架）
                    val alpha = sinw0 / (2f * SHELF_Q)
                    val sqrtA = sqrt(a)
                    val a0 = (a + 1f) - (a - 1f) * cosw0 + 2f * sqrtA * alpha
                    val b0v = (a * ((a + 1f) + (a - 1f) * cosw0 + 2f * sqrtA * alpha)) / a0
                    val b1v = (-2f * a * ((a - 1f) + (a + 1f) * cosw0)) / a0
                    val b2v = (a * ((a + 1f) + (a - 1f) * cosw0 - 2f * sqrtA * alpha)) / a0
                    val a1v = (2f * ((a - 1f) - (a + 1f) * cosw0)) / a0
                    val a2v = ((a + 1f) - (a - 1f) * cosw0 - 2f * sqrtA * alpha) / a0
                    stash(i, b0v, b1v, b2v, a1v, a2v)
                    applyToBand(i, initial, b0v, b1v, b2v, a1v, a2v)
                }
                else -> {
                    // Peaking EQ（峰化均衡）
                    val alpha = sinw0 / (2f * PEAKING_Q)
                    val a0 = 1f + alpha / a
                    val b0v = (1f + alpha * a) / a0
                    val b1v = (-2f * cosw0) / a0
                    val b2v = (1f - alpha * a) / a0
                    val a1v = (-2f * cosw0) / a0
                    val a2v = (1f - alpha / a) / a0
                    stash(i, b0v, b1v, b2v, a1v, a2v)
                    applyToBand(i, initial, b0v, b1v, b2v, a1v, a2v)
                }
            }
        }

        // 低音增强：低架滤波器 @100Hz，strength 0-1000 → 0..+15dB（RBJ low-shelf）
        val bassGainDb = bassStrengthActive / 1000f * BASS_MAX_GAIN_DB
        val ba = 10f.pow(bassGainDb / 40f)
        val bw0 = 2f * Math.PI.toFloat() * BASS_SHELF_FREQ_HZ / fs
        val bcosw0 = cos(bw0)
        val bsinw0 = sin(bw0)
        val balpha = bsinw0 / (2f * SHELF_Q)
        val bsqrtA = sqrt(ba)
        val ba0 = (ba + 1f) + (ba - 1f) * bcosw0 + 2f * bsqrtA * balpha
        val sb0 = (ba * ((ba + 1f) - (ba - 1f) * bcosw0 + 2f * bsqrtA * balpha)) / ba0
        val sb1 = (2f * ba * ((ba - 1f) - (ba + 1f) * bcosw0)) / ba0
        val sb2 = (ba * ((ba + 1f) - (ba - 1f) * bcosw0 - 2f * bsqrtA * balpha)) / ba0
        val sa1 = (-2f * ((ba - 1f) + (ba + 1f) * bcosw0)) / ba0
        val sa2 = ((ba + 1f) + (ba - 1f) * bcosw0 - 2f * bsqrtA * balpha) / ba0
        stash(NUM_BANDS, sb0, sb1, sb2, sa1, sa2)
        if (initial) {
            bassShelfCoeffs.snapTo(sb0, sb1, sb2, sa1, sa2)
        } else {
            bassShelfCoeffs.rampTo(sb0, sb1, sb2, sa1, sa2, COEFF_RAMP_FRAMES)
        }

        // ===== 虚拟音效：FDN 人工混响网络参数（刻度 0..19 在 0..6 锚点间连续插值） =====
        val vp = surroundLevelActive.coerceIn(0, SURROUND_MAX_LEVEL) * 6f / SURROUND_MAX_LEVEL
        val wetTarget = interpAnchor(SURROUND_WET_LEVEL, vp)
        val surroundRampF = if (initial) 0 else COEFF_RAMP_FRAMES
        wetRamp.set(wetTarget, surroundRampF)
        dampRamp.set(interpAnchor(SURROUND_DAMP_COEF, vp), surroundRampF)
        val rt60 = interpAnchor(SURROUND_RT60_S, vp)
        for (k in 0 until 4) {
            // RT60 → 梳状反馈增益：g = 10^(-3·delay/RT60)（尾音在 RT60 秒内衰减 60dB）
            val g = if (rt60 > 0f) {
                10f.pow(-3f * (SURROUND_COMB_MS[k] / 1000f) / rt60)
            } else {
                0f
            }
            combG[k].set(g, surroundRampF)
        }
        // 预延迟长度随刻度变化（整数样点无法插值）：直接重建缓冲，旧尾音由
        // wet 渐变压制，听感被新空间的建立过程掩盖
        val newPreFrames = msToFrames(interpAnchor(SURROUND_PRE_DELAY_MS, vp), sampleRate)
        if (newPreFrames != preDelayFrames) {
            preDelayBuf = FloatArray(newPreFrames)
            preDelayIdx = 0
            preDelayFrames = newPreFrames
        }
        // 仅立体声启用（FDN 的 L/R 去相关湿尾依赖双通道）
        surroundEngaged = inputFormat.channelCount == 2 && combBuf[0] != null

        // 静态余量增益：在探测频点上评估 10 段 + 低音低架级联后的真实幅频响应，
        // 取峰值扣除节目余量（+1dB 探针间裕量，上限 20dB）作为整体预衰减量。
        // shelf/peak 只是部分重叠，比"最大正频段 + 低音"的粗放相加少降 2~4dB 响度；
        // 真实母带达不到满幅最坏情况，再扣 PROGRAM_HEADROOM_DB 回升响度，
        // 残余瞬态峰值由限幅器偶尔介入兜底
        var peakDb = 0f
        for (f in HEADROOM_PROBE_FREQS) {
            val w = 2f * Math.PI.toFloat() * f / fs
            var sumDb = 0f
            for (k in 0..NUM_BANDS) {
                sumDb += biquadMagnitudeDb(tB0[k], tB1[k], tB2[k], tA1[k], tA2[k], w)
            }
            if (sumDb > peakDb) peakDb = sumDb
        }
        // 湿信号叠加的最坏能量上界：dry + wet·(≈dry) → 1+wet 倍
        if (wetTarget > 0f) {
            peakDb += 20f * log10(1f + wetTarget)
        }
        val headroomDb = (peakDb - PROGRAM_HEADROOM_DB + 1f).coerceIn(0f, 20f)
        preGainTarget = 10f.pow(-headroomDb / 20f)
        if (initial) {
            preGain = preGainTarget
            preGainFrom = preGainTarget
            preGainFramesTotal = 0
            preGainFramesLeft = 0
        } else {
            // 响度变化超过 3dB（开关均衡器、切换预设）走 800ms smoothstep 长渐变；
            // 小幅微调走 46ms 短渐变保持即时感
            val hi = maxOf(preGain, preGainTarget)
            val lo = minOf(preGain, preGainTarget)
            val deltaDb = if (lo > 0f) 20f * log10(hi / lo) else 20f
            preGainFrom = preGain
            preGainFramesTotal = if (deltaDb > 3f) sampleRate * 4 / 5 else COEFF_RAMP_FRAMES
            preGainFramesLeft = preGainFramesTotal
        }
    }

    /**
     * biquad 在角频率 w 处的幅频响应（dB）。
     * H(z) = (b0 + b1·z⁻¹ + b2·z⁻²) / (1 + a1·z⁻¹ + a2·z⁻²)，z = e^jw
     */
    private fun biquadMagnitudeDb(
        b0: Float,
        b1: Float,
        b2: Float,
        a1: Float,
        a2: Float,
        w: Float
    ): Float {
        val cosw = cos(w)
        val sinw = sin(w)
        val cos2w = cos(2f * w)
        val sin2w = sin(2f * w)
        val numRe = b0 + b1 * cosw + b2 * cos2w
        val numIm = -(b1 * sinw + b2 * sin2w)
        val denRe = 1f + a1 * cosw + a2 * cos2w
        val denIm = -(a1 * sinw + a2 * sin2w)
        val numMag2 = numRe * numRe + numIm * numIm
        val denMag2 = denRe * denRe + denIm * denIm
        return 10f * log10(numMag2 / denMag2)
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

    // ===== 软件环绕（FDN 人工混响）网络 =====

    /** 毫秒 → 样点数（至少 1） */
    private fun msToFrames(ms: Float, fs: Int): Int =
        (ms * fs / 1000f).toInt().coerceAtLeast(1)

    /** 在 0..6 预设锚点表上对虚拟预设位置 vp（可带小数）做线性插值 */
    private fun interpAnchor(values: FloatArray, vp: Float): Float {
        val idx = vp.toInt().coerceIn(0, values.size - 2)
        val frac = (vp - idx).coerceIn(0f, 1f)
        return values[idx] + (values[idx + 1] - values[idx]) * frac
    }

    /** 全新管线时分配 FDN 延迟线缓冲（长度只依赖采样率；预延迟由 recalculate 按预设重建） */
    private fun initSurroundBuffers(fs: Int) {
        for (k in 0 until 4) {
            combBuf[k] = FloatArray(msToFrames(SURROUND_COMB_MS[k], fs))
        }
        combIdx.fill(0)
        combDampState.fill(0f)
        for (k in 0 until 2) {
            apBuf[k] = FloatArray(msToFrames(SURROUND_ALLPASS_MS[k], fs))
        }
        apIdx.fill(0)
        val vp = surroundLevelActive.coerceIn(0, SURROUND_MAX_LEVEL) * 6f / SURROUND_MAX_LEVEL
        preDelayBuf = FloatArray(msToFrames(interpAnchor(SURROUND_PRE_DELAY_MS, vp), fs))
        preDelayIdx = 0
        preDelayFrames = preDelayBuf!!.size
    }

    /** 清空 FDN 全部延迟线状态（flush 用：防旧内容混响尾音渗进 seek 点后的新内容） */
    private fun clearSurroundState() {
        preDelayBuf?.fill(0f)
        preDelayIdx = 0
        for (b in combBuf) {
            b?.fill(0f)
        }
        combIdx.fill(0)
        combDampState.fill(0f)
        for (b in apBuf) {
            b?.fill(0f)
        }
        apIdx.fill(0)
        surroundWetL = 0f
        surroundWetR = 0f
    }

    /**
     * FDN 网络推进一帧：输入 mid，去相关湿尾写入 surroundWetL/R。
     * 信号流：mid → 预延迟 → 两级串联全通扩散 → 4 条带阻尼梳状延迟线 → ± 混合
     * （不同符号组合使 L/R 湿信号互相关系数低，产生"包裹感"而非单声道混响）。
     */
    private fun processSurround(mid: Float) {
        val pre = preDelayBuf ?: return
        var d = pre[preDelayIdx]
        pre[preDelayIdx] = mid
        preDelayIdx = if (preDelayIdx + 1 >= pre.size) 0 else preDelayIdx + 1
        d = runAllpass(d, 0)
        d = runAllpass(d, 1)
        val damp = dampRamp.value
        for (k in 0 until 4) {
            val buf = combBuf[k] ?: continue
            val i = combIdx[k]
            val y = buf[i]
            // 环路内一阶低通：高频先衰减，模拟真实空间空气/材质吸收
            val s = combDampState[k] + damp * (y - combDampState[k])
            combDampState[k] = s
            buf[i] = d + combG[k].value * s
            combIdx[k] = if (i + 1 >= buf.size) 0 else i + 1
            combOutScratch[k] = y
        }
        val w = wetRamp.value * 0.25f
        surroundWetL = (combOutScratch[0] + combOutScratch[1] - combOutScratch[2] - combOutScratch[3]) * w
        surroundWetR = (combOutScratch[0] - combOutScratch[1] + combOutScratch[2] - combOutScratch[3]) * w
    }

    /** 第 k 级全通扩散器：out = -a·x + buf[i]，buf[i] = x + a·旧值 */
    private fun runAllpass(x: Float, k: Int): Float {
        val buf = apBuf[k] ?: return x
        val i = apIdx[k]
        val tmp = buf[i]
        buf[i] = x + SURROUND_ALLPASS_A * tmp
        apIdx[k] = if (i + 1 >= buf.size) 0 else i + 1
        return -SURROUND_ALLPASS_A * x + tmp
    }

    // ===== 内部数据类 =====

    /**
     * 单参数线性渐变器（FDN 网络的湿电平/梳状反馈增益/阻尼系数共用）。
     * 反馈增益与阻尼若阶跃变化，延迟线内已存储的能量会突然改变衰减率/音色，
     * 听感为"尾巴被掐"，46ms 渐变可完全平滑。
     */
    private class ParamRamp(initial: Float) {
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
