package com.unicorn.player

import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.SeekBar
import androidx.fragment.app.Fragment
import com.bumptech.glide.Glide
import com.unicorn.player.databinding.FragmentPlayerBinding
import com.unicorn.player.model.Song
import com.unicorn.player.service.MusicService
import java.util.Locale
import java.util.concurrent.TimeUnit

class PlayerFragment : Fragment() {

    private var _binding: FragmentPlayerBinding? = null
    private val binding get() = _binding!!

    private var currentSong: Song? = null

    companion object {
        private const val ARG_SONG = "song"

        fun newInstance(song: Song): PlayerFragment {
            return PlayerFragment().apply {
                arguments = Bundle().apply {
                    putParcelable(ARG_SONG, song)
                }
            }
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentPlayerBinding.inflate(inflater, container, false)
        binding.root.tag = this
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        arguments?.let {
            currentSong = it.getParcelable(ARG_SONG)
        }
        Log.d("PlayerFragment", "onViewCreated: currentSong = ${currentSong?.title}")
        setupClickListeners()
        observeServiceState()
        // 立即显示当前Fragment的歌曲信息
        currentSong?.let {
            updateSongInfo(it)
            updatePlayPauseState(false)
        }
    }

    private fun setupClickListeners() {
        val service = getService() ?: return

        binding.playPauseButton.setOnClickListener {
            val isPlaying = service.isPlaying.value ?: false
            if (isPlaying) {
                service.pause()
            } else {
                service.requestAudioFocusAndPlay()
            }
            service.updateNotification()
        }

        binding.nextButton.setOnClickListener {
            service.requestAudioFocusAndPlayNext()
            service.updateNotification()
            (activity as? PlayerActivity)?.applyPageTransformer()
        }

        binding.previousButton.setOnClickListener {
            service.requestAudioFocusAndPlayPrevious()
            service.updateNotification()
            (activity as? PlayerActivity)?.applyPageTransformer()
        }

        binding.seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                // 只在用户拖动且当前Fragment的歌曲正在播放时更新播放进度
                if (fromUser) {
                    val service = getService() ?: return
                    val playingSong = service.currentSong.value
                    val isCurrentSongPlaying = currentSong?.id == playingSong?.id

                    if (isCurrentSongPlaying) {
                        service.seekTo(progress)
                        // 通知 PlayerActivity：抑制进度观察者（异步 seek 期间
                        // 可能读到旧位置）并取消滚动动画；拖动中歌词保持原位
                        (activity as? PlayerActivity)?.onUserSeeking()
                    }
                }
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) {}

            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                // 松手：歌词按库自身的 400ms 动画平滑滚动到目标位置
                val progress = seekBar?.progress ?: return
                (activity as? PlayerActivity)?.onUserSeekEnd(progress)
            }
        })
    }

    private fun observeServiceState() {
        val service = getService() ?: return

        // 观察当前播放的歌曲变化，只更新播放状态
        service.currentSong.observe(viewLifecycleOwner) { playingSong ->
            // 检查当前Fragment的歌曲是否正在播放
            val isCurrentSongPlaying = currentSong?.id == playingSong?.id
            updatePlayPauseState(isCurrentSongPlaying)
        }

        // 观察播放状态变化
        service.isPlaying.observe(viewLifecycleOwner) { isPlaying ->
            val service = getService() ?: return@observe
            val playingSong = service.currentSong.value
            val isCurrentSongPlaying = currentSong?.id == playingSong?.id
            updatePlayPauseState(isCurrentSongPlaying && isPlaying)
        }

        // 观察播放位置变化（只当前歌曲播放时）
        service.currentPosition.observe(viewLifecycleOwner) { position ->
            updateSeekBar(position)
        }
    }

    private fun updatePlayPauseState(isCurrentSongPlaying: Boolean) {
        if (isCurrentSongPlaying) {
            // 当前Fragment的歌曲正在播放，更新进度条
            updateTimeDisplay()
            updatePlayPauseButton(true)
        } else {
            updatePlayPauseButton(false)
        }
    }

    private fun updatePlayPauseButton(isPlaying: Boolean) {
        _binding?.let { binding ->
            binding.playPauseButton.setImageResource(
                if (isPlaying) R.drawable.ic_pause else R.drawable.ic_play
            )
        }
    }

    private fun updateSongInfo(song: Song?) {
        song?.let {
            currentSong = it
            binding.songTitle.text = it.title
            binding.artistName.text = it.artist
            binding.albumName.text = it.album

            Log.d("PlayerFragment", "Updating song info: ${it.title} - ${it.artist}")

            Glide.with(this)
                .load(it.albumArt)
                .placeholder(R.drawable.ic_music_note)
                .error(R.drawable.ic_music_note)
                .transform(com.bumptech.glide.load.resource.bitmap.RoundedCorners(36))
                .into(binding.albumArt)

            // 设置进度条最大值（使用歌曲实际时长）
            binding.seekBar.max = it.duration.toInt()
            updateTimeDisplay()
        }
    }

    fun updateCurrentSong(song: Song) {
        currentSong = song
        updateSongInfo(song)
    }

    private fun updateSeekBar(position: Int) {
        // 只在当前Fragment的歌曲正在播放时更新进度条
        val service = getService() ?: return
        val playingSong = service.currentSong.value
        val isCurrentSongPlaying = currentSong?.id == playingSong?.id

        // 当ViewPager不可见时（歌词全屏模式），停止刷新进度条
        if (!isViewPagerVisible()) return

        if (isCurrentSongPlaying) {
            binding.seekBar.progress = position
            updateTimeDisplay()
        }
    }

    private fun updateTimeDisplay() {
        val service = getService() ?: return
        val playingSong = service.currentSong.value
        val isCurrentSongPlaying = currentSong?.id == playingSong?.id

        // 当ViewPager不可见时（歌词全屏模式），停止刷新时间显示
        if (!isViewPagerVisible()) return

        if (isCurrentSongPlaying) {
            val currentPosition = service.getCurrentPosition()
            val duration = service.getDuration()

            binding.currentTime.text = formatTime(currentPosition)
            binding.totalTime.text = formatTime(duration)
        } else {
            // 当前Fragment的歌曲未播放，显示歌曲总时长
            currentSong?.let {
                binding.currentTime.text = "00:00"
                binding.totalTime.text = formatTime(it.duration.toInt())
            }
        }
    }

    /**
     * 检查ViewPager是否可见（用于判断是否处于歌词全屏模式）
     */
    private fun isViewPagerVisible(): Boolean {
        val activity = activity as? PlayerActivity ?: return true
        return activity.isLrcFullscreen.not()
    }

    private fun formatTime(milliseconds: Int): String {
        val minutes = TimeUnit.MILLISECONDS.toMinutes(milliseconds.toLong())
        val seconds = TimeUnit.MILLISECONDS.toSeconds(milliseconds.toLong()) % 60
        return String.format(Locale.getDefault(), "%02d:%02d", minutes, seconds)
    }

    // 从Activity获取Service
    private fun getService(): MusicService? {
        val activity = requireActivity()
        return if (activity is PlayerActivity) {
            activity.getMusicService()
        } else {
            null
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}