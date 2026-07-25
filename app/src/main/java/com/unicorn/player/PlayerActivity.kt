package com.unicorn.player

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import android.view.View
import android.view.animation.AnimationUtils
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.graphics.toColorInt
import androidx.lifecycle.lifecycleScope
import com.hw.lrcviewlib.LrcRow
import com.unicorn.player.databinding.ActivityPlayerBinding
import com.unicorn.player.service.MusicService
import com.unicorn.player.util.DisplayUtil
import com.unicorn.player.util.LogWriter
import com.unicorn.player.util.LrcFetcher
import com.unicorn.player.util.LrcHelper
import com.unicorn.player.util.LyricsSaveManager
import androidx.datastore.preferences.core.edit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.io.File

class PlayerActivity : AppCompatActivity() {

    private lateinit var binding: ActivityPlayerBinding
    private var musicService: MusicService? = null
    private var isServiceBound = false
    private lateinit var pagerAdapter: PlayerPagerAdapter
    private var isUserScrolling = false
    private val scrollDebounceHandler = Handler(Looper.getMainLooper())

    companion object {
        const val TAG = "PlayerActivity"
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

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as MusicService.MusicBinder
            musicService = binder.getService()
            isServiceBound = true
            // 服务连接后，将 MusicService 当前进度同步到 DataStore
            // 确保 Activity 重建时（如后台灭屏后重新打开）以 Service 实际进度为准
            musicService?.syncCurrentPositionToDataStore()
            setupViewPager()
            observeCurrentSong()
            observeCurrentPosition()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            musicService = null
            isServiceBound = false
        }
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

    // ===== SAF 歌词保存 =====
    // 待保存的歌词内容（授权完成后写入）
    private var pendingSaveContent: String? = null

    // 待保存的文件名
    private var pendingSaveFileName: String? = null

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
        binding.viewPager.setPageTransformer(
            androidx.viewpager2.widget.CompositePageTransformer().apply {
                // 添加透明度动画
                addTransformer { page, position ->
                    val absPosition = kotlin.math.abs(position)
                    page.alpha = 1f - absPosition * 0.3f
                }
                // 添加缩放动画
                addTransformer { page, position ->
                    val absPosition = kotlin.math.abs(position)
                    val scale = 1f - absPosition * 0.1f
                    page.scaleX = scale
                    page.scaleY = scale
                }
            })

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
        val selectLineColor = getColor(R.color.lrc_select_line)
        this.highlightRowColor = getColor(R.color.lrc_highlight_row)
        val timeTextColor = getColor(R.color.lrc_time_text)
        this.trySelectRowColor = getColor(R.color.lrc_try_select_row)

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

        // 应用字号设置（从 DataStore 读取字体大小偏好）
        applyFontSize()

