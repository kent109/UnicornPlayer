package com.unicorn.player.util

import android.content.Context
import com.unicorn.player.R

/**
 * 权限说明数据模型
 * 每个权限包含：完整名称、系统权限说明、本应用的使用场景
 */
data class PermissionInfo(
    val name: String,
    val description: String,
    val scene: String
) {
    companion object {
        fun getAllPermissions(context: Context): List<PermissionInfo> {
            val strings = context.resources
            return listOf(
                PermissionInfo(
                    name = "android.permission.INTERNET",
                    description = strings.getString(R.string.perm_desc_internet),
                    scene = strings.getString(R.string.perm_scene_internet)
                ),
                PermissionInfo(
                    name = "android.permission.READ_MEDIA_AUDIO",
                    description = strings.getString(R.string.perm_desc_read_media_audio),
                    scene = strings.getString(R.string.perm_scene_read_media_audio)
                ),
                PermissionInfo(
                    name = "android.permission.WAKE_LOCK",
                    description = strings.getString(R.string.perm_desc_wake_lock),
                    scene = strings.getString(R.string.perm_scene_wake_lock)
                ),
                PermissionInfo(
                    name = "android.permission.FOREGROUND_SERVICE",
                    description = strings.getString(R.string.perm_desc_fg_service),
                    scene = strings.getString(R.string.perm_scene_fg_service)
                ),
                PermissionInfo(
                    name = "android.permission.FOREGROUND_SERVICE_MEDIA_PLAYBACK",
                    description = strings.getString(R.string.perm_desc_fg_media),
                    scene = strings.getString(R.string.perm_scene_fg_media)
                ),
                PermissionInfo(
                    name = "android.permission.POST_NOTIFICATIONS",
                    description = strings.getString(R.string.perm_desc_post_notifications),
                    scene = strings.getString(R.string.perm_scene_post_notifications)
                ),
                PermissionInfo(
                    name = "android.permission.BLUETOOTH",
                    description = strings.getString(R.string.perm_desc_bluetooth),
                    scene = strings.getString(R.string.perm_scene_bluetooth)
                ),
                PermissionInfo(
                    name = "android.permission.BLUETOOTH_ADMIN",
                    description = strings.getString(R.string.perm_desc_bluetooth_admin),
                    scene = strings.getString(R.string.perm_scene_bluetooth_admin)
                ),
                PermissionInfo(
                    name = "android.permission.BLUETOOTH_CONNECT",
                    description = strings.getString(R.string.perm_desc_bluetooth_connect),
                    scene = strings.getString(R.string.perm_scene_bluetooth_connect)
                ),
                PermissionInfo(
                    name = "android.permission.REQUEST_INSTALL_PACKAGES",
                    description = strings.getString(R.string.perm_desc_install_packages),
                    scene = strings.getString(R.string.perm_scene_install_packages)
                )
            )
        }
    }
}
