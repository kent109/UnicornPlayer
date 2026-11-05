package com.unicorn.player.util

import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.RecyclerView
import com.unicorn.player.adapter.SongAdapter
import com.unicorn.player.model.Song
import com.unicorn.player.service.MusicService

/**
 * 歌曲列表长按拖动排序助手（只在自定义排序模式下生效）
 *
 * 首页全部歌曲、歌手/专辑/歌单详情页共用本类：宿主把提交给 SongAdapter 的入口换成
 * [submit]，其余交互不变。列表不新增拖动手柄，长按整行即进入拖拽，松手后顺序立即生效。
 *
 * 关于 DiffUtil：SongAdapter 是 ListAdapter，而 ItemTouchHelper 只负责手势与视图位移，
 * 数据变更与 notifyItemMoved 都要回调自己做。这里就地重排提交给 adapter 的那个列表实例
 * （AsyncListDiffer 保存的就是调用方传入的引用）再通知移动，比对基准始终等于屏幕上的顺序，
 * 若改成"改数据 + 重新 submitList"，同一次移动会被 diff 再算一遍，动画与位置都会错。
 * 顺带保证点击取到的条目与显示顺序一致，不会出现"拖完点错歌"。
 */
class SongDragReorderHelper(
    private val recyclerView: RecyclerView,
    private val adapter: SongAdapter,
    private val scopeProvider: () -> String,
    private val canDrag: () -> Boolean,
    private val serviceProvider: () -> MusicService?,
    private val onOrderChanged: (scope: String, songs: List<Song>) -> Unit
) {

    private var touchHelper: ItemTouchHelper? = null

    /** 提交给 adapter 的那一份可变列表；拖动就地重排它 */
    private var backing: MutableList<Song> = ArrayList()

    private var dragging = false
    private var movedDuringDrag = false

    /** 拖拽期间到达的新数据：不打断手势，松手后再合并提交 */
    private var pendingSubmit: List<Song>? = null

    val isDragging: Boolean
        get() = dragging

    fun attach() {
        touchHelper = ItemTouchHelper(callback).apply { attachToRecyclerView(recyclerView) }
    }

    fun release() {
        touchHelper?.attachToRecyclerView(null)
        touchHelper = null
    }

    /**
     * 后台 diff 未落地时不允许起拖：拖动是就地改动提交给 adapter 的那个列表实例，
     * 与后台 diff 并发读同一实例会拿到中间状态。
     */
    private fun dragAllowed(): Boolean = canDrag() && !adapter.isDiffPending

    /** 取代直接的 adapter.submitList：拖拽中排队，空闲时以可变副本提交 */
    fun submit(songs: List<Song>) {
        if (dragging) {
            pendingSubmit = songs
            return
        }
        backing = ArrayList(songs)
        adapter.submitList(backing)
    }

    private val callback = object : ItemTouchHelper.SimpleCallback(
        ItemTouchHelper.UP or ItemTouchHelper.DOWN, 0
    ) {

        override fun isLongPressDragEnabled(): Boolean = dragAllowed()

        override fun isItemViewSwipeEnabled(): Boolean = false

        override fun getMovementFlags(
            recyclerView: RecyclerView,
            viewHolder: RecyclerView.ViewHolder
        ): Int {
            // 非歌曲行（如首页顶部的占位 header）不参与拖动
            if (!dragAllowed() || viewHolder !is SongAdapter.SongViewHolder) return 0
            return makeMovementFlags(ItemTouchHelper.UP or ItemTouchHelper.DOWN, 0)
        }

        override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {
            // 只允许上下拖动，没有滑动动作
        }

        override fun onMove(
            recyclerView: RecyclerView,
            viewHolder: RecyclerView.ViewHolder,
            target: RecyclerView.ViewHolder
        ): Boolean {
            if (target !is SongAdapter.SongViewHolder) return false
            val from = viewHolder.bindingAdapterPosition
            val to = target.bindingAdapterPosition
            if (from == RecyclerView.NO_POSITION || to == RecyclerView.NO_POSITION) return false
            if (from >= backing.size || to >= backing.size) return false
            backing.add(to, backing.removeAt(from))
            movedDuringDrag = true
            // ItemTouchHelper 不会代派移动通知（源码里 onMove 返回 true 后只做视图位移），
            // 必须由这里通知 adapter，否则其它行不补位、松手后拖动的行弹回原位。
            // 通知子 adapter 用局部位置；首页是 ConcatAdapter，子通知会由它加偏移后转发。
            adapter.notifyItemMoved(from, to)
            return true
        }

        override fun onSelectedChanged(viewHolder: RecyclerView.ViewHolder?, action: Int) {
            super.onSelectedChanged(viewHolder, action)
            if (action == ItemTouchHelper.ACTION_STATE_DRAG && viewHolder != null) {
                dragging = true
                movedDuringDrag = false
                pendingSubmit = null
                viewHolder.itemView.alpha = 0.7f
            }
        }

        override fun clearView(recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder) {
            super.clearView(recyclerView, viewHolder)
            viewHolder.itemView.alpha = 1f
            if (!dragging) return
            dragging = false
            finishDrag()
        }
    }

    private fun finishDrag() {
        val pending = pendingSubmit
        pendingSubmit = null
        if (!movedDuringDrag) {
            // 只是长按没有移动：仍要把拖拽期间攒下的数据刷新补上
            if (pending != null) submit(pending)
            return
        }
        val ordered = ArrayList(backing)
        val scope = scopeProvider()
        onOrderChanged(scope, ordered)
        syncQueueIfCurrentSource(ordered)
        if (pending != null) {
            // 拖拽期间到达的新数据（如隐藏歌曲、重新扫描）：按刚落地的拖动顺序重排后再提交
            submit(CustomSortStore.applyOrder(recyclerView.context, scope, pending))
        }
    }

    /**
     * 松手后，如果这个列表正是当前播放队列的来源，队列跟着新顺序走，
     * 当前歌曲继续播放不受影响，只把它在队列里的位置挪到新处。
     */
    private fun syncQueueIfCurrentSource(ordered: List<Song>) {
        val service = serviceProvider() ?: return
        // scope 键已统一小写，service 标签存的是点击时的原始写法，比较须忽略大小写
        if (!service.currentPlaySourceTag().equals(scopeProvider(), ignoreCase = true)) return
        val currentId = service.currentSong.value?.id
        // 交给 service 的必须是副本：setSongList 内部 clear() 会连带清掉传入的引用，
        // 直接把 backing 交出去会把列表数据清空
        val copy = ArrayList(ordered)
        val index = copy.indexOfFirst { it.id == currentId }.coerceAtLeast(0)
        service.setSongList(copy, index)
    }
}
