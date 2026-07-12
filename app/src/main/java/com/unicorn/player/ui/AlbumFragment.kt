package com.unicorn.player.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.lifecycle.ViewModelProvider
import androidx.recyclerview.widget.LinearLayoutManager
import com.unicorn.player.AlbumSongsActivity
import com.unicorn.player.adapter.AlbumAdapter
import com.unicorn.player.databinding.FragmentAlbumBinding
import com.unicorn.player.model.Album
import com.unicorn.player.model.Song
import com.unicorn.player.repository.MusicRepository
import com.unicorn.player.util.PinyinUtil
import com.unicorn.player.viewmodel.MusicViewModel
import com.unicorn.player.viewmodel.MusicViewModelFactory
import java.text.Collator
import java.util.Locale

/**
 * 专辑标签页 Fragment，按专辑名拼音首字母分组展示专辑列表。
 * 中文与英文同字母归为一组（啊、abc → A），非中英文开头统一归入 "#"。
 * 每个分组项左侧 ic_album 图标，标题为专辑名，副标题为 "$歌手名 - $count 首"。
 */
class AlbumFragment : Fragment(), AlbumAdapter.OnAlbumClickListener {

    private var _binding: FragmentAlbumBinding? = null
    private val binding get() = _binding!!

    private lateinit var viewModel: MusicViewModel
    private lateinit var albumAdapter: AlbumAdapter

    private val collator = Collator.getInstance(Locale.CHINA)

    /** 每个首字母对应的第一个 Header 在扁平列表中的位置，用于侧边栏快速定位 */
    private var letterIndexMap: Map<String, Int> = emptyMap()

    companion object {
        fun newInstance() = AlbumFragment()
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentAlbumBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        setupViewModel()
        setupRecyclerView()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        binding.recyclerView.adapter = null
        _binding = null
    }

    private fun setupViewModel() {
        val factory = MusicViewModelFactory(MusicRepository(requireContext()), requireContext())
        viewModel = ViewModelProvider(requireActivity(), factory)[MusicViewModel::class.java]

        viewModel.allSongs.observe(viewLifecycleOwner) { songs ->
            submitAlbums(songs)
        }
    }

    private fun setupRecyclerView() {
        albumAdapter = AlbumAdapter(this)
        binding.recyclerView.apply {
            layoutManager = LinearLayoutManager(requireContext())
            adapter = albumAdapter
        }
        setupScrollListener()
        setupWaveSideBar()
    }

    /**
     * 绑定右侧字母导航栏，选中字母时滚动到对应的第一个分组头。
     */
    private fun setupWaveSideBar() {
        binding.waveSideBar.setIndexItems(
            "↑", "☆", "A", "B", "C", "D", "E", "F", "G", "H", "I",
            "J", "K", "L", "M", "N", "O", "P", "Q", "R", "S", "T", "U", "V", "W", "X", "Y", "Z", "#"
        )
        binding.waveSideBar.setOnSelectIndexItemListener { letter ->
            val position = letterIndexMap[letter] ?: return@setOnSelectIndexItemListener
            (binding.recyclerView.layoutManager as? LinearLayoutManager)
                ?.scrollToPositionWithOffset(position, 0)
        }
    }

    /**
     * 监听滚动，滚到底部时显示专辑数量（参考 ArtistFragment）。
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

                binding.tvAlbumCount.visibility = if (isAtBottom) View.VISIBLE else View.GONE
            }
        })
    }

    /**
     * 按专辑名分组统计歌曲数量，按拼音首字母分组排序后提交。
     */
    private fun submitAlbums(songs: List<Song>) {
        // 以 lowerCase 专辑名作为分组键，保留首次出现的原始大小写用于显示
        val displayCase = LinkedHashMap<String, String>()
        // 每组维护：歌曲数 与 各歌手出现次数（用于取数量最多的歌手作为副标题）
        val counts = LinkedHashMap<String, Int>()
        val artistCounts = LinkedHashMap<String, LinkedHashMap<String, Int>>()

        for (song in songs) {
            val key = song.album.lowercase()
            if (!displayCase.containsKey(key)) {
                displayCase[key] = song.album
            }
            counts[key] = (counts[key] ?: 0) + 1
            val groupArtists = artistCounts.getOrPut(key) { LinkedHashMap() }
            groupArtists[song.artist] = (groupArtists[song.artist] ?: 0) + 1
        }

        val albums = counts.map { (key, count) ->
            val topArtist = artistCounts[key]?.maxByOrNull { it.value }?.key ?: ""
            Album(displayCase[key] ?: key, topArtist, count)
        }

        // 按拼音首字母分组：字母 A-Z 顺序，"#" 置于末尾
        val grouped = LinkedHashMap<String, MutableList<Album>>()
        for (album in albums) {
            val letter = PinyinUtil.getPinyinFirstLetter(album.name)
            grouped.getOrPut(letter) { mutableListOf() }.add(album)
        }

        // 组内按专辑名排序（中文按拼音、英文不区分大小写）
        for ((_, list) in grouped) {
            list.sortWith(compareBy(collator) { it.name.lowercase() })
        }

        val orderedLetters = grouped.keys.sortedWith { a, b ->
            when {
                a == "#" -> 1
                b == "#" -> -1
                else -> a.compareTo(b)
            }
        }

        // 扁平化为 header + items 列表，并记录每个首字母第一个 Header 的位置
        val items = mutableListOf<AlbumAdapter.AlbumListItem>()
        val indexMap = LinkedHashMap<String, Int>()
        for (letter in orderedLetters) {
            indexMap[letter] = items.size
            items.add(AlbumAdapter.AlbumListItem.Header(letter))
            grouped[letter]?.forEach { album ->
                items.add(AlbumAdapter.AlbumListItem.Item(album))
            }
        }
        letterIndexMap = indexMap

        albumAdapter.submitList(items)
        updateAlbumCount(albums.size)
    }

    private fun updateAlbumCount(count: Int) {
        binding.tvAlbumCount.text = "共 $count 张专辑"
    }

    override fun onAlbumClick(album: Album, position: Int) {
        startActivity(AlbumSongsActivity.newIntent(requireContext(), album.name))
    }
}