        // "新建歌词"按钮：弹出歌词编辑对话框（空白编辑模式），保存后通过 SAF 写入 Documents/Unicorn/Lyrics
        binding.btnCreateLyrics.setOnClickListener {
            val song = musicService?.currentSong?.value
            if (song == null) {
                Toast.makeText(this@PlayerActivity, "当前无播放歌曲", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
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
                onSave = { content -> saveLyrics(content) },
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
        val lrcFileName = File(audioPath).nameWithoutExtension + ".lrc"
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
            onSave = { content -> saveLyrics(content) },
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
     */
    private fun saveLyrics(content: String) {
        try {
            val song = musicService?.currentSong?.value
            if (song == null) {
                Toast.makeText(this, "当前无播放歌曲", Toast.LENGTH_SHORT).show()
                return
            }
            val fileName = "${song.artist} - ${song.title}.lrc"

            // 1. 先检查是否有保存的 tree URI（没有则无法创建目录，直接授权）
            if (!LyricsSaveManager.hasSavedTreeUri(this)) {
                Log.d(TAG, "saveLyrics: 无保存的 tree URI，启动目录选择器")
                Toast.makeText(this, "请选择 Documents 目录以授权保存", Toast.LENGTH_LONG).show()
                pendingSaveContent = content
                pendingSaveFileName = fileName
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
                        openDocumentTreeLauncher.launch(LyricsSaveManager.getInitialUri())
                    }
                    return@launch
                }
                // 4. 目录就绪且权限有效，执行写入
                withContext(Dispatchers.Main) {
                    performSave(fileName, content)
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
     * 保存成功后自动刷新 LrcView 显示。
     */
    private fun performSave(fileName: String, content: String) {
        lifecycleScope.launch(Dispatchers.IO) {
            val success = LyricsSaveManager.writeLrcFile(this@PlayerActivity, fileName, content)
            withContext(Dispatchers.Main) {
                Toast.makeText(
                    this@PlayerActivity,
                    if (success) "保存成功" else "保存失败",
                    Toast.LENGTH_SHORT
                ).show()
                // 保存成功后刷新 LrcView 显示
                if (success) {
                    val audioPath = musicService?.currentSong?.value?.path
                    if (audioPath != null) {
                        reloadLrcView(audioPath)
                    }
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
        pendingSaveContent = null
        pendingSaveFileName = null
        performSave(fileName, content)
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
        containerParams.topMargin = DisplayUtil.dp2px(this, 50f)
        containerParams.bottomMargin = DisplayUtil.dp2px(this, 20f)
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
        binding.lrcView.commitLrcSettings()
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
                containerParams.height = DisplayUtil.dp2px(this@PlayerActivity, 120f)
                containerParams.topToTop = ConstraintLayout.LayoutParams.UNSET
                containerParams.bottomToBottom = ConstraintLayout.LayoutParams.PARENT_ID
                containerParams.topMargin = 0
                containerParams.bottomMargin = DisplayUtil.dp2px(this@PlayerActivity, 20f)
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
                binding.lrcView.commitLrcSettings()
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
            val fileName = File(audioPath).nameWithoutExtension + ".lrc"
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
     */
    private fun loadAndShowLrc(audioPath: String) {
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
                if (!docsRows.isNullOrEmpty()) {
                    showNoLyricsButton = false
                    binding.btnCreateLyrics.visibility = View.GONE
                    binding.lrcView.setLrcData(applyTimeLabelToRows(docsRows))
                    binding.lrcView.visibility = View.VISIBLE
                    Log.d(TAG, "Documents 歌词加载成功: ${docsRows.size} 行")
                    return@launch
                }
                // 3. 网络兜底
                binding.lrcView.setLrcData(emptyList())
                binding.lrcView.visibility = View.GONE
                Log.d(TAG, "Documents 无歌词，尝试网络下载")
                fetchLrcFromNetwork(audioPath)
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
     */
    private fun fetchLrcFromNetwork(audioPath: String) {
        LrcFetcher.fetchLrc(this@PlayerActivity, audioPath, object : LrcFetcher.LrcFetchCallback {
            override fun onSuccess(lrcFileName: String) {
                lifecycleScope.launch {
                    // 检查当前播放的还是不是这首歌，避免切歌后显示旧歌词
                    if (!isCurrentSong(audioPath)) {
                        Log.d(TAG, "歌曲已切换，丢弃旧歌词: $audioPath")
                        return@launch
                    }
                    try {
                        val lrcRows = LrcHelper.loadLrcFromAudioPath(this@PlayerActivity, audioPath)
                        if (!lrcRows.isNullOrEmpty()) {
                            showNoLyricsButton = false
                            binding.btnCreateLyrics.visibility = View.GONE
                            binding.lrcView.setLrcData(applyTimeLabelToRows(lrcRows))
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
                    // 切歌后不应影响新歌曲的 LrcView 状态
                    if (!isCurrentSong(audioPath)) return@launch
                    showNoLyricsButton = true
                    updateCreateLyricsButtonVisibility()
                    binding.lrcView.visibility = View.GONE
                    Log.d(TAG, "未搜索到网络歌词，显示新建歌词按钮")
                }
            }

            override fun onFailure(message: String) {
                lifecycleScope.launch {
                    // 切歌后不应影响新歌曲的 LrcView 状态
                    if (!isCurrentSong(audioPath)) return@launch
                    showNoLyricsButton = true
                    updateCreateLyricsButtonVisibility()
                    binding.lrcView.visibility = View.GONE
                    Log.e(TAG, "网络下载歌词失败: $message")
                }
            }
        })
    }

    /**
     * 判断指定的音频路径是否对应当前正在播放的歌曲
     */
    private fun isCurrentSong(audioPath: String): Boolean {
        val currentPath = musicService?.currentSong?.value?.path
        return currentPath != null && currentPath == audioPath
    }

    /**
     * 观察播放位置变化，同步更新歌词显示
     * 使用 seekLrcToTime 方法，传入当前播放位置（毫秒）
     * 方法内部会找到 CurrentRowTime <= position 的歌词行并滚动到该位置
     */
    private fun observeCurrentPosition() {
        musicService?.let { service ->
            service.currentPosition.observe(this) { position ->
                // 将播放进度（毫秒）传递给 LrcView，滚动到对应歌词行
                binding.lrcView.seekLrcToTime(position.toLong())
            }
        }
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
                // 没有歌曲，显示空状态
                showEmptyState()
                // 尝试从数据库加载
                loadSongsFromDatabase(service)
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

                    // 加载歌词
                    loadAndShowLrc(it.path)

                    val currentSongs = pagerAdapter.songs
                    if (currentSongs.isNotEmpty()) {
                        val index = currentSongs.indexOfFirst { it.id == song.id }
                        if (index >= 0 && binding.viewPager.currentItem != index) {
                            // 只在非用户滑动时更新ViewPager位置
                            if (!isUserScrolling) {
                                // 标记为代码设置
                                isProgrammaticSetItem = true
                                try {
                                    // 直接设置当前项，不触发onPageSelected
                                    binding.viewPager.setCurrentItem(index, false)
                                } finally {
                                    // 重置标记
                                    isProgrammaticSetItem = false
                                }
                            }
                        }
                        // 有歌曲时确保空状态被隐藏
                        hideEmptyState()
                    } else if (service.getSongList().isEmpty()) {
                        // 如果当前歌曲不为空但播放列表为空，尝试重新加载数据
                        loadSongsFromDatabase(service)
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
        val intent = Intent(this, MusicService::class.java)
        bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
        startService(intent)
    }

    @Suppress("DEPRECATION")
    private fun finishAndAnimate() {
        finish()
        // 主界面淡入，播放界面向下滑出
        overridePendingTransition(R.anim.fade_in, R.anim.slide_top_out)
    }

    override fun onPause() {
        super.onPause()
        musicService?.savePlaybackState()
    }

    override fun onResume() {
        super.onResume()
        // 字体大小/时间标签可能在设置页被修改，恢复时即时生效
        applyFontSize()
        // 恢复时重新同步歌词到当前播放位置
        musicService?.let { service ->
            if (isServiceBound) {
                val currentPos = service.getCurrentPosition()
                binding.lrcView.seekLrcToTime(currentPos.toLong())
                // 重新应用"显示时间标签"设置，让设置页面修改后即时生效
                if (LrcFetcher.lyricsEnabled) {
                    service.currentSong.value?.let { song -> loadAndShowLrc(song.path) }
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

        if (isServiceBound) {
            unbindService(serviceConnection)
            isServiceBound = false
        }
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
            binding.root.addView(emptyTextView)
        }
        emptyTextView?.visibility = View.VISIBLE
    }

    private fun hideEmptyState() {
        binding.viewPager.visibility = View.VISIBLE
        emptyTextView?.visibility = View.GONE
    }
}
