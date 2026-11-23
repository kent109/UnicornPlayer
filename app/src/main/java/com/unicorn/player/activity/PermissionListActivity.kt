package com.unicorn.player.activity

import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.cardview.widget.CardView
import com.unicorn.player.R
import com.unicorn.player.databinding.ActivityPermissionListBinding
import com.unicorn.player.util.PermissionInfo

/**
 * 权限说明页面
 * 展示应用声明的权限列表（名称 + 说明 + 使用场景）
 */
class PermissionListActivity : BaseActivity() {

    private lateinit var binding: ActivityPermissionListBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityPermissionListBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupPermissionList()
    }

    private fun setupPermissionList() {
        val container = binding.permissionContainer
        val permissions = PermissionInfo.getAllPermissions(this)
        val density = resources.displayMetrics.density

        // 顶部说明文字
        val introText = TextView(this).apply {
            text = getString(R.string.permission_list_intro)
            textSize = 15f
            gravity = Gravity.CENTER
            setTextColor(getColor(R.color.onSurfaceVariant))
            setPadding(
                (4 * density).toInt(),
                (8 * density).toInt(),
                (4 * density).toInt(),
                (12 * density).toInt()
            )
        }
        container.addView(introText)

        // 权限卡片列表
        permissions.forEach { perm ->
            val card = createPermissionCard(perm, density)
            container.addView(card)
        }
    }

    private fun createPermissionCard(perm: PermissionInfo, density: Float): CardView {
        val containerPadding = (12 * density).toInt()
        val itemSpacing = (0 * density).toInt()

        // 外层 CardView：圆角 + 阴影
        val card = CardView(this).apply {
            setCardBackgroundColor(getColor(R.color.settings_item_bg))
            radius = 12 * density
            cardElevation = 2 * density
            useCompatPadding = true
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = itemSpacing
            }
        }

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(containerPadding, containerPadding, containerPadding, containerPadding)
        }
        card.addView(content)

        // 权限名称（完整 android.permission.XXX）
        val tvName = TextView(this).apply {
            text = perm.name
            textSize = 13f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(getColor(R.color.primary))
        }
        content.addView(tvName)

        // 分割线
        val dividerHeight = (0.5 * density).toInt()
        val divider = View(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dividerHeight
            ).apply {
                topMargin = (6 * density).toInt()
                bottomMargin = (6 * density).toInt()
            }
            setBackgroundColor(getColor(R.color.divider_color))
        }
        content.addView(divider)

        // 权限说明
        val tvDesc = TextView(this).apply {
            text = perm.description
            textSize = 14f
            setTextColor(getColor(R.color.onSurface))
        }
        content.addView(tvDesc)

        // 使用场景标签
        val sceneLabel = TextView(this).apply {
            text = getString(R.string.permission_scene_label)
            textSize = 14f
            setTextColor(getColor(R.color.onSurfaceVariant))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = (8 * density).toInt() }
        }
        content.addView(sceneLabel)

        // 场景内容
        val tvScene = TextView(this).apply {
            text = perm.scene
            textSize = 14f
            setTextColor(getColor(R.color.onSurface))
        }
        content.addView(tvScene)

        return card
    }
}
