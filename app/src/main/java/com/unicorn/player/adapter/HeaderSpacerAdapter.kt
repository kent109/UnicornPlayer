package com.unicorn.player.adapter

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.unicorn.player.R

class HeaderSpacerAdapter : RecyclerView.Adapter<HeaderSpacerAdapter.SpacerViewHolder>() {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): SpacerViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_header_spacer, parent, false)
        return SpacerViewHolder(view)
    }

    override fun onBindViewHolder(holder: SpacerViewHolder, position: Int) {}

    override fun getItemCount(): Int = 1

    class SpacerViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView)
}
