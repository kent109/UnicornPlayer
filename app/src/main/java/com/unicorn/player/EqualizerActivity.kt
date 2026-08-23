package com.unicorn.player

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.IBinder
import android.util.Log
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.bullhead.equalizer.AudioEffectManager
import com.bullhead.equalizer.EqualizerFragment
import com.bullhead.equalizer.Settings
import com.unicorn.player.databinding.ActivityEqualizerBinding
import com.unicorn.player.service.MusicService

class EqualizerActivity : AppCompatActivity() {

    private lateinit var binding: ActivityEqualizerBinding
    private var musicService: MusicService? = null
    private var isServiceBound = false

    companion object {
        private const val TAG = "EqualizerActivity"
    }

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as MusicService.MusicBinder
            musicService = binder.getService()
            isServiceBound = true
            setupEqualizerFragment()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            musicService = null
            isServiceBound = false
        }
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
        unbindService(serviceConnection)
        isServiceBound = false
        musicService = null
    }

    override fun onStart() {
        super.onStart()

        if (isServiceBound && musicService != null) {
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
            equalizerFragment.showSaveEqDialog(true)
        }
    }

    private fun bindMusicService() {
        val intent = Intent(this, MusicService::class.java)
        bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
        startService(intent)
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
        if (isServiceBound && musicService != null) {
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
