package com.unicorn.player.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.unicorn.player.util.LogWriter

/**
 * Service 层的两个 BroadcastReceiver：通知栏按钮 + 蓝牙音频设备状态。
 *
 * 所有 Service 内部依赖通过构造闭包注入——零 MusicService 字段直接访问。
 * Service 保留 registerReceiver / unregisterReceiver / ACTION_* 常量。
 */
internal object ServiceBroadcastReceivers {

    /**
     * 通知栏按钮点击 → 播放控制。
     *
     * Service 注册时的 filter：ACTION_PLAY / ACTION_PAUSE / ACTION_NEXT / ACTION_PREVIOUS。
     */
    fun createNotificationButtonReceiver(
        tag: String,
        actionPlay: String,
        actionPause: String,
        actionNext: String,
        actionPrevious: String,
        getIsPlaying: () -> Boolean?,
        getCurrentSongTitle: () -> String?,
        setIsPlaying: (Boolean) -> Unit,
        doPlay: () -> Unit,
        doPause: () -> Unit,
        playNext: () -> Unit,
        playPrevious: () -> Unit,
        updateNotification: () -> Unit,
    ): BroadcastReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val action = intent.action
            LogWriter.writeDiag(
                tag,
                "notificationButton: action=$action, isPlaying=${getIsPlaying()}, song=${getCurrentSongTitle()}"
            )

            when (action) {
                actionPlay -> {
                    setIsPlaying(true)
                    doPlay()
                }

                actionPause -> {
                    setIsPlaying(false)
                    doPause()
                }

                actionNext -> playNext()

                actionPrevious -> playPrevious()
            }

            // 立即更新通知
            updateNotification()
            Log.d(tag, "After button click: isPlaying: ${getIsPlaying()}")
        }
    }

    /**
     * 蓝牙音频设备状态变化 → 自动暂停（耳机拔出由 ExoPlayer setHandleAudioBecomingNoisy 接管）。
     *
     * Service 注册时的 filter：
     *   BluetoothDevice.ACTION_ACL_DISCONNECTED
     *   BluetoothAdapter.ACTION_CONNECTION_STATE_CHANGED
     *   BluetoothA2dp.ACTION_CONNECTION_STATE_CHANGED
     */
    fun createAudioDeviceReceiver(
        tag: String,
        getIsPlaying: () -> Boolean?,
        doPause: () -> Unit,
        handleBluetoothDeviceDisconnect: (Intent, String) -> Unit,
    ): BroadcastReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val action = intent.action
            Log.d(tag, "Audio device event: $action")

            when (action) {
                // 蓝牙设备连接状态变化
                android.bluetooth.BluetoothDevice.ACTION_ACL_DISCONNECTED -> {
                    handleBluetoothDeviceDisconnect(intent, "disconnected")
                }

                // 蓝牙音频连接状态变化
                android.bluetooth.BluetoothAdapter.ACTION_CONNECTION_STATE_CHANGED -> {
                    val state = intent.getIntExtra(
                        android.bluetooth.BluetoothAdapter.EXTRA_CONNECTION_STATE,
                        android.bluetooth.BluetoothAdapter.STATE_DISCONNECTED
                    )
                    Log.d(tag, "Bluetooth connection state changed: $state")
                    if (state == android.bluetooth.BluetoothAdapter.STATE_DISCONNECTED) {
                        // 蓝牙音频断开，暂停播放
                        if (getIsPlaying() == true) {
                            doPause()
                        }
                    }
                }

                // A2DP音频流连接状态变化
                android.bluetooth.BluetoothA2dp.ACTION_CONNECTION_STATE_CHANGED -> {
                    val state = intent.getIntExtra(
                        android.bluetooth.BluetoothAdapter.EXTRA_CONNECTION_STATE,
                        android.bluetooth.BluetoothA2dp.STATE_DISCONNECTED
                    )
                    Log.d(tag, "A2DP audio stream connection state changed: $state")
                    if (state == android.bluetooth.BluetoothA2dp.STATE_DISCONNECTED) {
                        // A2DP音频流断开，暂停播放
                        if (getIsPlaying() == true) {
                            doPause()
                        }
                    }
                }

                // 蓝牙设备配对状态变化（配对解除时断开播放）
                android.bluetooth.BluetoothDevice.ACTION_BOND_STATE_CHANGED -> {
                    val state = intent.getIntExtra(
                        android.bluetooth.BluetoothDevice.EXTRA_BOND_STATE,
                        android.bluetooth.BluetoothDevice.BOND_NONE
                    )
                    if (state == android.bluetooth.BluetoothDevice.BOND_NONE) {
                        handleBluetoothDeviceDisconnect(intent, "bond state changed")
                    }
                }
            }
        }
    }
}
