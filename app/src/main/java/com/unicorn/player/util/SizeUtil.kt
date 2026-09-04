package com.unicorn.player.util

import java.util.Locale

class SizeUtil {

    companion object {
        fun formatSize(totalSize: Long): String {
            return when {
                totalSize >= 1024 * 1024 * 1024 -> String.format(
                    Locale.getDefault(),
                    "%.2f GB",
                    totalSize / (1024.0 * 1024.0 * 1024.0)
                )

                totalSize >= 1024 * 1024 -> String.format(
                    Locale.getDefault(),
                    "%.2f MB",
                    totalSize / (1024.0 * 1024.0)
                )

                totalSize >= 1024 -> String.format(
                    Locale.getDefault(),
                    "%.2f KB",
                    totalSize / 1024.0
                )

                else -> "$totalSize B"
            }
        }
    }
}