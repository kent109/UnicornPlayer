package com.unicorn.player

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.util.TypedValue
import android.view.View
import android.view.animation.AnimationUtils
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.content.ContextCompat
import androidx.core.graphics.toColorInt
import androidx.core.view.doOnPreDraw
import androidx.datastore.preferences.core.edit
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.RecyclerView
import com.hw.lrcviewlib.LrcRow
import com.hw.lrcviewlib.LrcShowRow
import com.unicorn.player.databinding.ActivityPlayerBinding
import com.unicorn.player.manager.MusicManager
import com.unicorn.player.model.Song
import com.unicorn.player.repository.MusicRepository
import com.unicorn.player.service.MusicService
import com.unicorn.player.util.DisplayUtil
import com.unicorn.player.util.LogWriter
import com.unicorn.player.util.LrcFetcher
import com.unicorn.player.util.LrcHelper
import com.unicorn.player.util.LyricsSaveManager
import com.unicorn.player.util.toSimpleCustom
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.io.File

class PlayerActivity : BaseActivity(), MusicManager.ConnectionCallback {

    private lateinit var binding: ActivityPlayerBinding
    private var musicService: MusicService? = null
    private lateinit var pagerAdapter: PlayerPagerAdapter
    private var isUserScrolling = false
    private val scrollDebounceHandler = Handler(Looper.getMainLooper())

    // ===== 歌词进度更新抑制 =====
    // true 时忽略 currentPosition 观察者的所有更新。
    // 用于：亮屏恢复（LiveData 粘性派发旧值）、用户拖动 seekbar（异步 seek
    // 期间 mediaPlayer.currentPosition 短暂返回旧值），避免库的动画跳到错误行
    private var positionUpdateSuppressed = false

    // 抑制窗口结束时的重同步任务：按实际播放位置无动画重新定位
    private val endSuppressRunnable = Runnable { endPositionSuppress() }

    // 上次 onPause 的时间戳，onStart 用于区分"灭屏/后台离开"与"短暂时离开（如设置页）"
    private var pausedElapsedRealtime = 0L

    // 标记是否主动跳转 LrcSearchActivity。返回 onResume 时需要重新加载歌词
    // （用户可能下载了新歌词）；而灭屏亮屏时该值为 false，歌词仍在内存，
    // 只需即时重定位、不应重新加载
    private var leavingForLrcSearch = false

    // 本次 Activity 是否已完成首次歌词加载。
    // 打开 PlayerActivity 时首次加载必须传入当前播放位置即时定位，
    // 避免"先高亮第一行、再滚动到当前行"；之后切歌走正常动画流程
    private var hasInitiallyLoadedLrc = false

    // 外部文件播放（文件管理器 ACTION_VIEW）相关
    private val repository by lazy { MusicRepository(applicationContext) }

    // 待播放的外部歌曲（service 未就绪时暂存，onServiceConnected 消费）
    private data class PendingExternal(val song: Song, val isTemp: Boolean)

    private var pendingExternal: PendingExternal? = null

    // 标记服务在 PlayerActivity 启动前是否未运行（用于外部文件播放结束后停止服务）
    private var serviceWasNotRunning = false

    // 标记是否为外部文件播放会话
    private var isExternalPlayback = false

    companion object {
        const val TAG = "PlayerActivity"

        /** seekbar 操作的进度抑制时长：拖动中随 progress 续期，松手后覆盖 seek 生效期 */
        private const val SEEK_SUPPRESS_MS = 1000L

        /** 亮屏恢复时的抑制时长，覆盖粘性派发 + 首轮进度 */
        private const val RESUME_SUPPRESS_MS = 1200L

        /** onPause→onStart 间隔超过该值才视为"灭屏/后台离开"，需要抑制恢复 */
        private const val AWAY_THRESHOLD_MS = 2000L
    }

    // 匹配 LRC 行内所有时间戳标记 [mm:ss.xx] / [mm:ss.xxx]
    private val LRC_TIME_PATTERN_REGEX = Regex("\\[\\d{2}:\\d{2}\\.\\d{2,3}]")

    /**
     * 同步读取"显示时间标签"DataStore 键
     */
    private fun isTimeLabelVisible(timeLabelDefault: Boolean = false): Boolean = runBlocking {
        try {
            applicationContext.lyricsDataStore.data.first()[LyricsOptionsActivity.TIME_LABEL_VISIBLE]
                ?: timeLabelDefault
        } catch (e: Exception) {
            Log.e(TAG, "读取 time_label_visible 失败", e)
            timeLabelDefault
        }
    }

    /**
     * 根据"显示时间标签"设置转换 LrcRow 列表。
     *
     * 两路解析路径形状不同：
     *  - LrcDataBuilder.Build：RowData 仅含 [mm:ss.xx] 之后的纯文本
     *  - parseLrcManually：RowData 含整行 [mm:ss.xx]歌词文本
     *
     * 为统一格式，开关打开时一律基于 CurrentRowTime 重新格式化为 "[mm:ss.xx] 歌词文本"
     * （中括号括起，百分秒两位）。开关关闭时一律剥离时间戳标记，仅保留纯文本。
     */
    private fun applyTimeLabelToRows(rows: List<LrcRow>): List<LrcRow> {
        val visible = isTimeLabelVisible(timeLabelDefault = false)
        return rows.map { row ->
            val textOnly = row.rowData.replace(LRC_TIME_PATTERN_REGEX, "").trim()
            val formatted = formatTimeLabel(row.CurrentRowTime)
            // 注意必须保留 TimeText，LrcView 拖动时左侧时间标签就是从这字段画出的
            if (visible) {
                LrcRow("$formatted $textOnly", formatted, row.CurrentRowTime)
            } else {
                LrcRow(textOnly, formatted, row.CurrentRowTime)
            }
        }
    }

    /**
     * 把毫秒时间戳格式化成 "mm:ss.xx" 形式，用方括号包裹，例如 "[01:23.45]"
     */
    private fun formatTimeLabel(timeMs: Long): String {
        val totalCentis = timeMs / 10
        val minutes = totalCentis / 6000
        val seconds = (totalCentis % 6000) / 100
        val centis = totalCentis % 100
        return "[%02d:%02d.%02d]".format(minutes, seconds, centis)
    }

    // 防止循环调用的标志
    private var isHandlingSongChange = false

    // 标记是否是初始设置ViewPager
    private var isInitialSetup = false

    // 标记是否是代码设置的ViewPager位置
    private var isProgrammaticSetItem = false

    // 标记LrcView是否处于全屏状态
    var isLrcFullscreen = false
        private set

    // 进入全屏时正在播放的歌曲路径，用于判断切歌时是否退出全屏
    private var fullscreenSongPath: String? = null

    // 标记当前歌曲是否搜索/加载不到歌词（本地+网络均无结果），用于显示"新建歌词"按钮
    private var showNoLyricsButton = false

    // 歌词加载代数计数器：每次调用 loadAndShowLrc 递增，异步结果只在接受时检查代数是否匹配，
    // 保证只有最新一次调用的结果生效，避免并发加载互相覆盖
    private var lrcLoadGeneration = 0

    // ===== SAF 歌词保存 =====
    // 待保存的歌词内容（授权完成后写入）
    private var pendingSaveContent: String? = null

    // 待保存的文件名
    private var pendingSaveFileName: String? = null

    // 待保存的音频路径（保存时可能已切歌，避免保存到下一首的歌词文件）
    private var pendingSaveAudioPath: String? = null

