package com.unicorn.player.ui

/**
 * 歌单刷新通知器（进程内跨页面同步）
 *
 * 当从歌曲/歌手/专辑页面添加歌曲到歌单后，通过此对象通知 PlaylistFragment 刷新列表，
 * 确保歌单歌曲数量显示与数据库一致。
 */
object PlaylistRefresher {

    /** 刷新监听器集合 */
    private val listeners: MutableList<() -> Unit> = mutableListOf()

    /**
     * 注册刷新监听器（PlaylistFragment 在 onStart 时注册）
     */
    fun addListener(listener: () -> Unit) {
        if (!listeners.contains(listener)) {
            listeners.add(listener)
        }
    }

    /**
     * 移除刷新监听器（PlaylistFragment 在 onStop 时移除）
     */
    fun removeListener(listener: () -> Unit) {
        listeners.remove(listener)
    }

    /**
     * 通知所有监听器刷新歌单列表
     */
    fun notifyPlaylistsChanged() {
        // 复制一份避免 ConcurrentModificationException
        val snapshot = listeners.toList()
        snapshot.forEach { it.invoke() }
    }
}
