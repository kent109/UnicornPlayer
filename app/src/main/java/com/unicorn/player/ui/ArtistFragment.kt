package com.unicorn.player.ui

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import androidx.lifecycle.ViewModelProvider
import androidx.recyclerview.widget.LinearLayoutManager
import com.unicorn.player.ArtistSongsActivity
import com.unicorn.player.adapter.ArtistAdapter
import com.unicorn.player.databinding.FragmentArtistBinding
import com.unicorn.player.model.Artist
import com.unicorn.player.model.Song
import com.unicorn.player.repository.MusicRepository
import com.unicorn.player.ui.SongsFragment.SongListHost
import com.unicorn.player.util.PinyinUtil
import com.unicorn.player.util.PlayHelper
import com.unicorn.player.util.PlaylistHelper
import com.unicorn.player.viewmodel.MusicViewModel
import com.unicorn.player.viewmodel.MusicViewModelFactory
import com.unicorn.player.viewmodel.PlaylistViewModel
import com.unicorn.player.viewmodel.PlaylistViewModelFactory

/**
 * 歌手标签页 Fragment，按歌手名分组显示歌曲数量
 */
class ArtistFragment : Fragment(), ArtistAdapter.OnArtistClickListener {

    private var _binding: FragmentArtistBinding? = null
    private val binding get() = _binding!!

    private var host: SongListHost? = null

    private lateinit var viewModel: MusicViewModel

    private lateinit var playlistViewModel: PlaylistViewModel

    private lateinit var playlistSongsLauncher: ActivityResultLauncher<Intent>
    private lateinit var artistAdapter: ArtistAdapter

    private var clickPlay: Boolean = false

    companion object {
        fun newInstance() = ArtistFragment()
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentArtistBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        setupViewModel()
        setupRecyclerView()
        setupPlaylistSongsLauncher()
        observeCurrentPlaying()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        binding.recyclerView.adapter = null
        _binding = null
    }

    private fun setupViewModel() {
        val repository = MusicRepository(requireContext())
        val factory = MusicViewModelFactory(repository, requireContext())
        viewModel = ViewModelProvider(requireActivity(), factory)[MusicViewModel::class.java]

        val playlistFactory = PlaylistViewModelFactory(repository, requireActivity().application)
        playlistViewModel =
            ViewModelProvider(requireActivity(), playlistFactory)[PlaylistViewModel::class.java]

        viewModel.allSongs.observe(viewLifecycleOwner) { songs ->
            submitArtists(songs)
            // 空列表时显示空数据提示
            if (songs.isNullOrEmpty()) {
                binding.emptyView.visibility = View.VISIBLE
            } else {
                binding.emptyView.visibility = View.GONE
            }
        }
    }

    private fun observeCurrentPlaying() {
        viewModel.currentPlayingArtist.observe(viewLifecycleOwner) { artistName ->
            artistAdapter.setPlayingArtist(artistName)
        }
    }

    private fun setupRecyclerView() {
        artistAdapter = ArtistAdapter(this)
        binding.recyclerView.apply {
            layoutManager = LinearLayoutManager(requireContext())
            adapter = artistAdapter
        }
        setupScrollListener()
    }

    private fun setupPlaylistSongsLauncher() {
        playlistSongsLauncher = registerForActivityResult(
            ActivityResultContracts.StartActivityForResult()
        ) { result ->
            if (result.resultCode == Activity.RESULT_OK) {
                // 歌单内歌曲有改动 → 主动刷新列表（更新歌曲数量、更新时间）
                playlistViewModel.refreshPlaylists()
            }
        }
    }

    /**
     * 监听滚动，滚到底部时显示歌手数量（参考 SongsFragment）
     */
    private fun setupScrollListener() {
        binding.recyclerView.addOnScrollListener(object :
            androidx.recyclerview.widget.RecyclerView.OnScrollListener() {
            override fun onScrolled(
                recyclerView: androidx.recyclerview.widget.RecyclerView,
                dx: Int,
                dy: Int
            ) {
                val layoutManager = recyclerView.layoutManager as? LinearLayoutManager ?: return
                val totalItemCount = layoutManager.itemCount
                val lastVisibleItem = layoutManager.findLastVisibleItemPosition()

                val isAtBottom = if (lastVisibleItem == totalItemCount - 1) {
                    val lastItemView = layoutManager.findViewByPosition(lastVisibleItem)
                    lastItemView != null && lastItemView.bottom <= recyclerView.bottom
                } else {
                    false
                }

                binding.tvArtistCount.visibility = if (isAtBottom) View.VISIBLE else View.GONE
            }
        })
    }