    // SAF 目录选择器启动器：用户授权后持久化树 URI → 确认/创建保存目录 → 执行保存
    private val openDocumentTreeLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { treeUri ->
            if (treeUri != null) {
                LyricsSaveManager.saveTreeUri(this, treeUri)
                // 授权完成，先确认 Documents/Unicorn/Lyrics 目录存在（不存在则创建）
                lifecycleScope.launch(Dispatchers.IO) {
                    val dirReady = LyricsSaveManager.ensureSaveDirExists(this@PlayerActivity)
                    withContext(Dispatchers.Main) {
                        if (dirReady) {
                            executePendingSave()
                        } else {
                            pendingSaveContent = null
                            pendingSaveFileName = null
                            pendingSaveAudioPath = null
                            Toast.makeText(
                                this@PlayerActivity,
                                "保存目录创建失败",
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                    }
                }
            } else {
                // 用户取消选择，清理待保存数据
                pendingSaveContent = null
                pendingSaveFileName = null
                pendingSaveAudioPath = null
            }
        }

    // 普通行颜色与高亮行颜色，进入/退出全屏时切换
    private var normalRowColor: Int = 0
    private var highlightRowColor: Int = 0

    // 缓存普通行字号，用于在非全屏时让高亮行字号与普通行一致（视觉上"禁止"高亮）
    private var normalRowTextSize: Int = 0
    private var highlightRowTextSize: Int = 0

    // 拖动选中行的独立颜色和字号，非全屏时也与普通行一致
    private var trySelectRowColor: Int = 0
    private var trySelectRowTextSize: Int = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityPlayerBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // 初始化 LrcView
        initLrcView()

        // 初始化adapter（初始为空）
        pagerAdapter = PlayerPagerAdapter(this)
        binding.viewPager.adapter = pagerAdapter

        // 配置ViewPager预加载：预加载前后各2页，避免切换时跳动感
        binding.viewPager.offscreenPageLimit = 2

        // 设置页面切换动画，提供更平滑的过渡效果
        binding.viewPager.setPageTransformer { page, position ->
            transformPage(page, position)
        }

        // 向下箭头点击收起播放页面
        binding.ivCollapse.setOnClickListener {
            if (isLrcFullscreen) {
                exitLrcFullscreen()
            } else {
                finishAndAnimate()
            }
        }

        // 注册返回键回调（替代已废弃的onBackPressed）
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (isLrcFullscreen) {
                    exitLrcFullscreen()
                } else {
                    finishAndAnimate()
                }
            }
        })

        bindMusicService()

        // 处理外部启动 intent（文件管理器 ACTION_VIEW）
        handleExternalIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // singleTask 下外部再次打开会走这里；更新 intent 供后续读取
        setIntent(intent)
        handleExternalIntent(intent)
    }

    /**
     * 处理外部音频文件打开请求（ACTION_VIEW）。
     * - 仅处理 ACTION_VIEW 且带 data 的 intent，其它走原流程。
     * - 解析 URI 构建 Song，并根据路径动态判断是否在排除目录内：
     *   在排除目录内 → 临时模式（不入库、不保存进度）；
     *   否则 → 永久模式（写入 DB、加入全库队列、保存进度）。
     * - 始终从头播放，即使当前正在播放同一首歌。
     * - service 未就绪时暂存 pendingExternal，onServiceConnected 消费。
     */
    private fun handleExternalIntent(intent: Intent?) {
        if (intent?.action != Intent.ACTION_VIEW) return
        val uri: Uri = intent.data ?: return
        // 标记为外部文件播放会话
        isExternalPlayback = true
        lifecycleScope.launch(Dispatchers.IO) {
            val song = repository.buildSongFromExternalUri(uri)
            if (song == null) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(
                        this@PlayerActivity,
                        "无法打开此音频文件",
                        Toast.LENGTH_SHORT
                    ).show()
                    finish()
                }
                return@launch
            }
            // 动态判断：路径在排除目录内 → 临时模式（不入库、不保存进度）
            val isTemp = repository.isPathExcluded(song.path)
            val finalSong = if (isTemp) song else repository.ensureSongInDb(song)
            withContext(Dispatchers.Main) {
                val svc = musicService
                if (svc != null) {
                    svc.playExternalSong(finalSong, isTemp)
                    pendingExternal = null
                } else {
                    // service 未就绪，暂存待 onServiceConnected 消费
                    pendingExternal = PendingExternal(finalSong, isTemp)
                }
            }
        }
    }

    private fun transformPage(page: View, position: Float) {
        val absPosition = kotlin.math.abs(position)
        page.alpha = 1f - absPosition * 0.3f
        val scale = 1f - absPosition * 0.1f
        page.scaleX = scale
        page.scaleY = scale
    }

    fun applyPageTransformer() {
        binding.viewPager.doOnPreDraw {
            applyTransformToVisiblePages()
        }
    }

    private fun applyTransformToVisiblePages() {
        val pager = binding.viewPager
        val rv = pager.getChildAt(0) as? RecyclerView ?: return
        val currentItem = pager.currentItem
        for (i in 0 until rv.childCount) {
            val child = rv.getChildAt(i)
            val adapterPos = rv.getChildAdapterPosition(child)
            if (adapterPos != RecyclerView.NO_POSITION) {
                // ViewPager2 稳定态下 position ≈ adapterPos - currentItem
                val position = (adapterPos - currentItem).toFloat()
                transformPage(child, position)
            }
        }
    }

    /**
     * 字号档位（小/中/大）对应的像素值。
     * 数组下标对应 FONT_SIZE_SMALL=0 / MEDIUM=1 / LARGE=2，
     * 三个元素分别为 { 普通行字号, 高亮行字号, 拖动选中行字号 }。
     */
    private val fontSizeTiers = arrayOf(
        intArrayOf(13, 16, 14),  // 小
        intArrayOf(15, 18, 16),  // 中（默认）
        intArrayOf(19, 22, 20)   // 大
    )

    /**
     * 从 DataStore 读取字体大小设置并应用到 LrcView。
     * 非全屏模式：拖动选中行保持普通行字号；全屏模式：拖动选中行独立字号。
     */
    private fun applyFontSize() {
        val tier = runBlocking {
            try {
                val size =
                    applicationContext.lyricsDataStore.data.first()[LyricsOptionsActivity.FONT_SIZE]
                        ?: LyricsOptionsActivity.FONT_SIZE_MEDIUM
                size.coerceIn(0, 2)
            } catch (e: Exception) {
                Log.e(TAG, "读取 font_size 失败", e)
                LyricsOptionsActivity.FONT_SIZE_MEDIUM
            }
        }
        val sizes = fontSizeTiers[tier]
        normalRowTextSize = DisplayUtil.sp2px(this, sizes[0])
        highlightRowTextSize = DisplayUtil.sp2px(this, sizes[1])
        trySelectRowTextSize = DisplayUtil.sp2px(this, sizes[2])

        if (!::binding.isInitialized) return
        val setting = binding.lrcView.lrcSetting
            .setNormalRowTextSize(normalRowTextSize)
            .setHeightLightRowTextSize(highlightRowTextSize)
        if (isLrcFullscreen) {
            setting.setTrySelectRowTextSize(trySelectRowTextSize)
        } else {
            setting.setTrySelectRowTextSize(normalRowTextSize)
        }
        binding.lrcView.commitLrcSettings()
    }

    /**
     * 初始化 LrcView 配置
     */
    private fun initLrcView() {
        val lrcView = binding.lrcView

        // 从资源获取颜色，自动适配白天/黑夜模式
        this.normalRowColor = getColor(R.color.lrc_normal_row)
        val timeTextColor = getColor(R.color.lrc_time_text)

        // 高亮相关颜色使用主题色
        val typedValue = TypedValue()
        theme.resolveAttribute(
            com.google.android.material.R.attr.colorPrimary, typedValue, true
        )
        val themeColor = if (typedValue.resourceId != 0) {
            ContextCompat.getColor(this, typedValue.resourceId)
        } else {
            typedValue.data
        }
        val selectLineColor = themeColor
        this.highlightRowColor = ThemeSettingActivity.resolveHighlightColor(this)
        this.trySelectRowColor = (0x55000000.toInt() or (themeColor and 0x00FFFFFF))

        // 字号由 applyFontSize() 读取 DataStore 后设置（下方），此处仅提供默认值兜底
        this.normalRowTextSize = DisplayUtil.sp2px(this, 15)
        this.highlightRowTextSize = DisplayUtil.sp2px(this, 18)
        this.trySelectRowTextSize = DisplayUtil.sp2px(this, 16)

        // 配置歌词显示样式
        // 正常大小时：高亮行恢复醒目颜色（高亮当前播放行）；
        // 拖动选中行仍保持普通行样式（拖动文字不会带上选中色）。
        // 全屏时恢复为醒目的高亮色与大字号，便于拖动时定位当前行。
        lrcView.lrcSetting.setNormalRowColor(normalRowColor)
            .setTimeTextSize(DisplayUtil.sp2px(this, 14)).setSelectLineColor(selectLineColor)
            .setSelectLineTextSize(DisplayUtil.sp2px(this, 18)).setHeightRowColor(highlightRowColor)
            .setNormalRowTextSize(normalRowTextSize)
            .setHeightLightRowTextSize(highlightRowTextSize)
            .setTrySelectRowTextSize(normalRowTextSize).setTimeTextColor(timeTextColor)
            .setTrySelectRowColor(normalRowColor)
            // 默认不显示拖动指示器；进入全屏后再开启，退出全屏后关闭
            .setShowTimeText(false)
            .setShowTriangle(false)
            .setShowSelectLine(false)
        // 应用设置（确保 ShowTimeText/ShowTriangle 生效）
        lrcView.commitLrcSettings()
        // 三角形宽度默认 0，库只在 viewWidth>0 且 TriangleWidth==0 时按 viewWidth/50 赋默认值；
        // onCreate 时 viewWidth 仍是 0，显式指定像素宽度并在测量完成后再次 commit，确保三角形可见
        binding.lrcView.lrcSetting.setTriangleWidth(DisplayUtil.dp2px(this, 10f))
        binding.lrcView.post { binding.lrcView.commitLrcSettings() }

        // 隐藏"加载歌词中"提示
        lrcView.setNoDataMessage("")

        // 滚动动画时长：库默认 400ms，调快为 250ms（seekbar 定位与切行滚动均生效）
        lrcView.setAutomaticMoveAnimationDuration(250)

        // 应用字号设置（从 DataStore 读取字体大小偏好）
        applyFontSize()

        // "新建歌词"按钮：弹出歌词编辑对话框（空白编辑模式），保存后通过 SAF 写入 Documents/Unicorn/Lyrics
        binding.btnCreateLyrics.setOnClickListener {
            val song = musicService?.currentSong?.value
            if (song == null) {
                Toast.makeText(this@PlayerActivity, "当前无播放歌曲", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            // 在打开弹窗时即捕获音频路径，避免保存时已切歌导致写入到下一首的歌词文件
            val songPath = song.path
            // 以 LrcView 中心作为弹窗动画起点
            val location = IntArray(2)
            binding.lrcView.getLocationOnScreen(location)
            val centerX = location[0] + binding.lrcView.width / 2f
            val centerY = location[1] + binding.lrcView.height / 2f

            lrcPreviewDialog = LrcPreviewDialog(
                this@PlayerActivity,
                "",
                centerX,
                centerY,
                startInEditMode = true,
                onSave = { content -> saveLyrics(content, songPath) },
                showSaveButton = false
            ).also { it.show() }
        }

        // 点击LrcView进入全屏显示
        lrcView.setOnClickListener {
            showNoLyricsButton = false
            binding.btnCreateLyrics.visibility = View.GONE
            if (!isLrcFullscreen) {
                enterLrcFullscreen()
            }
        }

        // 长按LrcView（通过容器拦截，绕过 LrcView 不调用 super.onTouchEvent 的问题）
        // 全屏模式下：弹出本地歌词预览/编辑弹窗；非全屏：进入歌词搜索界面
        binding.lrcViewContainer.onLongPressListener = listener@{
            val song = musicService?.currentSong?.value
            if (song == null) {
                Toast.makeText(this@PlayerActivity, "当前无播放歌曲", Toast.LENGTH_SHORT).show()
                return@listener
            }
            if (isLrcFullscreen) {
                showLrcPreviewForLocalFile(song.path)
            } else {
                val intent = Intent(this@PlayerActivity, LrcSearchActivity::class.java).apply {
                    putExtra(LrcSearchActivity.EXTRA_ARTIST, song.artist)
                    putExtra(LrcSearchActivity.EXTRA_TITLE, song.title)
                    putExtra(LrcSearchActivity.EXTRA_AUDIO_PATH, song.path)
                }
                leavingForLrcSearch = true
                startActivity(intent)
            }
        }
    }

    /** 当前显示的歌词预览对话框 */
    private var lrcPreviewDialog: LrcPreviewDialog? = null

    /**
     * 全屏模式下长按 LrcView 时：从 Documents/Unicorn/Lyrics 读取 .lrc 文件内容，弹出编辑预览对话框
     */
    private fun showLrcPreviewForLocalFile(audioPath: String) {
        val lrcFileName = File(audioPath).nameWithoutExtension.toSimpleCustom() + ".lrc"
        val content = LyricsSaveManager.readLrcFile(this, lrcFileName)
        if (content == null) {
            Toast.makeText(this, "本地歌词文件不存在", Toast.LENGTH_SHORT).show()
            return
        }

        // 以 LrcView 中心作为弹窗动画起点
        val location = IntArray(2)
        binding.lrcView.getLocationOnScreen(location)
        val centerX = location[0] + binding.lrcView.width / 2f
        val centerY = location[1] + binding.lrcView.height / 2f

        lrcPreviewDialog = LrcPreviewDialog(
            this,
            content,
            centerX,
            centerY,
            startInEditMode = true,
            onSave = { newContent -> saveLyrics(newContent, audioPath) },
            showSaveButton = false
        ).also { it.show() }
    }

    /**
     * 歌词保存入口（SAF 实现）：
     * 1. 检查是否拥有 Documents 目录的 SAF 树 URI 权限——有则直接写入
     *    Documents/Unicorn/Lyrics/ 目录（自动创建子目录）
     * 2. 无权限则缓存待保存数据，启动 SAF 选择器定位到 Documents 目录引导用户授权
     *
     * 使用 SAF 树 URI + DocumentFile API 在授权目录内逐级操作。
     * 树 URI 授予的读写权限覆盖整个子树，无需系统级权限。
     *
     * @param content 要保存的歌词文本内容
     * @param audioPath 打开编辑弹窗时的音频路径。即便保存时已切到下一首，
     *                  也按此路径推导歌词文件名，避免覆盖到下一首的 .lrc 文件。
     */
    private fun saveLyrics(content: String, audioPath: String) {
        try {
            // 使用音频文件名（不含扩展名）作为歌词文件名，与搜索、加载保持一致
            // 例如音频文件为 "a - b.mp3"，则保存为 "a - b.lrc"
            val fileName = File(audioPath).nameWithoutExtension.toSimpleCustom() + ".lrc"

            // 1. 先检查是否有保存的 tree URI（没有则无法创建目录，直接授权）
            if (!LyricsSaveManager.hasSavedTreeUri(this)) {
                Log.d(TAG, "saveLyrics: 无保存的 tree URI，启动目录选择器")
                Toast.makeText(this, "请选择 Documents 目录以授权保存", Toast.LENGTH_LONG).show()
                pendingSaveContent = content
                pendingSaveFileName = fileName
                pendingSaveAudioPath = audioPath
                openDocumentTreeLauncher.launch(LyricsSaveManager.getInitialUri())
                return
            }

            // 2. 有 tree URI，在 IO 协程中检查/创建目录
            lifecycleScope.launch(Dispatchers.IO) {
                val dirReady = LyricsSaveManager.ensureSaveDirExists(this@PlayerActivity)
                if (!dirReady) {
                    withContext(Dispatchers.Main) {
                        Toast.makeText(this@PlayerActivity, "保存目录创建失败", Toast.LENGTH_SHORT)
                            .show()
                    }
                    return@launch
                }
                // 3. 目录就绪，再检查权限是否仍有效
                if (!LyricsSaveManager.isTreePermissionValid(this@PlayerActivity)) {
                    withContext(Dispatchers.Main) {
                        pendingSaveContent = content
                        pendingSaveFileName = fileName
                        pendingSaveAudioPath = audioPath
                        openDocumentTreeLauncher.launch(LyricsSaveManager.getInitialUri())
                    }
                    return@launch
                }
                // 4. 目录就绪且权限有效，执行写入
                withContext(Dispatchers.Main) {
                    performSave(fileName, content, audioPath)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "saveLyrics 异常: ${e.javaClass.name}: ${e.message}", e)
            Toast.makeText(this, "保存失败: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * 执行实际的保存写入操作（在协程内完成 IO）。
     * 目录已在 SAF 回调中确认存在，此处直接写入。
     * 保存成功后自动刷新 LrcView 显示（仅当被编辑的歌曲仍是当前播放歌曲时）。
     *
     * @param fileName 歌词文件名（按打开弹窗时的音频路径推导）
     * @param content 要保存的歌词文本内容
     * @param audioPath 打开编辑弹窗时的音频路径，用于判断是否仍为当前播放歌曲以决定是否刷新 LrcView
     */
    private fun performSave(fileName: String, content: String, audioPath: String) {
        lifecycleScope.launch(Dispatchers.IO) {
            val success = LyricsSaveManager.writeLrcFile(this@PlayerActivity, fileName, content)
            withContext(Dispatchers.Main) {
                Toast.makeText(
                    this@PlayerActivity,
                    if (success) "保存成功" else "保存失败",
                    Toast.LENGTH_SHORT
                ).show()
                // 保存成功后刷新 LrcView 显示
                // 仅当被编辑的歌曲仍是当前播放歌曲时才刷新；若已切歌，无需在此刷新
                // （当前正在播放另一首歌，刷新反而会显示错乱的歌词）
                if (success && audioPath == musicService?.currentSong?.value?.path) {
                    reloadLrcView(audioPath)
                }
            }
        }
    }

    /**
     * 用户授权 SAF 目录后，执行之前缓存的待保存写入。
     */
    private fun executePendingSave() {
        val content = pendingSaveContent ?: return
        val fileName = pendingSaveFileName ?: return
        val audioPath = pendingSaveAudioPath ?: return
        pendingSaveContent = null
        pendingSaveFileName = null
        pendingSaveAudioPath = null
        performSave(fileName, content, audioPath)
    }

    /**
     * 重新加载 LrcView 数据（从 Documents/Unicorn/Lyrics 解析 .lrc 文件并刷新视图）
     */
    private fun reloadLrcView(audioPath: String) {
        lifecycleScope.launch {
            try {
                val lrcRows = loadLrcFromDocuments(audioPath)
                if (!lrcRows.isNullOrEmpty()) {
                    showNoLyricsButton = false
                    binding.btnCreateLyrics.visibility = View.GONE
                    binding.lrcView.setLrcData(applyTimeLabelToRows(lrcRows))
                    binding.lrcView.visibility = View.VISIBLE
                    // 同步到当前播放位置
                    val pos = musicService?.getCurrentPosition() ?: 0
                    binding.lrcView.seekLrcToTime(pos.toLong())
                }
            } catch (e: Exception) {
                Log.e(TAG, "重载歌词失败: ${e.message}", e)
            }
        }
    }

    /**
     * 将当前高亮歌词行立即滚动到 LrcView 垂直中心。
     *
     * 背景：LrcView(V1.4) 只在播放进度回调驱动 seekLrcToTime 时居中，且
     * StartMoveAnimation 在"目标行 == 当前高亮行"时直接 return。因此进入/退出全屏
     * 改变高度后，要等下一句歌词切换（甚至暂停时永远不会）才会重新居中。
     *
     * 该库无公开 recenter API，这里在 doOnPreDraw（确保已按新高度完成测量）中
     * 复用库自身的坐标公式直接修正偏移，即时生效，与播放/暂停状态无关：
     *   delta = height/2 + hlTextSize/2 - 高亮行首显示行上次绘制的 Y
     *   FirstRowPositionY = 当前偏移 + delta
     * 若库版本变更导致反射失败，静默降级为原有行为。
     */
    private fun snapLrcHighlightToCenter() {
        binding.lrcView.doOnPreDraw {
            try {
                val view = binding.lrcView
                if (!view.hasData()) return@doOnPreDraw
                val viewCls = view.javaClass

                // 先取消可能在运行的滚动动画，避免其后续帧覆盖本次偏移
                cancelLrcMoveAnimator()

                val firstRowField = viewCls.getDeclaredField("FirstRowPositionY")
                firstRowField.isAccessible = true
                val currentOffset = firstRowField.getFloat(view)

                val hlIndexField = viewCls.getDeclaredField("HeightLightRowPosition")
                hlIndexField.isAccessible = true
                val hlIndex = hlIndexField.getInt(view)

                val mRowsField = viewCls.getDeclaredField("mRows")
                mRowsField.isAccessible = true
                @Suppress("UNCHECKED_CAST")
                val rows = mRowsField.get(view) as List<LrcRow>
                if (hlIndex !in rows.indices) return@doOnPreDraw

                val showRows: List<LrcShowRow> = rows[hlIndex].showRows ?: return@doOnPreDraw
                if (showRows.isEmpty()) return@doOnPreDraw

                // 高亮行第一个显示行上次被绘制时的绝对 Y（FirstRowPositionY + 累积行高）
                val yPosField = LrcShowRow::class.java.getDeclaredField("YPosition")
                yPosField.isAccessible = true
                val rowDrawnY = yPosField.getFloat(showRows[0])

                val setting = view.lrcSetting
                val hlSizeField = setting.javaClass.getDeclaredField("HeightLightRowTextSize")
                hlSizeField.isAccessible = true
                val hlTextSize = hlSizeField.getInt(setting)

                val targetOffset =
                    currentOffset + (view.height / 2f + hlTextSize / 2f - rowDrawnY)
                firstRowField.setFloat(view, targetOffset)
                view.invalidate()
            } catch (e: Exception) {
                Log.e(TAG, "snapLrcHighlightToCenter 失败: ${e.message}", e)
            }
        }
    }

    /**
     * 取消 LrcView 内部正在运行的滚动 ValueAnimator，并复位 OnAnimation 标记。
     * 库的 setLrcData/外部直接写偏移都不会停止该动画，运行中的动画会逐帧覆盖
     * FirstRowPositionY，因此任何"即时定位"前都必须先调用本方法。
     */
    private fun cancelLrcMoveAnimator() {
        try {
            val view = binding.lrcView
            val animatorField =
                view.javaClass.getDeclaredField("valueAnimator")
            animatorField.isAccessible = true
            (animatorField.get(view) as? android.animation.ValueAnimator)?.cancel()
            val onAnimationField =
                view.javaClass.getDeclaredField("OnAnimation")
            onAnimationField.isAccessible = true
            onAnimationField.set(view, false)
        } catch (e: Exception) {
            Log.e(TAG, "cancelLrcMoveAnimator 失败: ${e.message}", e)
        }
    }

    /**
     * 应用歌词数据并立即定位到 [position] 对应的歌词行：无动画、无首行闪烁。
     *
     * 灭屏再亮屏时走 onResume → loadAndShowLrc → setLrcData。而 setLrcData 会把
     * HeightLightRowPosition、FirstRowPositionY 全部重置为 0，导致第一帧高亮第一行，
     * 之后要等播放进度回调驱动 seekLrcToTime 才滚到当前播放行（暂停时更久）。
     *
     * 在 setLrcData 之后、首帧绘制之前，先反射触发库内部的行布局，再委托
     * [positionLrcImmediate] 直接写好高亮行下标与偏移，使第一帧即为正确状态。
     *
     * @return true 表示已立即定位；false 表示反射失败或高度未就绪，调用方回退到原流程
     */
    private fun applyLrcRowsImmediate(rows: List<LrcRow>, position: Long): Boolean {
        val view = binding.lrcView
        if (view.height <= 0) return false
        return try {
            val viewCls = view.javaClass
            // 1. 正常应用数据（内部会重置高亮/偏移并 postInvalidate）
            view.setLrcData(rows)

            // 2. 立即触发库内部的行布局，生成 ShowRows（首帧 onDraw 前通常尚未执行）。
            // 注意 initLrcRowData 非幂等：对每行 getShowRows().add(...) 并累加
            // ContentHeight。因此调用前必须先重置每行的 ShowRows 与 ContentHeight，
            // 调用后把 InitLrcRowDada 置为 true，避免 onDraw 再执行一次导致
            // 每行歌词重复显示。
            rows.forEach { row ->
                row.showRows = ArrayList()
                // ContentHeight 为纯 public 字段（无 getter），用原名访问
                row.ContentHeight = 0
            }
            val initMethod =
                viewCls.getDeclaredMethod("initLrcRowData", List::class.java)
            initMethod.isAccessible = true
            initMethod.invoke(view, rows)
            val initFlagField =
                viewCls.getDeclaredField("InitLrcRowDada")
            initFlagField.isAccessible = true
            initFlagField.set(view, true)

            // 3. 无动画定位到 position
            positionLrcImmediate(position)
        } catch (e: Exception) {
            Log.e(TAG, "applyLrcRowsImmediate 失败: ${e.message}", e)
            false
        }
    }

    /**
     * 立即（无动画）把歌词定位到 [position] 对应行并垂直居中。
     * 要求歌词数据已设置、ShowRows 已生成（数据加载后或 applyLrcRowsImmediate 后）。
     *
     * 坐标公式与库 StartMoveAnimation 完全一致：
     *   目标行首显示行 Y = FirstRowPositionY + 之前所有显示行 (RowHeight+RowPadding) 之和
     *   FirstRowPositionY = height/2 + hlTextSize/2 - 前行高度和
     *
     * @return true 成功；false 数据/高度未就绪，调用方应回退到 seekLrcToTime
     */
    private fun positionLrcImmediate(position: Long): Boolean {
        val view = binding.lrcView
        if (view.height <= 0 || !view.hasData()) return false
        return try {
            val viewCls = view.javaClass
            // 先取消滚动动画，避免其后续帧覆盖即将写入的偏移
            cancelLrcMoveAnimator()

            val mRowsField = viewCls.getDeclaredField("mRows")
            mRowsField.isAccessible = true
            @Suppress("UNCHECKED_CAST")
            val readyRows = mRowsField.get(view) as List<LrcRow>

            // 与 seekLrcToTime 一致：最后一个时间 <= position 的行；
            // position 早于首行时间时按默认首行居中处理
            val foundIndex = readyRows.indexOfLast { it.currentRowTime <= position }
            val targetIndex = if (foundIndex < 0) 0 else foundIndex

            // 累加目标行之前所有显示行的高度（RowHeight+RowPadding）
            var precedingHeight = 0f
            for (i in 0 until targetIndex) {
                val showRows = readyRows[i].showRows ?: return false
                showRows.forEach { showRow ->
                    precedingHeight += showRow.floatFieldOf("RowHeight") +
                            showRow.floatFieldOf("RowPadding")
                }
            }

            val setting = view.lrcSetting
            val hlSizeField =
                setting.javaClass.getDeclaredField("HeightLightRowTextSize")
            hlSizeField.isAccessible = true
            val hlTextSize = hlSizeField.getInt(setting)

            var targetOffset = view.height / 2f + hlTextSize / 2f - precedingHeight
            // 与库 makeFirstRowPositionSecure 一致：偏移不超过 height/2（首行边界保护）
            val halfHeight = view.height / 2f
            if (targetOffset > halfHeight) targetOffset = halfHeight

            val firstRowField = viewCls.getDeclaredField("FirstRowPositionY")
            firstRowField.isAccessible = true
            firstRowField.setFloat(view, targetOffset)

            val hlPosField = viewCls.getDeclaredField("HeightLightRowPosition")
            hlPosField.isAccessible = true
            hlPosField.setInt(view, targetIndex)

            view.invalidate()
            true
        } catch (e: Exception) {
            Log.e(TAG, "positionLrcImmediate 失败: ${e.message}", e)
            false
        }
    }

    /** 反射读取 LrcShowRow 的包级可见 float 字段（RowHeight / RowPadding） */
    private fun LrcShowRow.floatFieldOf(name: String): Float {
        val field = LrcShowRow::class.java.getDeclaredField(name)
        field.isAccessible = true
        return field.getFloat(this)
    }

    /**
     * 即时应用歌词并定位到 [position]；若视图尚未完成测量（如服务在 onCreate 中
     * 同步连接、协程极快返回时 height 仍为 0），延迟到下一帧绘制前重试，
     * 保证第一帧高亮的就是 [position] 对应行，不会先闪现第一行。
     */
    private fun applyLrcRowsWhenReady(rows: List<LrcRow>, position: Long) {
        if (applyLrcRowsImmediate(rows, position)) return
        binding.lrcView.doOnPreDraw {
            if (!applyLrcRowsImmediate(rows, position)) {
                // 重试仍失败（如反射异常）：回退普通流程
                binding.lrcView.setLrcData(rows)
            }
        }
    }

    /**
     * 进入歌词全屏模式
     * ViewPager向上滑出消失，LrcView向上滑入全屏显示
     */
    private fun enterLrcFullscreen() {
        if (isLrcFullscreen) return
        isLrcFullscreen = true
        // 记录进入全屏时的歌曲路径，切歌判断用
        fullscreenSongPath = musicService?.currentSong?.value?.path
        // 进入全屏时隐藏"新建歌词"按钮
        binding.btnCreateLyrics.visibility = View.GONE

        // 容器约束设为全屏（容器在 ConstraintLayout 内）
        val containerParams = binding.lrcViewContainer.layoutParams as ConstraintLayout.LayoutParams
        containerParams.height = 0 // 0dp，配合约束撑满父布局
        containerParams.matchConstraintPercentHeight = 0.92f
        containerParams.topMargin = DisplayUtil.dp2px(this, 12f)
        containerParams.bottomMargin = 0
        containerParams.marginStart = 0
        containerParams.marginEnd = 0
        containerParams.topToTop = ConstraintLayout.LayoutParams.PARENT_ID
        containerParams.bottomToBottom = ConstraintLayout.LayoutParams.PARENT_ID
        binding.lrcViewContainer.layoutParams = containerParams

        // LrcView 在 FrameLayout 容器内撑满
        val lrcParams = binding.lrcView.layoutParams as android.widget.FrameLayout.LayoutParams
        lrcParams.height = android.widget.FrameLayout.LayoutParams.MATCH_PARENT
        binding.lrcView.layoutParams = lrcParams
        binding.lrcView.visibility = View.VISIBLE

        // ViewPager向上滑出动画 (XML定义)
        val slideOutUp = AnimationUtils.loadAnimation(this, R.anim.slide_out_up)
        slideOutUp.setAnimationListener(object :
            android.view.animation.Animation.AnimationListener {
            override fun onAnimationStart(animation: android.view.animation.Animation?) {}
            override fun onAnimationEnd(animation: android.view.animation.Animation?) {
                binding.viewPager.visibility = View.GONE
            }

            override fun onAnimationRepeat(animation: android.view.animation.Animation?) {}
        })
        binding.viewPager.startAnimation(slideOutUp)

        // LrcView向上滑入全屏动画 (XML定义)
        val slideUpIn = AnimationUtils.loadAnimation(this, R.anim.slide_up_in)
        slideUpIn.startOffset = 0
        binding.lrcView.startAnimation(slideUpIn)

        // 全屏模式：开启拖动时间标签 + 三角形指示器，高亮行+拖动选中行恢复醒目颜色和字号
        binding.lrcView.lrcSetting.setShowTimeText(true).setShowTriangle(true)
            .setShowSelectLine(true)
            .setHeightRowColor(highlightRowColor)
            .setHeightLightRowTextSize(highlightRowTextSize)
            .setTrySelectRowColor(trySelectRowColor)
            .setTrySelectRowTextSize(trySelectRowTextSize)
        commitLrcSettingsAndSnap()
    }

    /**
     * 提交歌词设置并在新布局下把当前高亮行立即滚动到垂直中心
     */
    private fun commitLrcSettingsAndSnap() {
        binding.lrcView.commitLrcSettings()
        snapLrcHighlightToCenter()
    }

    /**
     * 退出歌词全屏模式
     * LrcView向下滑出，ViewPager向下滑入恢复显示
     */
    private fun exitLrcFullscreen() {
        if (!isLrcFullscreen) return
        isLrcFullscreen = false
        // 清除全屏歌曲路径标记
        fullscreenSongPath = null

        // ViewPager向下滑入动画 (XML定义)
        val slideInDown = AnimationUtils.loadAnimation(this, R.anim.slide_in_down)
        slideInDown.startOffset = 0
        binding.viewPager.visibility = View.VISIBLE
        binding.viewPager.startAnimation(slideInDown)

        // LrcView向下滑出动画 (XML定义)
        val slideOutDown = AnimationUtils.loadAnimation(this, R.anim.slide_out_down)
        slideOutDown.setAnimationListener(object :
            android.view.animation.Animation.AnimationListener {
            override fun onAnimationStart(animation: android.view.animation.Animation?) {}
            override fun onAnimationEnd(animation: android.view.animation.Animation?) {
                // 退出全屏后，若无歌词则重新显示"新建歌词"按钮
                updateCreateLyricsButtonVisibility()
                // 恢复容器原始布局（ConstraintLayout.LayoutParams）
                val containerParams =
                    binding.lrcViewContainer.layoutParams as ConstraintLayout.LayoutParams
                // 复位百分比哨兵值
                containerParams.matchConstraintPercentHeight = 0.18f
                containerParams.topToTop = ConstraintLayout.LayoutParams.UNSET
                containerParams.bottomToBottom = ConstraintLayout.LayoutParams.PARENT_ID
                containerParams.topMargin = 0
                containerParams.bottomMargin = DisplayUtil.dp2px(this@PlayerActivity, 24f)
                containerParams.marginStart = DisplayUtil.dp2px(this@PlayerActivity, 16f)
                containerParams.marginEnd = DisplayUtil.dp2px(this@PlayerActivity, 16f)
                binding.lrcViewContainer.layoutParams = containerParams

                // 恢复 LrcView 在 FrameLayout 内撑满
                val lrcParams =
                    binding.lrcView.layoutParams as android.widget.FrameLayout.LayoutParams
                lrcParams.height = android.widget.FrameLayout.LayoutParams.MATCH_PARENT
                binding.lrcView.layoutParams = lrcParams

                // 退出全屏：关闭拖动时间标签 + 三角形指示器
                // 高亮行恢复为醒目颜色（非全屏下仍高亮当前播放行）
                // 拖动选中行保持普通行样式（拖动文字不带选中色）
                binding.lrcView.lrcSetting.setShowTimeText(false).setShowTriangle(false)
                    .setShowSelectLine(false)
                    .setHeightRowColor(highlightRowColor)
                    .setHeightLightRowTextSize(highlightRowTextSize)
                    .setTrySelectRowColor(normalRowColor)
                    .setTrySelectRowTextSize(normalRowTextSize)
                commitLrcSettingsAndSnap()
            }

            override fun onAnimationRepeat(animation: android.view.animation.Animation?) {}
        })
        binding.lrcView.startAnimation(slideOutDown)
    }

    /**
     * 检查是否拥有有效的 SAF 树 URI 权限
     */
    private fun hasValidSafPermission(): Boolean {
        return LyricsSaveManager.hasSavedTreeUri(this) &&
                LyricsSaveManager.isTreePermissionValid(this)
    }

    /**
     * 从 Documents/Unicorn/Lyrics 加载歌词
     */
    private suspend fun loadLrcFromDocuments(audioPath: String): List<LrcRow>? {
        return withContext(Dispatchers.IO) {
            val fileName = File(audioPath).nameWithoutExtension.toSimpleCustom() + ".lrc"
            val content = LyricsSaveManager.readLrcFile(this@PlayerActivity, fileName)
                ?: return@withContext null
            LrcHelper.parseLrcContent(this@PlayerActivity, content)
        }
    }

    /**
     * 关闭歌词功能并同步开关状态
     */
    private fun disableLyrics() {
        showNoLyricsButton = false
        binding.btnCreateLyrics.visibility = View.GONE
        binding.lrcView.setLrcData(emptyList())
        binding.lrcView.visibility = View.GONE
        LrcFetcher.lyricsEnabled = false
        lifecycleScope.launch {
            applicationContext.lyricsDataStore.edit {
                it[LyricsOptionsActivity.LYRICS_ENABLED] = false
            }
        }
    }

    /**
     * 加载并显示歌词，优先级：Documents/Unicorn/Lyrics > 网络下载。
     * 无 SAF 权限时不显示歌词、不下载，并关闭歌词开关。
     *
     * @param syncPosition 非 null 时（如 onResume 灭屏亮屏恢复），数据应用后立即
     *                     定位到该播放位置，避免 setLrcData 重置导致首帧高亮第一行；
     *                     null 时保持原有加载动画流程
     */
    private fun loadAndShowLrc(
        audioPath: String,
        syncPosition: Long? = null
    ) {
        Log.d(TAG, "loadAndShowLrc: path=$audioPath")
        // 歌词功能被关闭时，清空并隐藏 LrcView 和"新建歌词"按钮
        if (!LrcFetcher.lyricsEnabled) {
            showNoLyricsButton = false
            binding.btnCreateLyrics.visibility = View.GONE
            binding.lrcView.setLrcData(emptyList())
            binding.lrcView.visibility = View.GONE
            return
        }
        // 切换到不同歌曲时，如果LrcView处于全屏状态，才退出全屏；
        // 同一首歌（如 onResume 重新加载歌词）保持全屏不变
        if (isLrcFullscreen && audioPath != fullscreenSongPath) {
            exitLrcFullscreen()
        }

        val myGeneration = ++lrcLoadGeneration
        lifecycleScope.launch {
            try {
                // 1. SAF 权限前置检查——无权限则关闭歌词开关，不显示不下载
                if (!hasValidSafPermission()) {
                    Log.d(TAG, "无 SAF 权限，关闭歌词功能")
                    disableLyrics()
                    return@launch
                }
                // 2. Documents 优先
                val docsRows = loadLrcFromDocuments(audioPath)
                Log.d(TAG, "loadLrcFromDocuments 返回: ${docsRows?.size ?: "null"} 行")
                // 检查代数：若加载期间有新的 loadAndShowLrc 调用，丢弃本次结果
                if (lrcLoadGeneration != myGeneration) {
                    Log.d(TAG, "歌词加载已过时(generation=$myGeneration, current=$lrcLoadGeneration)，丢弃: $audioPath")
                    return@launch
                }
                if (!docsRows.isNullOrEmpty()) {
                    showNoLyricsButton = false
                    binding.btnCreateLyrics.visibility = View.GONE
                    val displayRows = applyTimeLabelToRows(docsRows)
                    if (syncPosition != null) {
                        // 即时定位（视图未就绪时等下一帧），首帧即为当前播放行
                        applyLrcRowsWhenReady(displayRows, syncPosition)
                    } else {
                        binding.lrcView.setLrcData(displayRows)
                    }
                    binding.lrcView.visibility = View.VISIBLE
                    Log.d(TAG, "Documents 歌词加载成功: ${docsRows.size} 行")
                    return@launch
                }
                // 3. 网络兜底
                binding.lrcView.setLrcData(emptyList())
                binding.lrcView.visibility = View.GONE
                Log.d(TAG, "Documents 无歌词，尝试网络下载")
                fetchLrcFromNetwork(audioPath, myGeneration, syncPosition)
            } catch (e: Exception) {
                binding.lrcView.visibility = View.GONE
                Log.e(TAG, "加载歌词失败", e)
            }
        }
    }

    /**
     * 根据当前状态更新"新建歌词"按钮可见性：仅当标记为无歌词、且 LrcView 未处于全屏时显示
     */
    private fun updateCreateLyricsButtonVisibility() {
        if (showNoLyricsButton && !isLrcFullscreen) {
            binding.btnCreateLyrics.visibility = View.VISIBLE
        } else {
            binding.btnCreateLyrics.visibility = View.GONE
        }
    }

    /**
     * 从网络下载歌词，下载成功后刷新 LrcView
     *
     * @param syncPosition 非 null 时下载成功后立即定位到该播放位置，避免首帧高亮第一行
     */
    private fun fetchLrcFromNetwork(
        audioPath: String,
        generation: Int,
        syncPosition: Long? = null
    ) {
        LrcFetcher.fetchLrc(this@PlayerActivity, audioPath, object : LrcFetcher.LrcFetchCallback {
            override fun onSuccess(lrcFileName: String) {
                lifecycleScope.launch {
                    // 检查代数：若下载期间有新的 loadAndShowLrc 调用，丢弃本次结果
                    if (lrcLoadGeneration != generation) {
                        Log.d(TAG, "网络歌词加载已过时(generation=$generation, current=$lrcLoadGeneration)，丢弃: $audioPath")
                        return@launch
                    }
                    try {
                        val lrcRows = loadLrcFromDocuments(audioPath)
                        if (!lrcRows.isNullOrEmpty()) {
                            showNoLyricsButton = false
                            binding.btnCreateLyrics.visibility = View.GONE
                            val displayRows = applyTimeLabelToRows(lrcRows)
                            if (syncPosition != null) {
                                applyLrcRowsWhenReady(displayRows, syncPosition)
                            } else {
                                binding.lrcView.setLrcData(displayRows)
                            }
                            binding.lrcView.visibility = View.VISIBLE
                            Log.d(TAG, "网络歌词加载成功: ${lrcRows.size} 行")
                        } else {
                            showNoLyricsButton = true
                            updateCreateLyricsButtonVisibility()
                            binding.lrcView.visibility = View.GONE
                            Log.d(TAG, "下载的歌词文件解析为空")
                        }
                    } catch (e: Exception) {
                        binding.lrcView.visibility = View.GONE
                        Log.e(TAG, "加载网络歌词失败", e)
                    }
                }
            }

            override fun onFileExists(lrcFileName: String) {
                // 已在 loadAndShowLrc 中处理本地文件，此处忽略
                Log.d(TAG, "歌词文件已存在（回调）: $lrcFileName")
            }

            override fun onNoLyricsFound() {
                lifecycleScope.launch {
                    // 代数不匹配说明已有更新的歌词请求，不应影响新歌曲的状态
                    if (lrcLoadGeneration != generation) return@launch
                    showNoLyricsButton = true
                    updateCreateLyricsButtonVisibility()
                    binding.lrcView.visibility = View.GONE
                    Log.d(TAG, "未搜索到网络歌词，显示新建歌词按钮")
                }
            }

            override fun onFailure(message: String) {
                lifecycleScope.launch {
                    if (lrcLoadGeneration != generation) return@launch
                    showNoLyricsButton = true
                    updateCreateLyricsButtonVisibility()
                    binding.lrcView.visibility = View.GONE
                    Log.e(TAG, "网络下载歌词失败: $message")
                }
            }
        })
    }

    /**
     * 观察播放位置变化，同步更新歌词显示
     * 使用 seekLrcToTime 方法，传入当前播放位置（毫秒）
     * 方法内部会找到 CurrentRowTime <= position 的歌词行并滚动到该位置。
     * 抑制窗口内（亮屏恢复/seekbar 拖动）忽略所有更新，避免旧进度驱动动画跳到错误行
     */
    private fun observeCurrentPosition() {
        musicService?.let { service ->
            service.currentPosition.observe(this) { position ->
                if (positionUpdateSuppressed) return@observe
                // 将播放进度（毫秒）传递给 LrcView，滚动到对应歌词行
                binding.lrcView.seekLrcToTime(position.toLong())
            }
        }
    }

    /**
     * 开启进度更新抑制窗口：窗口内观察者忽略一切进度，结束时按实际位置无动画重定位。
     * 重复调用会重置窗口（拖动 seekbar 时持续续期）。
     */
    private fun beginPositionSuppress(durationMs: Long) {
        positionUpdateSuppressed = true
        scrollDebounceHandler.removeCallbacks(endSuppressRunnable)
        scrollDebounceHandler.postDelayed(endSuppressRunnable, durationMs)
    }

    /**
     * 抑制窗口结束：按 MediaPlayer 实际位置无动画重新定位歌词，随后恢复正常更新
     */
    private fun endPositionSuppress() {
        positionUpdateSuppressed = false
        val position = musicService?.getCurrentPosition() ?: return
        positionLrcImmediate(position.toLong())
    }

    /**
     * 用户正在拖动（或点击）seekbar，由 PlayerFragment 在每次 progress 变化时回调：
     * 1. 续期进度抑制窗口（异步 seek 期间进度线程可能读到旧位置）
     * 2. 取消可能在播放中的滚动动画（如松手动画未结束又再次拖动）
     * 拖动期间歌词保持原位不动，避免逐帧跳变；松手时才播放滚动动画
     */
    fun onUserSeeking() {
        beginPositionSuppress(SEEK_SUPPRESS_MS)
        cancelLrcMoveAnimator()
    }

    /**
     * 用户松手结束 seekbar 操作，由 PlayerFragment 在 onStopTrackingTouch 回调。
     * 调用库的 seekLrcToTime：目标行与当前高亮行不同时，库用 400ms ValueAnimator
     * 平滑滚动过去（库内部会先取消旧动画）；目标行相同则直接 return（无需滚动）。
     * 抑制窗口覆盖异步 seek 生效期，窗口结束再按实际位置校正一次终态
     */
    fun onUserSeekEnd(position: Int) {
        beginPositionSuppress(SEEK_SUPPRESS_MS)
        binding.lrcView.seekLrcToTime(position.toLong())
    }

    private fun setupViewPager() {
        musicService?.let { service ->
            // 设置页面切换监听（只注册一次）
            binding.viewPager.registerOnPageChangeCallback(object :
                androidx.viewpager2.widget.ViewPager2.OnPageChangeCallback() {
                override fun onPageScrolled(
                    position: Int, positionOffset: Float, positionOffsetPixels: Int
                ) {
                    super.onPageScrolled(position, positionOffset, positionOffsetPixels)
                    // 标记用户正在滑动
                    isUserScrolling = positionOffsetPixels != 0
                }

                override fun onPageScrollStateChanged(state: Int) {
                    super.onPageScrollStateChanged(state)
                    // 当滑动状态改变时
                    when (state) {
                        androidx.viewpager2.widget.ViewPager2.SCROLL_STATE_IDLE -> {
                            // 滑动停止
                            isUserScrolling = false
                        }

                        androidx.viewpager2.widget.ViewPager2.SCROLL_STATE_DRAGGING, androidx.viewpager2.widget.ViewPager2.SCROLL_STATE_SETTLING -> {
                            // 用户开始滑动
                            isUserScrolling = true
                        }
                    }
                }

                override fun onPageSelected(position: Int) {
                    super.onPageSelected(position)
                    // 只在用户滑动时处理，代码设置时不处理
                    if (!isProgrammaticSetItem) {
                        handlePageSelected(position)
                    }
                }
            })

            // 标记为初始设置
            isInitialSetup = true
            // 标记为代码设置
            isProgrammaticSetItem = true

            // 检查是否有歌曲列表
            val songs = service.getSongList()
            if (songs.isEmpty()) {
                if (!isExternalPlayback) {
                    // 没有歌曲，显示空状态
                    showEmptyState()
                    // 尝试从数据库加载
                    loadSongsFromDatabase(service)
                }
            } else {
                if (isExternalPlayback) {
                    // 外部播放正在处理中，不要覆盖即将播放的外部歌曲
                } else {
                    // 有歌曲，隐藏空状态
                    hideEmptyState()

                    // 找到当前播放歌曲在列表中的位置
                    val currentSong = service.currentSong.value
                    val startPosition = if (currentSong != null) {
                        val index = songs.indexOfFirst { it.id == currentSong.id }
                        if (index >= 0) {
                            Log.d(TAG, "Found current song at position $index")
                            index
                        } else {
                            Log.w(
                                TAG,
                                "Current song not in list, using index ${service.currentIndex}"
                            )
                            service.currentIndex
                        }
                    } else {
                        Log.d(TAG, "No current song, using index ${service.currentIndex}")
                        service.currentIndex
                    }

                    // 设置初始歌曲
                    pagerAdapter.updateSongs(songs, startPosition)
                    Log.d(
                        TAG,
                        "Initialized ViewPager with ${songs.size} songs, starting at position $startPosition"
                    )

                    // 重要：确保初始设置时不触发播放
                    // 只更新当前歌曲，不播放
                    Log.d(TAG, "Setting current song: ${songs[startPosition].title}")
                    service.setCurrentSong(songs[startPosition])
                }
            }

            // 初始设置完成
            isProgrammaticSetItem = false
            isInitialSetup = false
        }
    }

    private fun observeCurrentSong() {
        musicService?.let { service ->
            // 观察当前歌曲变化，同步ViewPager位置和歌词
            service.currentSong.observe(this) { song ->
                if (isHandlingSongChange) return@observe

                song?.let {
                    // 如果是初始设置，不处理
                    if (isInitialSetup) return@observe

                    // 首次加载（打开 PlayerActivity）：传入当前播放位置即时定位，
                    // 避免"先高亮第一行、再滚动到当前行"；之后切歌走正常动画流程
                    if (!hasInitiallyLoadedLrc) {
                        hasInitiallyLoadedLrc = true
                        loadAndShowLrc(it.path, service.getCurrentPosition().toLong())
                    } else {
                        // 加载歌词
                        loadAndShowLrc(it.path)
                    }

                    val currentSongs = pagerAdapter.songs
                    if (currentSongs.isNotEmpty()) {
                        val index = currentSongs.indexOfFirst { it.id == song.id }
                        if (index >= 0) {
                            if (binding.viewPager.currentItem != index && !isUserScrolling) {
                                // 只在非用户滑动时更新ViewPager位置
                                isProgrammaticSetItem = true
                                try {
                                    // 直接设置当前项，不触发onPageSelected
                                    binding.viewPager.setCurrentItem(index, false)
                                } finally {
                                    // 重置标记
                                    isProgrammaticSetItem = false
                                }
                            }
                        } else {
                            // 当前歌曲不在 pagerAdapter 中（如临时播放结束切回全库），
                            // 从 service 的 songList 刷新 pagerAdapter
                            val list = service.getSongList()
                            val idx = list.indexOfFirst { it.id == song.id }.coerceAtLeast(0)
                            if (list.isNotEmpty()) {
                                isProgrammaticSetItem = true
                                try {
                                    pagerAdapter.updateSongs(list, idx)
                                    binding.viewPager.setCurrentItem(idx, false)
                                } finally {
                                    isProgrammaticSetItem = false
                                }
                            }
                        }
                        // 有歌曲时确保空状态被隐藏
                        hideEmptyState()
                    } else {
                        // pagerAdapter 为空：优先从 service.songList 填充（外部播放刚设置），
                        // 否则从数据库加载
                        val list = service.getSongList()
                        if (list.isNotEmpty()) {
                            val idx = list.indexOfFirst { it.id == song.id }.coerceAtLeast(0)
                            isProgrammaticSetItem = true
                            try {
                                pagerAdapter.updateSongs(list, idx)
                                binding.viewPager.setCurrentItem(idx, false)
                            } finally {
                                isProgrammaticSetItem = false
                            }
                            hideEmptyState()
                        } else {
                            if (!isExternalPlayback) {
                                loadSongsFromDatabase(service)
                            }
                        }
                    }
                }
            }
        }
    }

    private fun handlePageSelected(position: Int) {
        if (isHandlingSongChange) return

        musicService?.let { service ->
            val songs = pagerAdapter.songs
            if (songs.isEmpty()) return

            // 边界检查
            if (position !in songs.indices) return

            // 如果是初始设置，不触发播放
            if (isInitialSetup) {
                Log.d(TAG, "Initial setup, skipping playback")
                return
            }

            // 如果当前歌曲位置与目标位置不同，才切换歌曲
            if (service.currentIndex != position) {
                isHandlingSongChange = true
                try {
                    // 更新adapter数据
                    pagerAdapter.updateSongs(songs, position)

                    // 设置当前歌曲
                    service.currentIndex = position
                    service.setCurrentSong(songs[position])
                    service.requestAudioFocusAndPlayCurrentSong()
                } finally {
                    isHandlingSongChange = false
                }

                // 切换歌曲后加载歌词，动态显示/隐藏 LrcView
                loadAndShowLrc(songs[position].path)
            }
        }
    }

    private fun loadSongsFromDatabase(service: MusicService) {
        if (isExternalPlayback) return
        // 从数据库加载歌曲列表
        val database = com.unicorn.player.database.MusicDatabase.getDatabase(this)

        // 使用协程作用域来collect数据
        lifecycleScope.launch {
            try {
                database.songDao().getAllSongs().collect { songs ->
                    if (songs.isNotEmpty()) {
                        // 找到当前播放歌曲在列表中的位置
                        val currentSong = service.currentSong.value
                        val startPosition = if (currentSong != null) {
                            val index = songs.indexOfFirst { it.id == currentSong.id }
                            if (index >= 0) {
                                Log.d(
                                    TAG,
                                    "Found current song at position $index in loaded list"
                                )
                                index
                            } else {
                                Log.w(
                                    TAG,
                                    "Current song not in loaded list, using index ${service.currentIndex}"
                                )
                                service.currentIndex.coerceIn(0, songs.size - 1)
                            }
                        } else {
                            Log.d(
                                TAG,
                                "No current song, using index ${service.currentIndex}"
                            )
                            service.currentIndex.coerceIn(0, songs.size - 1)
                        }

                        // 设置歌曲列表到service
                        service.setSongList(songs, startPosition)

                        // 更新ViewPager
                        pagerAdapter.updateSongs(songs, startPosition)
                        // 加载成功后隐藏空状态
                        hideEmptyState()
                        Log.d(
                            TAG,
                            "Loaded ${songs.size} songs from database, starting at position $startPosition"
                        )

                        // 重要：确保初始设置时不触发播放
                        // 只更新当前歌曲，不播放
                        service.setCurrentSong(songs[startPosition])

                        // 注意：不再自动重启播放，让歌曲从当前位置继续
                        // 这样可以避免进入PlayerActivity时歌曲从头开始播放的问题
                    } else {
                        Log.w(TAG, "No songs found in database")
                    }
                }
            } catch (e: Exception) {
                LogWriter.writeError(
                    TAG, "Error loading songs from database: ${e.message}", e
                )
            }
        }
    }

    private fun bindMusicService() {
        // 记录服务在绑定前是否未运行（用于外部文件播放结束后停止服务）
        serviceWasNotRunning = MusicManager.getService() == null
        // 注册服务连接回调，在异步绑定完成时收到通知
        MusicManager.registerConnectionCallback(this)
        // 使用MusicManager绑定MusicService
        val alreadyConnected = MusicManager.bind(this)
        // 获取MusicService实例（如果服务已绑定则立即返回，否则需等待回调）
        musicService = MusicManager.getService()
        if (alreadyConnected && musicService != null) {
            // 服务已连接（非首次绑定），直接执行初始化
            onServiceConnected(musicService)
        }
    }

    // ==================== MusicManager.ConnectionCallback 实现 ====================

    override fun onServiceConnected(service: MusicService?) {
        musicService = service ?: return
        musicService?.syncCurrentPositionToDataStore()
        setupViewPager()
        observeCurrentSong()
        observeCurrentPosition()
        // 消费待播放的外部歌曲（外部启动时 service 异步绑定完成）
        pendingExternal?.let { pending ->
            musicService?.playExternalSong(pending.song, pending.isTemp)
            pendingExternal = null
        }
    }

    override fun onServiceDisconnected() {
        musicService = null
    }

    @Suppress("DEPRECATION")
    private fun finishAndAnimate() {
        finish()
        // 主界面淡入，播放界面向下滑出
        overridePendingTransition(R.anim.fade_in, R.anim.slide_top_out)
    }

    override fun onPause() {
        super.onPause()
        pausedElapsedRealtime = SystemClock.elapsedRealtime()
        musicService?.savePlaybackState()
    }

    override fun onStart() {
        // 判断是否为灭屏/长时间后台后恢复。正常播放期间 LiveData 在后台持续累积进度，
        // 观察者在 super.onStart() 中激活时会粘性派发旧值，可能驱动动画跳到旧行。
        val wasLongAway = pausedElapsedRealtime != 0L &&
                SystemClock.elapsedRealtime() - pausedElapsedRealtime >= AWAY_THRESHOLD_MS
        if (wasLongAway) {
            // 必须在 super.onStart() 之前抑制：粘性派发发生在 super 调用内部
            beginPositionSuppress(RESUME_SUPPRESS_MS)
            // 取消残留的滚动动画
            cancelLrcMoveAnimator()
        }
        super.onStart()
    }

    override fun onResume() {
        super.onResume()
        // 字体大小可能在歌词选项中被修改，恢复时即时生效
        applyFontSize()
        musicService?.let { service ->
            val currentPos = service.getCurrentPosition().toLong()
            when {
                // 从歌词搜索页返回：用户可能下载了新歌词，需要重新加载并即时定位
                leavingForLrcSearch -> {
                    leavingForLrcSearch = false
                    if (LrcFetcher.lyricsEnabled) {
                        service.currentSong.value?.let { song ->
                            loadAndShowLrc(song.path, currentPos)
                        }
                    }
                }
                // 灭屏亮屏等恢复场景：歌词数据仍在内存中，只无动画即时重定位，
                // 绝不重新加载——setLrcData 的重置 + 异步加载会引入错误帧竞争
                LrcFetcher.lyricsEnabled -> {
                    positionLrcImmediate(currentPos)
                }
                else -> {
                    binding.lrcView.seekLrcToTime(currentPos)
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        // 关闭歌词预览对话框，避免内存泄漏
        lrcPreviewDialog?.dismiss()
        lrcPreviewDialog = null
        // 取消所有进行中的歌词网络请求，避免回调访问已销毁的 UI
        LrcFetcher.cancelAll()
        // 取消待处理的任务
        scrollDebounceHandler.removeCallbacksAndMessages(null)
        isHandlingSongChange = false

        // 移除空状态TextView
        emptyTextView?.let { textView ->
            binding.root.removeView(textView)
            emptyTextView = null
        }

        // 使用MusicManager解绑
        MusicManager.unregisterConnectionCallback(this)
        // 外部文件播放且服务之前未运行：停止服务，避免残留
        if (isExternalPlayback && serviceWasNotRunning) {
            musicService?.stopSelf()
        }
        MusicManager.unbind(this)
    }

    // 提供给Fragment访问Service的方法
    fun getMusicService(): MusicService? = musicService

    // 空状态提示
    private var emptyTextView: TextView? = null

    private fun showEmptyState() {
        binding.viewPager.visibility = View.GONE
        // 创建一个简单的空状态提示
        if (emptyTextView == null) {
            emptyTextView = TextView(this).apply {
                text = "没有找到音乐文件\n\n请在设备存储中添加音乐文件后重试"
                textSize = 16f
                gravity = android.view.Gravity.CENTER
                setPadding(50, 50, 50, 50)
                setTextColor("#666666".toColorInt())
            }
            val params = ConstraintLayout.LayoutParams(
                ConstraintLayout.LayoutParams.WRAP_CONTENT,
                ConstraintLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                topToTop = ConstraintLayout.LayoutParams.PARENT_ID
                bottomToBottom = ConstraintLayout.LayoutParams.PARENT_ID
                startToStart = ConstraintLayout.LayoutParams.PARENT_ID
                endToEnd = ConstraintLayout.LayoutParams.PARENT_ID
            }
            binding.root.addView(emptyTextView, params)
        }
        emptyTextView?.visibility = View.VISIBLE
    }

    private fun hideEmptyState() {
        binding.viewPager.visibility = View.VISIBLE
        emptyTextView?.visibility = View.GONE
    }
}
