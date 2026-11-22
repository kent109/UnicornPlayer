package com.unicorn.player.util.compat

import android.content.Intent
import android.os.Build
import android.os.Parcelable

/**
 * Intent API 兼容层：抹平不同 Android 版本的 API 差异。
 * 集中在一处便于升级后统一替换官方 API。
 */
object IntentCompat {

    /**
     * Intent.getParcelableExtra 的 API 33+ Class 重载兼容。
     * 旧版直接 getParcelableExtra(key) as? T，新版必须传 Class<T> 避免类型不推断。
     */
    @Suppress("DEPRECATION")
    fun <T : Parcelable> getParcelableExtraCompat(
        intent: Intent,
        key: String,
        clazz: Class<T>,
    ): T? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(key, clazz)
        } else {
            intent.getParcelableExtra(key) as? T
        }
    }
}
