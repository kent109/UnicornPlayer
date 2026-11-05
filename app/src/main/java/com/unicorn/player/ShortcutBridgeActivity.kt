package com.unicorn.player

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.unicorn.player.manager.MusicManager
import com.unicorn.player.util.ShortcutUtil

/**
 * 桌面长按图标快捷方式的静默执行入口。
 *
 * Android ShortcutManager 的硬限制：动态快捷方式的 intent 必须指向 Activity，
 * 不能是 BroadcastReceiver 或 Service。因此这里用一个透明、无标题、排除最近任务的
 * Activity 作为桥梁 — onCreate 里完成切换后立即 finish()，用户不会看到任何界面。
 *
 * 关键设计：
 *   - 和 MainActivity 共用同一个 taskAffinity（默认包名），不会把主 task 推到后台
 *     （独立 task 在某些 ROM 上会导致后台播放被暂停）
 *   - finish() 之前先 startActivity Launcher，确保用户回到桌面而不是看到栈底 Activity
 *     （如果不做，Bridge Activity finish 后系统会把同 task 里底层的 MainActivity 显示出来）
 *   - 用 overridePendingTransition(0, 0) 消除所有过渡动画，完全无感
 */
class ShortcutBridgeActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (intent?.action == ShortcutUtil.ACTION_TOGGLE_EQUALIZER) {
            val service = MusicManager.getService()
            val enabled = ShortcutUtil.toggleEqualizer(this, service)
            // 切换后立即更新快捷方式文本（"打开均衡器" ↔ "关闭均衡器"）
            ShortcutUtil.updateEqualizerShortcut(this, enabled)
            Toast.makeText(
                this,
                if (enabled) "已打开均衡器" else "已关闭均衡器",
                Toast.LENGTH_SHORT
            ).show()
        }

        // finish 之前先把用户送回桌面，避免 Bridge Activity finish 后
        // 系统把同 task 栈底的 MainActivity 拉出来显示
        val home = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        home.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        startActivity(home)

        finish()
        // 消除所有过渡动画，让整个过程完全无感
        @Suppress("DEPRECATION")
        overridePendingTransition(0, 0)
    }
}
