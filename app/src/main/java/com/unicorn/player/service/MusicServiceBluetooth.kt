package com.unicorn.player.service

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.util.Log
import androidx.core.content.ContextCompat
import com.unicorn.player.util.LogWriter
import com.unicorn.player.util.compat.IntentCompat

/**
 * MusicService 蓝牙相关能力：
 *   - isBluetoothA2dpConnected：当前是否通过 A2DP 蓝牙音频输出
 *   - handleBluetoothDeviceDisconnect：统一处理 ACL_DISCONNECTED / BOND_STATE_CHANGED
 *
 * 所有 Service 内部依赖通过构造闭包注入——零 MusicService 字段直接访问。
 */
internal class MusicServiceBluetooth(
    private val context: Context,
    private val tag: String,
    private val getIsPlaying: () -> Boolean?,
    private val doPause: () -> Unit,
) {

    /** 检查当前是否通过A2DP蓝牙音频输出（替代废弃的 isBluetoothA2dpOn） */
    fun isBluetoothA2dpConnected(): Boolean {
        return try {
            // getDefaultAdapter() 在 API 31 废弃，改用 BluetoothManager.adapter（API 23+ 可用）
            val bluetoothAdapter =
                (context.getSystemService(Context.BLUETOOTH_SERVICE) as? android.bluetooth.BluetoothManager)?.adapter
            if (bluetoothAdapter != null && bluetoothAdapter.isEnabled) {
                val a2dpState = bluetoothAdapter.getProfileConnectionState(
                    android.bluetooth.BluetoothProfile.A2DP
                )
                a2dpState == android.bluetooth.BluetoothProfile.STATE_CONNECTED
            } else {
                false
            }
        } catch (e: SecurityException) {
            LogWriter.writeError(tag, "BLUETOOTH_CONNECT permission denied for A2DP check", e)
            false
        } catch (e: Exception) {
            LogWriter.writeError(tag, "Error checking A2DP connection state", e)
            false
        }
    }

    /** 统一处理蓝牙设备断开/未配对事件（ACL_DISCONNECTED 和 BOND_STATE_CHANGED） */
    fun handleBluetoothDeviceDisconnect(intent: Intent, eventType: String) {
        val device = IntentCompat.getParcelableExtraCompat(
            intent,
            android.bluetooth.BluetoothDevice.EXTRA_DEVICE,
            android.bluetooth.BluetoothDevice::class.java
        )
        // 检查 BLUETOOTH_CONNECT 权限后再获取设备名称
        val deviceName = if (ContextCompat.checkSelfPermission(
                context,
                android.Manifest.permission.BLUETOOTH_CONNECT
            ) == PackageManager.PERMISSION_GRANTED
        ) {
            device?.name
        } else {
            "unknown"
        }
        Log.d(tag, "Bluetooth device $eventType: $deviceName, type: ${device?.type}")

        // 只有当前通过蓝牙音频输出时才可能暂停
        if (device == null || !isBluetoothA2dpConnected()) {
            return
        }

        // 如果断开的是 LE 设备（如共享单车），不是音频设备，不需要暂停
        if (device.type == android.bluetooth.BluetoothDevice.DEVICE_TYPE_LE) {
            Log.d(tag, "LE device $eventType, not pausing")
            return
        }

        // 经典蓝牙设备断开，可能是音频设备，暂停
        if (getIsPlaying() == true) {
            doPause()
        }
    }
}