    /**
     * 按歌手名分组统计歌曲数量（不区分大小写），按歌手名排序后提交
     */
    private fun submitArtists(songs: List<Song>) {
        // 1. 以 lowerCase 名作为分组键，保留首次出现的原始大小写用于显示
        val displayCase = LinkedHashMap<String, String>()
        // 2. 统计每个艺术家的歌曲数量
        val counts = LinkedHashMap<String, Int>()
        // 将存储 ID 的 Set 改为存储 Song 对象的 MutableList
        val artistSongs = LinkedHashMap<String, MutableList<Song>>()
        for (song in songs) {
            val key = song.artist.lowercase()
            // 记录原始名称
            if (!displayCase.containsKey(key)) {
                displayCase[key] = song.artist
            }
            // 计数 +1
            counts[key] = (counts[key] ?: 0) + 1
            // 将当前 song 对象直接添加到列表中
            // getOrPut: 如果 key 不存在，创建一个新的 mutableListOf 并放入 map，然后返回该列表
            artistSongs.getOrPut(key) { mutableListOf() }.add(song)
        }
        // 3. 构建 Artist 列表
        val artists = counts.map { (key, count) ->
            // 由于 counts 和 artistSongs 是基于相同的 key 生成的，这里一定存在
            val songList = artistSongs[key] ?: mutableListOf()

            Artist(
                name = displayCase[key] ?: key,
                pinyinName = PinyinUtil.getPinyinString(displayCase[key] ?: key),
                songCount = count,
                songList = songList
            )
        }.sortedBy { it.pinyinName }
        artistAdapter.submitList(artists)
        updateArtistCount(artists.size)
    }


    private fun updateArtistCount(count: Int) {
        binding.tvArtistCount.text = "共 $count 位歌手"
    }

    override fun onArtistClick(artist: Artist, position: Int) {
        // 使用 launcher 启动，以便在歌曲改动后（RESULT_OK）触发刷新
        val playingArtist = artistAdapter.getPlayingArtist()
        clickPlay = playingArtist != null && playingArtist == artist.name
        playlistSongsLauncher.launch(
            ArtistSongsActivity.newIntent(requireContext(), artist.name, clickPlay)
        )
    }

    override fun onArtistLongClick(artist: Artist, position: Int) {
        ArtistInfoDialog(
            requireContext(), artist,
            onAddToPlaylist = {
                val service = host?.musicService
                if (service != null) {
                    val songIds = artist.songList.map { it.id }.toSet()
                    PlaylistHelper.addToPlaylist(
                        songIds, requireActivity() as AppCompatActivity, viewModel, service
                    )
                } else {
                    Toast.makeText(context, "音乐服务没有运行", Toast.LENGTH_SHORT).show()
                }
            },
            onPlay = {
                val service = host?.musicService
                // 从全量歌曲列表中过滤并排序，确保与当前排序模式一致
                val allSongs = viewModel.allSongs.value ?: emptyList()
                val artistSongs =
                    allSongs.filter { it.artist.equals(artist.name, ignoreCase = true) }
                val sortedArtistSongs = viewModel.sortWithCurrentMode(artistSongs)

                clickPlay = PlayHelper.playArtist(
                    context = requireContext(),
                    service = service,
                    artistName = artist.name,
                    songs = sortedArtistSongs
                )
            }
        ).show()
    }

    override fun onAttach(context: Context) {
        super.onAttach(context)
        host = context as? SongListHost
            ?: throw IllegalStateException("Host activity must implement SongListHost")
    }

    /**
     * 双击搜索框时滚动到顶部（由宿主 Activity 调用）
     */
    fun scrollToTop() {
        val layoutManager = binding.recyclerView.layoutManager as? LinearLayoutManager
        if (layoutManager != null && layoutManager.itemCount > 0) {
            binding.recyclerView.smoothScrollToPosition(0)
        }
    }
}
