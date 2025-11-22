package com.example.screencircle.ui.dashboard

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.screencircle.databinding.FragmentDashboardBinding

class GroupDashboardFragment : Fragment() {

    private var _binding: FragmentDashboardBinding? = null
    private val binding get() = _binding!!
    private val viewModel: GroupViewModel by viewModels()
    private lateinit var adapter: GroupAdapter

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentDashboardBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        adapter = GroupAdapter()
        binding.rvMembers.layoutManager = LinearLayoutManager(context)
        binding.rvMembers.adapter = adapter

        viewModel.groupMembers.observe(viewLifecycleOwner) { members ->
            // Sort by usage ASCENDING (Less time = Winner)
            val sortedMembers = members.sortedBy { it.todayUsage }
            adapter.submitList(sortedMembers)
        }

        binding.btnCreateGroup.setOnClickListener {
            showCreateGroupDialog()
        }

        binding.btnJoinGroup.setOnClickListener {
            showJoinGroupDialog()
        }
    }

    private fun showCreateGroupDialog() {
        val input = EditText(context)
        AlertDialog.Builder(requireContext())
            .setTitle("Create Group")
            .setView(input)
            .setPositiveButton("Create") { _, _ ->
                val name = input.text.toString()
                viewModel.createGroup(name) { groupId ->
                    if (groupId != null) {
                        Toast.makeText(context, "Group Created: $groupId", Toast.LENGTH_LONG).show()
                        viewModel.loadGroupData(groupId)
                    }
                }
            }
            .show()
    }

    private fun showJoinGroupDialog() {
        val input = EditText(context)
        AlertDialog.Builder(requireContext())
            .setTitle("Join Group")
            .setMessage("Enter Group ID")
            .setView(input)
            .setPositiveButton("Join") { _, _ ->
                val groupId = input.text.toString()
                viewModel.joinGroup(groupId) { success ->
                    if (success) {
                        Toast.makeText(context, "Joined Group!", Toast.LENGTH_SHORT).show()
                        viewModel.loadGroupData(groupId)
                    } else {
                        Toast.makeText(context, "Failed to join", Toast.LENGTH_SHORT).show()
                    }
                }
            }
            .show()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
