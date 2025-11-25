package com.example.screencircle.ui.settings

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.Fragment
import com.example.screencircle.R
import com.example.screencircle.data.local.PreferencesManager
import com.example.screencircle.databinding.FragmentSettingsBinding
import com.example.screencircle.ui.login.LoginActivity
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ValueEventListener

class SettingsFragment : Fragment() {

    private var _binding: FragmentSettingsBinding? = null
    private val binding get() = _binding!!
    private lateinit var prefsManager: PreferencesManager
    private val auth = FirebaseAuth.getInstance()

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentSettingsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        
        prefsManager = PreferencesManager.getInstance(requireContext())
        
        setupUserProfile()
        setupGroupInfo()
        setupButtons()
    }

    private fun setupUserProfile() {
        val user = auth.currentUser
        if (user != null) {
            binding.tvUserEmail.text = user.email ?: "No email"
            
            // Load user name from Firebase
            val database = FirebaseDatabase.getInstance().reference
            database.child("users").child(user.uid).child("profile")
                .addListenerForSingleValueEvent(object : ValueEventListener {
                    override fun onDataChange(snapshot: DataSnapshot) {
                        val name = snapshot.child("name").getValue(String::class.java)
                        binding.tvUserName.text = name ?: user.email?.substringBefore("@") ?: "User"
                        prefsManager.userName = name
                        prefsManager.userEmail = user.email
                    }

                    override fun onCancelled(error: DatabaseError) {
                        binding.tvUserName.text = user.email?.substringBefore("@") ?: "User"
                    }
                })
        }
    }

    private fun setupGroupInfo() {
        val groupId = prefsManager.currentGroupId
        val groupName = prefsManager.currentGroupName
        
        if (groupId != null) {
            binding.cardGroup.visibility = View.VISIBLE
            binding.tvNoGroup.visibility = View.GONE
            binding.tvGroupName.text = groupName ?: "My Group"
            binding.tvGroupId.text = groupId
            
            binding.btnCopyGroupId.setOnClickListener {
                copyToClipboard(groupId)
            }
            
            binding.btnLeaveGroup.setOnClickListener {
                leaveGroup()
            }
        } else {
            binding.cardGroup.visibility = View.GONE
            binding.tvNoGroup.visibility = View.VISIBLE
        }
    }

    private fun copyToClipboard(text: String) {
        val clipboard = requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = ClipData.newPlainText("Group ID", text)
        clipboard.setPrimaryClip(clip)
        Toast.makeText(context, R.string.group_id_copied, Toast.LENGTH_SHORT).show()
    }

    private fun leaveGroup() {
        val userId = auth.currentUser?.uid ?: return
        val groupId = prefsManager.currentGroupId ?: return
        
        val database = FirebaseDatabase.getInstance().reference
        
        // Remove user from group
        database.child("groups").child(groupId).child("members").child(userId).removeValue()
        // Remove group from user
        database.child("users").child(userId).child("groups").child(groupId).removeValue()
        
        prefsManager.clearGroupData()
        setupGroupInfo()
        
        Toast.makeText(context, "Left the group", Toast.LENGTH_SHORT).show()
    }

    private fun setupButtons() {
        binding.btnLogout.setOnClickListener {
            prefsManager.clearAll()
            FirebaseAuth.getInstance().signOut()
            startActivity(Intent(requireContext(), LoginActivity::class.java))
            requireActivity().finish()
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
