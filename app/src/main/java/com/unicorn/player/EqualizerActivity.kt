package com.unicorn.player

import android.app.AlertDialog
import android.os.Bundle
import android.util.Log
import android.util.TypedValue
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.bullhead.equalizer.AudioEffectManager
import com.bullhead.equalizer.EqualizerFragment
import com.bullhead.equalizer.Settings
import com.google.gson.Gson
import com.unicorn.player.EqualizerActivity.Companion.MAX_CONFIG_COUNT
import com.unicorn.player.adapter.MultiSelectableEqConfigAdapter
import com.unicorn.player.adapter.SelectableEqConfigAdapter
import com.unicorn.player.databinding.ActivityEqualizerBinding
import com.unicorn.player.databinding.DialogEqCleanupConfigsBinding
import com.unicorn.player.databinding.DialogEqExportFilenameBinding
import com.unicorn.player.databinding.DialogEqImportConfigBinding
import com.unicorn.player.databinding.EqualizerOptionsBinding
import com.unicorn.player.equalizer.EqualizerConfig
import com.unicorn.player.manager.MusicManager
import com.unicorn.player.service.MusicService
import com.unicorn.player.util.EqualizerConfigManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class EqualizerActivity : BaseActivity(), MusicManager.ConnectionCallback {

    private lateinit var binding: ActivityEqualizerBinding
    private var musicService: MusicService? = null

    /** SAF 授权完成后的待执行操作；NONE 表示没有挂起的操作。 */
    private var pendingAction: PendingAction = PendingAction.NONE

    private enum class PendingAction { NONE, EXPORT, IMPORT }

    /** SAF 目录选择器启动器：授权后持久化树 URI -> 确保目录存在 -> 执行挂起操作。 */
    private val openDocumentTreeLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { treeUri ->
            if (treeUri != null) {
                EqualizerConfigManager.saveTreeUri(this, treeUri)
                lifecycleScope.launch(Dispatchers.IO) {
                    val dirReady = EqualizerConfigManager.ensureSaveDirExists(this@EqualizerActivity)
                    withContext(Dispatchers.Main) {
                        if (dirReady) {
                            when (pendingAction) {
                                PendingAction.EXPORT -> startExportFlow()
                                PendingAction.IMPORT -> showImportConfigDialog()
                                PendingAction.NONE -> {}
                            }
                        } else {
                            Toast.makeText(
                                this@EqualizerActivity,
                                "授权目录创建失败",
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                        pendingAction = PendingAction.NONE
                    }
                }
            } else {
                // 用户取消授权
                pendingAction = PendingAction.NONE
            }
        }

    companion object {
        private const val TAG = "EqualizerActivity"

        /** 导出目录允许保存的配置文件数量上限，达到后需先清理。 */
        private const val MAX_CONFIG_COUNT = 10
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityEqualizerBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupTitleBar()
        bindMusicService()
    }

    override fun onDestroy() {
        super.onDestroy()
        // 注销服务连接回调并解绑
        MusicManager.unregisterConnectionCallback(this)
        MusicManager.unbind(this)
    }

    override fun onStart() {
        super.onStart()

        if (musicService != null) {
            val sessionId = musicService?.getAudioSessionId() ?: 0

            if (sessionId == 0) {
                Log.e(TAG, "Invalid audio session ID: 0")
                return
            }

            setupEqualizerFragment()

            if (Settings.isEqualizerEnabled) {
                AudioEffectManager.enableEffects(this)
            } else {
                AudioEffectManager.disableEffects()
            }
        }
    }

    private fun setupTitleBar() {
        binding.titleBar.setOnBackClickListener {
            val equalizerFragment =
                supportFragmentManager.findFragmentByTag("f_eq") as? EqualizerFragment
            if (equalizerFragment == null) {
                finish()
                return@setOnBackClickListener
            }
            equalizerFragment.showSaveEqDialog(true, null)
        }

        val optionsBinding = EqualizerOptionsBinding.inflate(layoutInflater)
        binding.titleBar.addViewToRight(optionsBinding.root)

        // 均衡器配置：导入 / 导出
        optionsBinding.ivImport.setOnClickListener { onImportClicked() }
        optionsBinding.ivExport.setOnClickListener { onExportClicked() }
    }

    // ==================== 均衡器配置导入/导出 ====================

    private fun onExportClicked() {
        ensurePermissionAndDir(PendingAction.EXPORT)
    }

    private fun onImportClicked() {
        ensurePermissionAndDir(PendingAction.IMPORT)
    }

    /**
     * 检查 SAF 权限 + 确保目录存在。
     * 已授权：异步确保目录存在后执行 [action]；
     * 未授权：启动 SAF 选择器，授权后回到 [openDocumentTreeLauncher] 回调执行 [action]。
     */
    private fun ensurePermissionAndDir(action: PendingAction) {
        if (!EqualizerConfigManager.hasPermission(this)) {
            pendingAction = action
            openDocumentTreeLauncher.launch(EqualizerConfigManager.getInitialUri())
            return
        }
        // 已授权：异步确保目录存在
        lifecycleScope.launch(Dispatchers.IO) {
            val ready = EqualizerConfigManager.ensureSaveDirExists(this@EqualizerActivity)
            withContext(Dispatchers.Main) {
                if (ready) {
                    when (action) {
                        PendingAction.EXPORT -> startExportFlow()
                        PendingAction.IMPORT -> showImportConfigDialog()
                        PendingAction.NONE -> {}
                    }
                } else {
                    Toast.makeText(this@EqualizerActivity, "目录创建失败", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    /**
     * 导出流程入口：实时读取已存在的配置文件数量。
     * - 达到 [MAX_CONFIG_COUNT]：先弹出清理弹窗，删除后不做后续操作，需重新点击导出；
     * - 未达上限：进入文件名输入弹窗（文件列表同时用于重名检测）。
     */
    private fun startExportFlow() {
        lifecycleScope.launch(Dispatchers.IO) {
            val existingFiles = EqualizerConfigManager.listConfigFiles(this@EqualizerActivity)
            withContext(Dispatchers.Main) {
                if (existingFiles.size >= MAX_CONFIG_COUNT) {
                    showCleanupConfigsDialog(existingFiles)
                } else {
                    showExportFilenameDialogInternal(existingFiles)
                }
            }
        }
    }

    /** 显示配置清理弹窗（多选删除）。删除后刷新列表，不自动继续导出流程。 */
    private fun showCleanupConfigsDialog(initialFiles: List<String>) {
        val dialogBinding = DialogEqCleanupConfigsBinding.inflate(layoutInflater)
        val dialog = AlertDialog.Builder(this)
            .setView(dialogBinding.root)
            .setCancelable(true)
            .create()
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)

        val adapter = MultiSelectableEqConfigAdapter { selectedCount ->
            dialogBinding.btnDelete.isEnabled = selectedCount > 0
            dialogBinding.btnDelete.text = if (selectedCount > 0) "删除($selectedCount)" else "删除"
        }
        dialogBinding.recyclerView.layoutManager = LinearLayoutManager(this)
        dialogBinding.recyclerView.adapter = adapter

        /** 渲染列表：空列表时显示空态提示。 */
        fun render(files: List<String>) {
            if (files.isEmpty()) {
                dialogBinding.recyclerView.visibility = View.GONE
                dialogBinding.tvEmpty.visibility = View.VISIBLE
            } else {
                dialogBinding.tvEmpty.visibility = View.GONE
                dialogBinding.recyclerView.visibility = View.VISIBLE
            }
            adapter.submitList(files)
            dialogBinding.btnDelete.isEnabled = false
            dialogBinding.btnDelete.text = "删除"
        }
        render(initialFiles)

        dialogBinding.btnDelete.setOnClickListener {
            val toDelete = adapter.getSelectedFiles()
            if (toDelete.isEmpty()) {
                return@setOnClickListener
            }
            lifecycleScope.launch(Dispatchers.IO) {
                var successCount = 0
                toDelete.forEach { name ->
                    if (EqualizerConfigManager.deleteConfig(this@EqualizerActivity, name)) {
                        successCount++
                    }
                }
                withContext(Dispatchers.Main) {
                    // 删除完成后关闭弹窗，不自动进入导出，用户需重新点击导出
                    dialog.dismiss()
                    Toast.makeText(
                        this@EqualizerActivity,
                        "已删除 $successCount 个配置",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
        }
        dialogBinding.btnCancel.setOnClickListener { dialog.dismiss() }
        dialog.show()
    }

    private fun showExportFilenameDialogInternal(existingFiles: List<String>) {
        val dialogBinding = DialogEqExportFilenameBinding.inflate(layoutInflater)
        val dialog = AlertDialog.Builder(this)
            .setView(dialogBinding.root)
            .setCancelable(true)
            .create()
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)

        // 归一化为小写文件名集合，作为重名判定的单一事实来源
        val existingNames = existingFiles.map { it.lowercase() }.toHashSet()

        dialogBinding.btnConfirm.setOnClickListener {
            // 先去除首尾空白并回写到输入框，保证后续校验/保存基于同一个 trim 后的值
            val input = dialogBinding.etFileName.text?.toString()?.trim().orEmpty()
            if (input != dialogBinding.etFileName.text?.toString()) {
                dialogBinding.etFileName.setText(input)
                dialogBinding.etFileName.setSelection(input.length)
            }
            if (input.isEmpty()) {
                Toast.makeText(this, "请输入文件名", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val fileName = EqualizerConfigManager.normalizeFileName(input)
            // 勾选"覆盖同名配置"时跳过重名检查直接覆盖；否则走原重名检查逻辑
            if (!dialogBinding.cbOverwrite.isChecked && existingNames.contains(fileName.lowercase())) {
                // 重名时在输入框下方显示红色错误提示，用户可修改后再次点击"确定"
                dialogBinding.tilFileName.error = "已存在同名配置文件，请更换名称"
                return@setOnClickListener
            }
            dialog.dismiss()
            performExport(fileName)
        }
        dialogBinding.btnCancel.setOnClickListener { dialog.dismiss() }
        dialog.show()
    }

    /** 执行导出：读取当前 Settings -> 序列化为 JSON -> 写入文件。 */
    private fun performExport(fileName: String) {
        val config = EqualizerConfig(
            bandLevels = Settings.seekbarpos.toList(),
            bassStrength = Settings.bassStrength.toInt(),
            reverbPreset = Settings.reverbPreset.toInt()
        )
        val json = try {
            Gson().toJson(config)
        } catch (e: Exception) {
            Log.e(TAG, "序列化配置失败", e)
            Toast.makeText(this, "序列化失败", Toast.LENGTH_SHORT).show()
            return
        }
        lifecycleScope.launch(Dispatchers.IO) {
            val ok = EqualizerConfigManager.writeConfig(this@EqualizerActivity, fileName, json)
            withContext(Dispatchers.Main) {
                Toast.makeText(
                    this@EqualizerActivity,
                    if (ok) "已导出到 Documents/Unicorn/Equalizer/$fileName" else "写入失败",
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
    }

    /** 显示导入弹窗：先异步读取目录下的 .json 文件列表。 */
    private fun showImportConfigDialog() {
        lifecycleScope.launch(Dispatchers.IO) {
            val files = EqualizerConfigManager.listConfigFiles(this@EqualizerActivity)
            withContext(Dispatchers.Main) {
                showImportDialogInternal(files)
            }
        }
    }

    private fun showImportDialogInternal(files: List<String>) {
        val dialogBinding = DialogEqImportConfigBinding.inflate(layoutInflater)
        val dialog = AlertDialog.Builder(this)
            .setView(dialogBinding.root)
            .setCancelable(true)
            .create()
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)

        if (files.isEmpty()) {
            dialogBinding.recyclerView.visibility = View.GONE
            dialogBinding.tvEmpty.visibility = View.VISIBLE
            dialogBinding.btnConfirm.isEnabled = false
        } else {
            dialogBinding.tvEmpty.visibility = View.GONE
            dialogBinding.recyclerView.visibility = View.VISIBLE
            val adapter = SelectableEqConfigAdapter { selected ->
                dialogBinding.btnConfirm.isEnabled = selected != null
            }
            dialogBinding.recyclerView.layoutManager = LinearLayoutManager(this)
            dialogBinding.recyclerView.adapter = adapter
            adapter.submitList(files)
        }

        dialogBinding.btnConfirm.setOnClickListener {
            val adapter = dialogBinding.recyclerView.adapter as? SelectableEqConfigAdapter
            val selected = adapter?.selectedFileName
            if (selected.isNullOrEmpty()) {
                Toast.makeText(this, "请选择一个配置", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            dialog.dismiss()
            performImport(selected)
        }
        dialogBinding.btnCancel.setOnClickListener { dialog.dismiss() }
        dialog.show()
    }

    /** 执行导入：读取 JSON -> 反序列化 -> 调用 EqualizerFragment.applyImportedConfig。 */
    private fun performImport(fileName: String) {
        lifecycleScope.launch(Dispatchers.IO) {
            val content = EqualizerConfigManager.readConfig(this@EqualizerActivity, fileName)
            if (content.isNullOrEmpty()) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@EqualizerActivity, "读取失败", Toast.LENGTH_SHORT).show()
                }
                return@launch
            }
            val config = try {
                Gson().fromJson(content, EqualizerConfig::class.java)
            } catch (e: Exception) {
                Log.e(TAG, "解析配置失败", e)
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@EqualizerActivity, "配置文件格式错误", Toast.LENGTH_SHORT).show()
                }
                return@launch
            }
            if (config.bandLevels.size < 5) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@EqualizerActivity, "频段数据不完整", Toast.LENGTH_SHORT).show()
                }
                return@launch
            }
            val bandArr = IntArray(5) { i -> config.bandLevels[i] }
            val bass = config.bassStrength.toShort()
            val reverb = config.reverbPreset.toShort()
            withContext(Dispatchers.Main) {
                val fragment =
                    supportFragmentManager.findFragmentByTag("f_eq") as? EqualizerFragment
                if (fragment == null) {
                    Toast.makeText(this@EqualizerActivity, "均衡器未就绪", Toast.LENGTH_SHORT).show()
                    return@withContext
                }
                val applied = fragment.applyImportedConfig(bandArr, bass, reverb)
                Toast.makeText(
                    this@EqualizerActivity,
                    if (applied) "已导入并应用到当前音效"
                    else "已导入（开关未打开，下次开启后生效）",
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
    }

    // ==================== MusicManager.ConnectionCallback 实现 ====================

    private fun bindMusicService() {
        MusicManager.registerConnectionCallback(this)
        val alreadyConnected = MusicManager.bind(this)
        musicService = MusicManager.getService()
        if (alreadyConnected && musicService != null) {
            onServiceConnected(musicService)
        }
    }

    override fun onServiceConnected(service: MusicService?) {
        musicService = service ?: return
        // 服务连接后立即设置均衡器
        val sessionId = musicService?.getAudioSessionId() ?: 0
        if (sessionId == 0) {
            Log.e(TAG, "Invalid audio session ID: 0")
            return
        }
        setupEqualizerFragment()
        if (Settings.isEqualizerEnabled) {
            AudioEffectManager.enableEffects(this)
        } else {
            AudioEffectManager.disableEffects()
        }
    }

    override fun onServiceDisconnected() {
        musicService = null
    }

    private fun setupEqualizerFragment() {
        val sessionId = musicService?.getAudioSessionId() ?: 0
        if (sessionId == 0) {
            finish()
            return
        }

        var equalizerFragment =
            supportFragmentManager.findFragmentByTag("f_eq") as? EqualizerFragment
        if (equalizerFragment != null && equalizerFragment.isVisible) {
            return
        }

        val typedValue = TypedValue()
        theme.resolveAttribute(
            com.google.android.material.R.attr.colorPrimary, typedValue, true
        )
        val accentColor = if (typedValue.resourceId != 0) {
            ContextCompat.getColor(this, typedValue.resourceId)
        } else {
            typedValue.data
        }

        equalizerFragment = EqualizerFragment.newBuilder()
            .setAccentColor(accentColor)
            .setAudioSessionId(sessionId)
            .build()

        supportFragmentManager.beginTransaction()
            .replace(R.id.eqFrame, equalizerFragment, "f_eq")
            .commit()
    }

    override fun onResume() {
        super.onResume()
        if (musicService != null) {
            val sessionId = musicService?.getAudioSessionId() ?: 0
            if (sessionId != 0) {
                val existingFragment = supportFragmentManager.findFragmentById(R.id.eqFrame)
                if (existingFragment == null || !existingFragment.isVisible) {
                    setupEqualizerFragment()
                }
            }
        }
    }

    override fun onPause() {
        super.onPause()
        musicService?.savePlaybackState()
    }
}
