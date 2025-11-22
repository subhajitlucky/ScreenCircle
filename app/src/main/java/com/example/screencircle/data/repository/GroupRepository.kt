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

    fun listenToGroupUsage(groupId: String, date: String) {
        database.child("groups").child(groupId).child("members")
            .addValueEventListener(object : ValueEventListener {
                override fun onDataChange(snapshot: DataSnapshot) {
                    val members = mutableListOf<GroupMember>()
                    for (child in snapshot.children) {
                        val memberId = child.key ?: continue
                        fetchMemberUsage(memberId, date) { member ->
                            members.add(member)
                            _groupMembers.postValue(members)
                        }
                    }
                }

                override fun onCancelled(error: DatabaseError) {
                    Log.e("GroupRepository", "Error listening to group", error.toException())
                }
            })
    }

    private fun fetchMemberUsage(userId: String, date: String, onResult: (GroupMember) -> Unit) {
        database.child("users").child(userId).addListenerForSingleValueEvent(object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                val name = snapshot.child("profile").child("name").getValue(String::class.java) ?: "Unknown"
                val email = snapshot.child("profile").child("email").getValue(String::class.java) ?: ""
                val usage = snapshot.child("usage").child(date).getValue(Long::class.java) ?: 0L
                
                onResult(GroupMember(userId, name, email, usage))
            }

            override fun onCancelled(error: DatabaseError) {}
        })
    }
}
