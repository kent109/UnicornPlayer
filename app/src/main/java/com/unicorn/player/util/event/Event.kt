package com.unicorn.player.util.event

/**
 * 一次性事件包装类，避免 LiveData 旋转/重建时重复触发。
 * 来自 Google 官方单通道事件模式（LiveData with additional content）。
 *
 * MusicService 的 _requestSongList、_fileChanged 等用它做"单次通知"。
 */
open class Event<out T>(private val content: T) {
    private var hasBeenHandled = false

    /** 返回未消费过的事件内容，消费后返回 null */
    fun getContentIfNotHandled(): T? {
        return if (hasBeenHandled) {
            null
        } else {
            hasBeenHandled = true
            content
        }
    }
}
