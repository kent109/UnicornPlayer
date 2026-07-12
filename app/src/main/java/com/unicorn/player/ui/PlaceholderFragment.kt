package com.unicorn.player.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.fragment.app.Fragment
import com.unicorn.player.R

/**
 * 占位 Fragment，用于歌手/专辑/歌单等尚未实现的标签页
 */
open class PlaceholderFragment : Fragment() {

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.fragment_placeholder, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val title = arguments?.getString(ARG_TITLE) ?: ""
        view.findViewById<TextView>(R.id.tvPlaceholder).text = title
    }

    companion object {
        private const val ARG_TITLE = "title"

        fun newInstance(title: String): PlaceholderFragment {
            return PlaceholderFragment().apply {
                arguments = Bundle().apply { putString(ARG_TITLE, title) }
            }
        }
    }
}

/**
 * 歌单标签页
 */
class PlaylistFragment : PlaceholderFragment() {
    companion object {
        fun newInstance() = PlaceholderFragment.newInstance("歌单")
    }
}
