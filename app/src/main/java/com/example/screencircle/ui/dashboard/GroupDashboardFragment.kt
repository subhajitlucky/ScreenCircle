package com.example.screencircle.ui.dashboard

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
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
import com.example.screencircle.R
import com.example.screencircle.data.local.PreferencesManager
import com.example.screencircle.databinding.FragmentDashboardBinding

class GroupDashboardFragment : Fragment() {

    private var _binding: FragmentDashboardBinding? = null
    private val binding get() = _binding!!
    private val viewModel: GroupViewModel by viewModels()
    private lateinit var adapter: GroupAdapter
    private lateinit var prefsManager: PreferencesManager

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

        prefsManager = PreferencesManager.getInstance(requireContext())

        adapter = GroupAdapter()
        binding.rvMembers.layoutManager = LinearLayoutManager(context)
        binding.rvMembers.adapter = adapter

        viewModel.groupMembers.observe(viewLifecycleOwner) { members ->
            if (members.isNotEmpty()) {
                // Sort by usage ASCENDING (Less time = Winner)
                val sortedMembers = members.sortedBy { it.todayUsage }
                adapter.submitList(sortedMembers)
                binding.tvEmptyState.visibility = View.GONE
                binding.rvMembers.visibility = View.VISIBLE
            } else {
                binding.tvEmptyState.visibility = View.VISIBLE
                binding.rvMembers.visibility = View.GONE
            }
        }

        viewModel.groupName.observe(viewLifecycleOwner) { name ->
            if (name != null) {
                binding.tvGroupTitle.text = name
                binding.tvGroupTitle.visibility = View.VISIBLE
                binding.groupInfoCard.visibility = View.VISIBLE
                prefsManager.currentGroupName = name
            }
        }

        binding.btnCreateGroup.setOnClickListener {
            showCreateGroupDialog()
        }

        binding.btnJoinGroup.setOnClickListener {
            showJoinGroupDialog()
        }

        binding.btnCopyId.setOnClickListener {
            val groupId = prefsManager.currentGroupId
            if (groupId != null) {
                copyToClipboard(groupId)
            }
        }
        
        // Click on group title to switch groups
        binding.tvGroupTitle.setOnClickListener {
            showSwitchGroupDialog()
        }

        // Load saved group if exists
        loadSavedGroup()
    }

    private fun loadSavedGroup() {
        val savedGroupId = prefsManager.currentGroupId
        val savedGroupName = prefsManager.currentGroupName
        
        if (savedGroupId != null) {
            binding.tvGroupTitle.text = "$savedGroupName ▼"  // Add dropdown indicator
            binding.tvGroupTitle.visibility = View.VISIBLE
            binding.tvGroupIdDisplay.text = savedGroupId
            binding.groupInfoCard.visibility = View.VISIBLE
            viewModel.loadGroupData(savedGroupId)
        } else {
            binding.groupInfoCard.visibility = View.GONE
            binding.tvGroupTitle.visibility = View.GONE
        }
    }
    
    private fun showSwitchGroupDialog() {
        val groups = prefsManager.getAllGroups()
        
        if (groups.isEmpty()) {
            Toast.makeText(context, "No groups yet. Create or join a group!", Toast.LENGTH_SHORT).show()
            return
        }
        
        val groupNames = groups.values.toTypedArray()
        val groupIds = groups.keys.toTypedArray()
        
        AlertDialog.Builder(requireContext())
            .setTitle("Switch Group")
            .setItems(groupNames) { _, which ->
                val selectedId = groupIds[which]
                val selectedName = groupNames[which]
                
                prefsManager.currentGroupId = selectedId
                prefsManager.currentGroupName = selectedName
                
                binding.tvGroupTitle.text = "$selectedName ▼"
                binding.tvGroupIdDisplay.text = selectedId
                viewModel.loadGroupData(selectedId)
                
                Toast.makeText(context, "Switched to $selectedName", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun copyToClipboard(text: String) {
        val clipboard = requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = ClipData.newPlainText("Group ID", text)
        clipboard.setPrimaryClip(clip)
        Toast.makeText(context, R.string.group_id_copied, Toast.LENGTH_SHORT).show()
    }

    private fun showCreateGroupDialog() {
        val input = EditText(context).apply {
            hint = "Enter group name"
            setPadding(48, 32, 48, 32)
        }
        
        AlertDialog.Builder(requireContext())
            .setTitle("Create Group")
            .setMessage("Choose a name for your group")
            .setView(input)
            .setPositiveButton("Create") { _, _ ->
                val name = input.text.toString().trim()
                if (name.isNotEmpty()) {
                    viewModel.createGroup(name) { groupId ->
                        if (groupId != null) {
                            prefsManager.currentGroupId = groupId
                            prefsManager.currentGroupName = name
                            prefsManager.addGroup(groupId, name)  // Save to groups list
                            binding.tvGroupIdDisplay.text = groupId
                            binding.tvGroupTitle.text = "$name ▼"
                            binding.tvGroupTitle.visibility = View.VISIBLE
                            binding.groupInfoCard.visibility = View.VISIBLE
                            Toast.makeText(context, "Group Created! Share the ID with friends.", Toast.LENGTH_LONG).show()
                            viewModel.loadGroupData(groupId)
                        } else {
                            Toast.makeText(context, "Failed to create group", Toast.LENGTH_SHORT).show()
                        }
                    }
                } else {
                    Toast.makeText(context, "Please enter a group name", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showJoinGroupDialog() {
        val input = EditText(context).apply {
            hint = "Enter Group ID"
            setPadding(48, 32, 48, 32)
        }
        
        AlertDialog.Builder(requireContext())
            .setTitle("Join Group")
            .setMessage("Enter the Group ID shared by your friend")
            .setView(input)
            .setPositiveButton("Join") { _, _ ->
                val groupId = input.text.toString().trim()
                if (groupId.isNotEmpty()) {
                    viewModel.joinGroup(groupId) { success, groupName ->
                        if (success) {
                            val finalName = groupName ?: "My Group"
                            prefsManager.currentGroupId = groupId
                            prefsManager.currentGroupName = finalName
                            prefsManager.addGroup(groupId, finalName)  // Save to groups list
                            binding.tvGroupIdDisplay.text = groupId
                            binding.tvGroupTitle.text = "$finalName ▼"
                            binding.tvGroupTitle.visibility = View.VISIBLE
                            binding.groupInfoCard.visibility = View.VISIBLE
                            Toast.makeText(context, "Joined Group!", Toast.LENGTH_SHORT).show()
                            viewModel.loadGroupData(groupId)
                        } else {
                            Toast.makeText(context, "Failed to join. Check the Group ID.", Toast.LENGTH_SHORT).show()
                        }
                    }
                } else {
                    Toast.makeText(context, "Please enter a Group ID", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
