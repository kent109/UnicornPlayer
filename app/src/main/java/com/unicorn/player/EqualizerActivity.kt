package com.unicorn.player

import android.os.Bundle
import android.util.Log
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.bullhead.equalizer.AudioEffectManager
import com.bullhead.equalizer.EqualizerFragment
import com.bullhead.equalizer.Settings
import com.unicorn.player.databinding.ActivityEqualizerBinding
import com.unicorn.player.manager.MusicManager
import com.unicorn.player.service.MusicService

class EqualizerActivity : AppCompatActivity(), MusicManager.ConnectionCallback {

    private lateinit var binding: ActivityEqualizerBinding
    private var musicService: MusicService? = null

    companion object {
        private const val TAG = "EqualizerActivity"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityEqualizerBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupTitleBar()
        bindMusicService()
    }

    override fun onDestroy() {
        super.onDestroy()
        // 注销服务连接回调并解绑
        MusicManager.unregisterConnectionCallback(this)
        MusicManager.unbind(this)
    }

    override fun onStart() {
        super.onStart()

        if (musicService != null) {
            val sessionId = musicService?.getAudioSessionId() ?: 0

            if (sessionId == 0) {
                Log.e(TAG, "Invalid audio session ID: 0")
                return
            }

            setupEqualizerFragment()

            if (Settings.isEqualizerEnabled) {
                AudioEffectManager.enableEffects(this)
            } else {
                AudioEffectManager.disableEffects()
            }
        }
    }

    private fun setupTitleBar() {
        binding.titleBar.setOnBackClickListener {
            val equalizerFragment =
                supportFragmentManager.findFragmentByTag("f_eq") as? EqualizerFragment
            if (equalizerFragment == null) {
                finish()
                return@setOnBackClickListener
            }
            equalizerFragment.showSaveEqDialog(true, null)
        }
    }

    private fun bindMusicService() {
        MusicManager.registerConnectionCallback(this)
        val alreadyConnected = MusicManager.bind(this)
        musicService = MusicManager.getService()
        if (alreadyConnected && musicService != null) {
            onServiceConnected(musicService)
        }
    }

    // ==================== MusicManager.ConnectionCallback 实现 ====================

    override fun onServiceConnected(service: MusicService?) {
        musicService = service ?: return
        // 服务连接后立即设置均衡器
        val sessionId = musicService?.getAudioSessionId() ?: 0
        if (sessionId == 0) {
            Log.e(TAG, "Invalid audio session ID: 0")
            return
        }
        setupEqualizerFragment()
        if (Settings.isEqualizerEnabled) {
            AudioEffectManager.enableEffects(this)
        } else {
            AudioEffectManager.disableEffects()
        }
    }

    override fun onServiceDisconnected() {
        musicService = null
    }

    private fun setupEqualizerFragment() {
        val sessionId = musicService?.getAudioSessionId() ?: 0
        if (sessionId == 0) {
            finish()
            return
        }

        var equalizerFragment =
            supportFragmentManager.findFragmentByTag("f_eq") as? EqualizerFragment
        if (equalizerFragment != null && equalizerFragment.isVisible) {
            return
        }

        equalizerFragment = EqualizerFragment.newBuilder()
            .setAccentColor(ContextCompat.getColor(this, R.color.primary))
            .setAudioSessionId(sessionId)
            .build()

        supportFragmentManager.beginTransaction()
            .replace(R.id.eqFrame, equalizerFragment, "f_eq")
            .commit()
    }

    override fun onResume() {
        super.onResume()
        if (musicService != null) {
            val sessionId = musicService?.getAudioSessionId() ?: 0
            if (sessionId != 0) {
                val existingFragment = supportFragmentManager.findFragmentById(R.id.eqFrame)
                if (existingFragment == null || !existingFragment.isVisible) {
                    setupEqualizerFragment()
                }
            }
        }
    }

    override fun onPause() {
        super.onPause()
        musicService?.savePlaybackState()
    }
}
