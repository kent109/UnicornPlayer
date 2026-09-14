package com.unicorn.player.manager

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import com.unicorn.player.service.MusicService
import java.lang.ref.WeakReference

/**
 * MusicManager - MusicService全局管理器
 * 负责MusicService的全局绑定、解绑和实例获取。
 *
 * 绑定是异步的：第一次调用 bind() 会触发 startService + bindService，
 * onServiceConnected 回调中设置 musicService 实例。
 * 后续 Activity 调用 bind() 时，如果服务已连接，直接返回已有实例。
 *
 * Activity 应在 onCreate 中调用 bind()，在 onDestroy 中调用 unbind()。
 * 绑定后通过 getService() 获取 MusicService 实例（可能为 null，如果异步绑定尚未完成）。
 *
 * 关键设计：
 * - 使用真正的 WeakReference 存储 Activity 引用，避免内存泄漏
 * - 引用计数：所有 Activity 都 unbind 后才真正解绑服务
 * - onServiceConnected 回调通知所有活跃的 Activity
 */
object MusicManager {

    private const val TAG = "MusicManager"

    private var musicService: MusicService? = null
    private var isBound = false
    private var isServiceStarted = false

    /** 活跃的 Activity 引用集合（弱引用，避免内存泄漏） */
    private val activityReferences = mutableSetOf<WeakReference<Any>>()

    /** 服务连接回调集合：Activity 可注册回调以在服务连接/断开时收到通知 */
    private val connectionCallbacks = mutableSetOf<WeakReference<ConnectionCallback>>()

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as MusicService.MusicBinder
            musicService = binder.getService()
            isBound = true
            notifyServiceConnected()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            isBound = false
            musicService = null
            notifyServiceDisconnected()
        }
    }

    /**
     * 绑定 MusicService
     * @param context 用于 startService/bindService 的 Context（通常是 Activity）
     * @return 是否立即获取到已连接的 MusicService 实例（如果服务之前已绑定则返回 true）
     */
    fun bind(context: Context): Boolean {
        activityReferences.add(WeakReference(context))

        if (!isServiceStarted) {
            val intent = Intent(context, MusicService::class.java)
            context.startService(intent)
            isServiceStarted = true
        }

        if (!isBound && musicService == null) {
            val intent = Intent(context, MusicService::class.java)
            context.bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
        }

        return musicService != null
    }

    /**
     * 解绑 MusicService
     * @param context 用于 unbindService 的 Context（通常是 Activity）
     */
    fun unbind(context: Context) {
        // 从弱引用集合中移除该 Context
        activityReferences.removeAll { it.get() === context }

        // 清理已被 GC 回收的弱引用
        activityReferences.removeIf { it.get() == null }
        connectionCallbacks.removeIf { it.get() == null }

        // 如果没有活跃的 Activity 引用，真正解绑服务
        if (activityReferences.isEmpty()) {
            if (isBound) {
                try {
                    context.unbindService(serviceConnection)
                } catch (e: IllegalArgumentException) {
                    // 服务未绑定或已解绑，忽略
                }
                isBound = false
            }
            musicService = null
            isServiceStarted = false
        }
    }

    /**
     * 获取 MusicService 实例
     * @return 当前已连接的 MusicService 实例，如果尚未连接则返回 null
     */
    fun getService(): MusicService? = musicService

    /**
     * 注册服务连接回调
     * Activity 可在 bind() 之后注册回调，以便在异步绑定完成时收到通知。
     * 回调使用弱引用持有，不会阻止 Activity 被 GC。
     *
     * @param callback 连接回调
     */
    fun registerConnectionCallback(callback: ConnectionCallback) {
        connectionCallbacks.add(WeakReference(callback))
    }

    /**
     * 注销服务连接回调
     * @param callback 要注销的回调
     */
    fun unregisterConnectionCallback(callback: ConnectionCallback) {
        connectionCallbacks.removeAll { it.get() === callback || it.get() == null }
    }

    /**
     * 强制清理所有状态（用于进程恢复场景）
     */
    fun forceCleanup() {
        activityReferences.clear()
        connectionCallbacks.clear()
        isBound = false
        isServiceStarted = false
        musicService = null
    }

    // ==================== 内部方法 ====================

    private fun notifyServiceConnected() {
        cleanupInvalidReferences()
        activityReferences.forEach { ref ->
            ref.get()?.let { _ ->
                connectionCallbacks.forEach { cbRef ->
                    cbRef.get()?.onServiceConnected(musicService)
                }
            }
        }
        cleanupInvalidReferences()
    }

    private fun notifyServiceDisconnected() {
        connectionCallbacks.forEach { cbRef ->
            cbRef.get()?.onServiceDisconnected()
        }
        cleanupInvalidReferences()
    }

    private fun cleanupInvalidReferences() {
        activityReferences.removeIf { it.get() == null }
        connectionCallbacks.removeIf { it.get() == null }
    }

    /**
     * 服务连接回调接口
     * Activity 实现此接口并在 bind() 后注册，以便在异步绑定完成时收到通知。
     */
    interface ConnectionCallback {
        fun onServiceConnected(service: MusicService?)
        fun onServiceDisconnected()
    }
}
