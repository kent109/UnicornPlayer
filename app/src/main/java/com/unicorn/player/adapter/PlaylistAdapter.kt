package com.unicorn.player.adapter

import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.animation.AccelerateInterpolator
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.unicorn.player.R
import com.unicorn.player.ThemeSettingActivity
import com.unicorn.player.databinding.ItemPlaylistBinding
import com.unicorn.player.viewmodel.PlaylistViewModel

/**
 * 歌单列表适配器（ListAdapter + DiffUtil）
 *
 * - item 点击 → [OnPlaylistClickListener.onPlaylistClick]
 * - 滑动露出底层按钮：[onEditClicked] / [onDeleteClicked]
 * - 由外部 ItemTouchHelper 驱动滑动；[resetSwipedItem] 复位某项。
 *
 * 注意：**不**走 ItemTouchHelper 的 SWIPE_SUCCESS 路径。
 * SWIPE_SUCCESS 会在 ItemTouchHelper 中留下 RecoverAnimation 孤儿（reveal 不 detach 视图），
 * 导致下一次 tap 被 findAnimation() 吃掉 → "第一次点击看似无效"。
 * 改为：ItemTouchHelper 仅做 clamp + onChildDraw 视觉；open/close 完全由触摸决策 →
 * notifyItemChanged → bind() 驱动。
 */
