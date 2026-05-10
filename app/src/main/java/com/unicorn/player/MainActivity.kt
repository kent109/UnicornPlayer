package com.unicorn.player

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.IBinder
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModelProvider
import androidx.recyclerview.widget.LinearLayoutManager
import com.unicorn.player.databinding.ActivityMainBinding
import com.unicorn.player.model.Song
import com.unicorn.player.repository.MusicRepository
import com.unicorn.player.service.MusicService
import com.unicorn.player.viewmodel.MusicViewModel
import com.unicorn.player.viewmodel.MusicViewModelFactory
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity(), SongAdapter.OnSongClickListener {

    private lateinit var binding: ActivityMainBinding
    private lateinit var viewModel: MusicViewModel
    private lateinit var songAdapter: SongAdapter

    private var musicService: MusicService? = null
    private var isServiceBound = false

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            loadMusic()
        } else {
            Toast.makeText(this, "需要存储权限才能访问音乐文件", Toast.LENGTH_LONG).show()
            finish()
        }
    }

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as MusicService.MusicBinder
            musicService = binder.getService()
            isServiceBound = true
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            musicService = null
            isServiceBound = false
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupViewModel()
        setupRecyclerView()
        setupSearchView()
        setupBottomPlayer()

        checkPermissions()
        bindMusicService()
    }

    private fun setupViewModel() {
        val repository = MusicRepository(this)
        val factory = MusicViewModelFactory(repository)
        viewModel = ViewModelProvider(this, factory)[MusicViewModel::class.java]

        // Observe LiveData after ViewModel is ready
        viewModel.allSongs.observe(this) { songs ->
            if (::songAdapter.isInitialized) {
                songAdapter.submitList(songs)
            }
        }

        viewModel.isLoading.observe(this) { isLoading ->
            binding.progressBar.visibility = if (isLoading) android.view.View.VISIBLE else android.view.View.GONE
        }
    }

    private fun setupRecyclerView() {
        songAdapter = SongAdapter(this)
        binding.recyclerView.apply {
            layoutManager = LinearLayoutManager(this@MainActivity)
            adapter = songAdapter
        }
    }

    private fun setupSearchView() {
        binding.searchView.setOnQueryTextListener(object : androidx.appcompat.widget.SearchView.OnQueryTextListener {
            override fun onQueryTextSubmit(query: String?): Boolean {
                return false
            }

            override fun onQueryTextChange(newText: String?): Boolean {
                viewModel.searchSongs(newText ?: "")
                return true
            }
        })
    }

    private fun setupBottomPlayer() {
        binding.bottomPlayer.setOnClickListener {
            val intent = Intent(this, PlayerActivity::class.java)
            startActivity(intent)
        }
    }

    private fun checkPermissions() {
        when {
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.READ_MEDIA_AUDIO
            ) == PackageManager.PERMISSION_GRANTED -> {
                loadMusic()
            }
            ActivityCompat.shouldShowRequestPermissionRationale(
                this,
                Manifest.permission.READ_MEDIA_AUDIO
            ) -> {
                // Show explanation if needed
                permissionLauncher.launch(Manifest.permission.READ_EXTERNAL_STORAGE)
            }
            else -> {
                permissionLauncher.launch(Manifest.permission.READ_EXTERNAL_STORAGE)
            }
        }
    }

    private fun loadMusic() {
        viewModel.loadMusic()
    }

    private fun bindMusicService() {
        val intent = Intent(this, MusicService::class.java)
        bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
        startService(intent)
    }

    override fun onSongClick(song: Song, position: Int) {
        viewModel.allSongs.value?.let { songs ->
            musicService?.setSongList(songs, position)
            musicService?.playSong(song)

            updateBottomPlayer(song)
        }
    }

    private fun updateBottomPlayer(song: Song) {
        binding.bottomPlayer.visibility = android.view.View.VISIBLE
        binding.songTitle.text = song.title
        binding.artistName.text = song.artist

        // Set album art if available
        // Glide.with(this)
        //     .load(song.albumArt)
        //     .placeholder(R.drawable.ic_music_note)
        //     .into(binding.albumArt)
    }

    override fun onResume() {
        super.onResume()
        // Update UI with current playing song
        musicService?.currentSong?.observe(this) { song ->
            song?.let { updateBottomPlayer(it) }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        if (isServiceBound) {
            unbindService(serviceConnection)
            isServiceBound = false
        }
    }
}