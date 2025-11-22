package com.example.screencircle.ui.dashboard

import androidx.lifecycle.LiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.screencircle.data.repository.GroupMember
import com.example.screencircle.data.repository.GroupRepository
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class GroupViewModel : ViewModel() {

    private val repository = GroupRepository()
    val groupMembers: LiveData<List<GroupMember>> = repository.groupMembers

    fun loadGroupData(groupId: String) {
        val today = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date())
        repository.listenToGroupUsage(groupId, today)
    }

    fun createGroup(name: String, onResult: (String?) -> Unit) {
        viewModelScope.launch {
            val groupId = repository.createGroup(name)
            onResult(groupId)
        }
    }

    fun joinGroup(groupId: String, onResult: (Boolean) -> Unit) {
        viewModelScope.launch {
            val success = repository.joinGroup(groupId)
            onResult(success)
        }
    }
}