class PlaylistAdapter(
    private val listener: OnPlaylistClickListener
) : ListAdapter<PlaylistViewModel.PlaylistInfo, PlaylistAdapter.PlaylistHolder>(DIFF) {

    /** 当前处于"已滑开"状态的位置（单指仅允许一个展开），-1 表示无 */
    private var swipedPosition: Int = -1

    /** 上次量到的操作按钮区宽度（px），作为卡片向左平移的距离 */
    private var actionWidth: Int = 0

    /** 当前正在播放的歌单ID */
    private var currentPlayingPlaylistId: Long? = null

    /** 供 ItemTouchHelper 读取操作按钮区宽度以限制滑动距离，避免过拉 */
    internal val actionWidthPx: Int get() = actionWidth

    fun setPlayingPlaylistId(playlistId: Long?) {
        currentPlayingPlaylistId = playlistId
        notifyDataSetChanged()
    }

    interface OnPlaylistClickListener {
        fun onPlaylistClick(playlist: PlaylistViewModel.PlaylistInfo, position: Int)
        fun onPlayClicked(playlist: PlaylistViewModel.PlaylistInfo, position: Int)
        fun onEditClicked(playlist: PlaylistViewModel.PlaylistInfo, position: Int)
        fun onDeleteClicked(playlist: PlaylistViewModel.PlaylistInfo, position: Int)
        fun onExportClicked(playlist: PlaylistViewModel.PlaylistInfo, position: Int)
        fun onSwipedOpened(position: Int)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PlaylistHolder {
        val binding = ItemPlaylistBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return PlaylistHolder(binding)
    }

    override fun onBindViewHolder(holder: PlaylistHolder, position: Int) {
        holder.bind(getItem(position), position, isSwiped(position))
    }

    fun getPlaylistAt(position: Int): PlaylistViewModel.PlaylistInfo? = getItem(position)

    /**
     * 标记某个位置进入已滑开状态（先复位别的项）
     */
    fun openSwipe(position: Int) {
        if (position == swipedPosition) return
        val prev = swipedPosition
        swipedPosition = position
        Log.d(TAG, "openSwipe position=$position prev=$prev")
        if (prev != -1) notifyItemChanged(prev)
        notifyItemChanged(position)
        listener.onSwipedOpened(position)
    }

    /**
     * 复位当前已滑开项。一律走 notifyItemChanged 驱动 bind() 的平移动画。

     */
    fun resetSwipedItem(): Boolean {
        if (swipedPosition == -1) return false
        val prev = swipedPosition
        swipedPosition = -1
        notifyItemChanged(prev)
        return true
    }

    /**
     * 仅清除 swiped 状态标记，不做任何视图操作。
     *
     * 调用场景：列表数据更新前清除状态，防止 DiffUtil 让新滑入该位置的 item 继承旧平移。
     * 视图收起动画由 [collapseExpandedItemWithAnim] 单独控制时机。
     */
    fun forceResetSwipeState() {
        swipedPosition = -1
    }

    /**
     * 以动画收起所有可见的已展开 item（translationX != 0 的卡片）。
     *
     * 不依赖 [swipedPosition]，直接遍历可见 holder 收回平移，
     * 因此可在 [forceResetSwipeState] 之后（状态已清零）单独调用。
     */
    fun collapseVisibleExpandedItems() {
        val rv = attachedRecyclerView ?: return
        val lm = rv.layoutManager as? androidx.recyclerview.widget.LinearLayoutManager ?: return
        val first = lm.findFirstVisibleItemPosition()
        val last = lm.findLastVisibleItemPosition()
        if (first == RecyclerView.NO_POSITION || last == RecyclerView.NO_POSITION) return
        for (pos in first..last) {
            rv.findViewHolderForAdapterPosition(pos)
                ?.let { it as? PlaylistHolder }?.let { holder ->
                    val card = holder.binding.cardContent
                    if (card.translationX != 0f) {
                        holder.animateCardTo(0f, REVEAL_ANIM_MS)
                        holder.lastSwiped = false
                    }
                }
        }
    }

    /** 由 [onAttachedToRecyclerView] 赋值，供 [forceResetSwipeState] 查找 holder */
    private var attachedRecyclerView: RecyclerView? = null

    override fun onAttachedToRecyclerView(recyclerView: RecyclerView) {
        super.onAttachedToRecyclerView(recyclerView)
        attachedRecyclerView = recyclerView
    }

    override fun onDetachedFromRecyclerView(recyclerView: RecyclerView) {
        super.onDetachedFromRecyclerView(recyclerView)
        attachedRecyclerView = null
    }

    fun isSwiped(position: Int): Boolean = swipedPosition == position

    inner class PlaylistHolder(
        internal val binding: ItemPlaylistBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        /** 上次绑定的 swiped 状态，用于在 [bind] 中检测"状态切换"从而驱动动画 */
        var lastSwiped = false

        init {
            // 整行点击：若当前已滑开，点击内容区应关闭；否则触发 onPlaylistClick
            binding.cardContent.setOnClickListener {
                val pos = bindingAdapterPosition
                Log.d(TAG, "cardContent click pos=$pos swipedPos=$swipedPosition")
                if (pos == RecyclerView.NO_POSITION) return@setOnClickListener
                if (swipedPosition == pos) {
                    resetSwipedItem()
                } else {
                    listener.onPlaylistClick(getItem(pos), pos)
                }
            }

            // 整行长按：未滑开时露出操作按钮，已滑开时关闭
            binding.cardContent.setOnLongClickListener {
                val pos = bindingAdapterPosition
                if (pos == RecyclerView.NO_POSITION) return@setOnLongClickListener false
                if (swipedPosition == pos) {
                    resetSwipedItem()
                } else {
                    openSwipe(pos)
                }
                true
            }

            binding.btnEdit.setOnClickListener {
                val pos = bindingAdapterPosition
                if (pos == RecyclerView.NO_POSITION) return@setOnClickListener
                resetSwipedItem()
                listener.onEditClicked(getItem(pos), pos)
            }

            binding.btnDelete.setOnClickListener {
                val pos = bindingAdapterPosition
                if (pos == RecyclerView.NO_POSITION) return@setOnClickListener
                resetSwipedItem()
                listener.onDeleteClicked(getItem(pos), pos)
            }

            binding.btnExport.setOnClickListener {
                val pos = bindingAdapterPosition
                if (pos == RecyclerView.NO_POSITION) return@setOnClickListener
                resetSwipedItem()
                listener.onExportClicked(getItem(pos), pos)
            }

            binding.btnPlay.setOnClickListener {
                val pos = bindingAdapterPosition
                if (pos == RecyclerView.NO_POSITION) return@setOnClickListener
                resetSwipedItem()
                listener.onPlayClicked(getItem(pos), pos)
            }
        }

        fun bind(
            playlist: PlaylistViewModel.PlaylistInfo,
            position: Int,
            isSwiped: Boolean
        ) {
            binding.tvPlaylistName.text = playlist.name
            binding.tvSongCount.text = "${playlist.songCount} 首"

            // 仅当歌单正在播放时高亮（通过观察当前正在播放的歌单ID）
            if (playlist.id == currentPlayingPlaylistId) {
                val highlightColor = ThemeSettingActivity.resolveHighlightColor(
                    binding.ivPlaylistIcon.context
                )
                binding.ivPlaylistIcon.isSelected = true
                binding.ivPlaylistIcon.setColorFilter(highlightColor)
                binding.tvPlaylistName.setTextColor(highlightColor)
                binding.tvSongCount.setTextColor(highlightColor)
            } else {
                binding.ivPlaylistIcon.isSelected = false
                binding.ivPlaylistIcon.clearColorFilter()
                binding.tvPlaylistName.setTextColor(
                    ContextCompat.getColor(
                        binding.tvPlaylistName.context,
                        R.color.onSurface
                    )
                )
                binding.tvSongCount.setTextColor(
                    ContextCompat.getColor(
                        binding.tvSongCount.context,
                        R.color.onSurfaceVariant
                    )
                )
            }

            binding.actionContainer.visibility = View.VISIBLE
            // 同步量一次操作按钮区的实际宽度，作为卡片平移距离；
            // post 异步兜底，避免首次滑动时宽度仍为 0。
            val widthNow = binding.actionContainer.width
            if (widthNow > 0) {
                actionWidth = widthNow
            }
            binding.actionContainer.post {
                if (binding.actionContainer.width > 0) {
                    actionWidth = binding.actionContainer.width
                }
            }

            // 仅当 swiped 状态发生切换时，才播放平移动画，使操作按钮区的显露与收起平滑过渡；
            // 普通滚动复用（状态不变）时则直接落到目标位，避免残影。
            // 拖拽过程中的位移由触摸层实时驱动，不走此处。
            val targetX = if (isSwiped && actionWidth > 0) -actionWidth.toFloat() else 0f
            val card = binding.cardContent
            Log.d(
                TAG, "bind pos=$position isSwiped=$isSwiped lastSwiped=$lastSwiped " +
                        "curX=${card.translationX} targetX=$targetX"
            )
            if (lastSwiped != isSwiped) {
                lastSwiped = isSwiped
                card.animate().cancel()
                card.animate()
                    .translationX(targetX)
                    .setDuration(REVEAL_ANIM_MS)
                    .setInterpolator(AccelerateInterpolator())
                    .start()
            } else {
                card.animate().cancel()
                card.translationX = targetX
            }
        }

        /** 供触摸层实时写平移（绕过动画，拖动中） */
        fun setCardX(x: Float) {
            binding.cardContent.animate().cancel()
            binding.cardContent.translationX = x
        }

        /** 供触摸层动画到目标 */
        fun animateCardTo(targetX: Float, duration: Long = REVEAL_ANIM_MS) {
            binding.cardContent.animate().cancel()
            binding.cardContent.animate()
                .translationX(targetX)
                .setDuration(duration)
                .setInterpolator(AccelerateInterpolator())
                .start()
        }

        fun cardContent(): View = binding.cardContent
    }

    /** 回收时清掉平移与过渡态，避免新绑定到该 holder 的 item 播放多余的收起动画 */
    override fun onViewRecycled(holder: PlaylistHolder) {
        super.onViewRecycled(holder)
        holder.binding.cardContent.animate().cancel()
        holder.binding.cardContent.translationX = 0f
        holder.lastSwiped = false
    }

    companion object {
        private const val TAG = "PlaylistSwipe"

        /** 内容卡片展开 / 收起过渡动画时长（毫秒） */
        const val REVEAL_ANIM_MS = 180L

        private val DIFF = object : DiffUtil.ItemCallback<PlaylistViewModel.PlaylistInfo>() {
            override fun areItemsTheSame(
                oldItem: PlaylistViewModel.PlaylistInfo,
                newItem: PlaylistViewModel.PlaylistInfo
            ): Boolean = oldItem.id == newItem.id

            override fun areContentsTheSame(
                oldItem: PlaylistViewModel.PlaylistInfo,
                newItem: PlaylistViewModel.PlaylistInfo
            ): Boolean = oldItem == newItem
        }
    }
}
