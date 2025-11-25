package com.example.screencircle.ui.dashboard

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
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
    
    private val _groupName = MutableLiveData<String?>()
    val groupName: LiveData<String?> = _groupName

    fun loadGroupData(groupId: String) {
        val today = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date())
        repository.listenToGroupUsage(groupId, today)
        
        // Also fetch group name
        viewModelScope.launch {
            val name = repository.getGroupName(groupId)
            _groupName.postValue(name)
        }
    }

    fun createGroup(name: String, onResult: (String?) -> Unit) {
        viewModelScope.launch {
            val groupId = repository.createGroup(name)
            if (groupId != null) {
                _groupName.postValue(name)
            }
            onResult(groupId)
        }
    }

    fun joinGroup(groupId: String, onResult: (Boolean, String?) -> Unit) {
        viewModelScope.launch {
            val result = repository.joinGroup(groupId)
            if (result) {
                val name = repository.getGroupName(groupId)
                _groupName.postValue(name)
                onResult(true, name)
            } else {
                onResult(false, null)
            }
        }
    }
}
