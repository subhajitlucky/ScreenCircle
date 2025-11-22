package com.example.screencircle.ui.dashboard

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.example.screencircle.data.repository.GroupMember
import com.example.screencircle.databinding.ItemGroupMemberBinding

class GroupAdapter : ListAdapter<GroupMember, GroupAdapter.ViewHolder>(DiffCallback()) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemGroupMemberBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(getItem(position), position)
    }

    class ViewHolder(private val binding: ItemGroupMemberBinding) : RecyclerView.ViewHolder(binding.root) {
        fun bind(member: GroupMember, position: Int) {
            binding.tvName.text = member.name
            val hours = member.todayUsage / 3600
            val minutes = (member.todayUsage % 3600) / 60
            binding.tvUsage.text = String.format("%02dh %02dm", hours, minutes)

            // Ranking Logic
            when (position) {
                0 -> {
                    binding.tvRank.text = "👑" // King
                    binding.tvRank.visibility = android.view.View.VISIBLE
                }
                1 -> {
                    binding.tvRank.text = "🥈" // Silver
                    binding.tvRank.visibility = android.view.View.VISIBLE
                }
                2 -> {
                    binding.tvRank.text = "🥉" // Bronze
                    binding.tvRank.visibility = android.view.View.VISIBLE
                }
                else -> {
                    binding.tvRank.visibility = android.view.View.GONE
                }
            }
        }
    }

    class DiffCallback : DiffUtil.ItemCallback<GroupMember>() {
        override fun areItemsTheSame(oldItem: GroupMember, newItem: GroupMember) = oldItem.userId == newItem.userId
        override fun areContentsTheSame(oldItem: GroupMember, newItem: GroupMember) = oldItem == newItem
    }
}
