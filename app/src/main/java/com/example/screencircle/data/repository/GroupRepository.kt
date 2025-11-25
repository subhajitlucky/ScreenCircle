package com.example.screencircle.data.repository

import android.util.Log
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ValueEventListener
import kotlinx.coroutines.tasks.await

data class GroupMember(
    val userId: String = "",
    val name: String = "",
    val email: String = "",
    val todayUsage: Long = 0
)

class GroupRepository {
    private val database = FirebaseDatabase.getInstance().reference
    private val auth = FirebaseAuth.getInstance()

    private val _groupMembers = MutableLiveData<List<GroupMember>>()
    val groupMembers: LiveData<List<GroupMember>> = _groupMembers

    suspend fun createGroup(groupName: String): String? {
        val userId = auth.currentUser?.uid ?: return null
        val groupId = database.child("groups").push().key ?: return null
        
        val groupData = mapOf(
            "name" to groupName,
            "owner" to userId,
            "createdAt" to System.currentTimeMillis(),
            "members" to mapOf(userId to true)
        )

        try {
            database.child("groups").child(groupId).setValue(groupData).await()
            // Add group to user's group list
            database.child("users").child(userId).child("groups").child(groupId).setValue(true).await()
            return groupId
        } catch (e: Exception) {
            Log.e("GroupRepository", "Error creating group", e)
            return null
        }
    }

    suspend fun joinGroup(groupId: String): Boolean {
        val userId = auth.currentUser?.uid ?: return false
        try {
            // Check if group exists
            val snapshot = database.child("groups").child(groupId).get().await()
            if (!snapshot.exists()) return false

            // Add user to group
            database.child("groups").child(groupId).child("members").child(userId).setValue(true).await()
            // Add group to user
            database.child("users").child(userId).child("groups").child(groupId).setValue(true).await()
            return true
        } catch (e: Exception) {
            Log.e("GroupRepository", "Error joining group", e)
            return false
        }
    }

    suspend fun getGroupName(groupId: String): String? {
        return try {
            val snapshot = database.child("groups").child(groupId).child("name").get().await()
            snapshot.getValue(String::class.java)
        } catch (e: Exception) {
            Log.e("GroupRepository", "Error getting group name", e)
            null
        }
    }

    fun listenToGroupUsage(groupId: String, date: String) {
        database.child("groups").child(groupId).child("members")
            .addValueEventListener(object : ValueEventListener {
                override fun onDataChange(snapshot: DataSnapshot) {
                    val memberIds = mutableListOf<String>()
                    for (child in snapshot.children) {
                        child.key?.let { memberIds.add(it) }
                    }
                    
                    if (memberIds.isEmpty()) {
                        _groupMembers.postValue(emptyList())
                        return
                    }
                    
                    fetchAllMembersUsage(memberIds, date)
                }

                override fun onCancelled(error: DatabaseError) {
                    Log.e("GroupRepository", "Error listening to group", error.toException())
                }
            })
    }

    private fun fetchAllMembersUsage(memberIds: List<String>, date: String) {
        val members = mutableListOf<GroupMember>()
        var completedCount = 0
        
        for (memberId in memberIds) {
            database.child("users").child(memberId).addListenerForSingleValueEvent(object : ValueEventListener {
                override fun onDataChange(snapshot: DataSnapshot) {
                    val name = snapshot.child("profile").child("name").getValue(String::class.java) ?: "Unknown"
                    val email = snapshot.child("profile").child("email").getValue(String::class.java) ?: ""
                    val usage = snapshot.child("usage").child(date).getValue(Long::class.java) ?: 0L
                    
                    synchronized(members) {
                        members.add(GroupMember(memberId, name, email, usage))
                        completedCount++
                        
                        if (completedCount == memberIds.size) {
                            _groupMembers.postValue(members.toList())
                        }
                    }
                }

                override fun onCancelled(error: DatabaseError) {
                    synchronized(members) {
                        completedCount++
                        if (completedCount == memberIds.size) {
                            _groupMembers.postValue(members.toList())
                        }
                    }
                }
            })
        }
    }
}
