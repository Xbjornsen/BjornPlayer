package com.bjorntech.player

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.launch

enum class SortOrder { RANDOM, TITLE, ARTIST, DURATION }

class PlayerViewModel(application: Application) : AndroidViewModel(application) {

    private val _songs = MutableLiveData<List<Song>>(emptyList())
    val songs: LiveData<List<Song>> = _songs

    private val _isLoading = MutableLiveData(true)
    val isLoading: LiveData<Boolean> = _isLoading

    private val _currentSong = MutableLiveData<Song?>()
    val currentSong: LiveData<Song?> = _currentSong

    private val _currentTab = MutableLiveData(0)
    val currentTab: LiveData<Int> = _currentTab

    private val _searchQuery = MutableLiveData("")
    val searchQuery: LiveData<String> = _searchQuery

    private val _sortOrder = MutableLiveData(SortOrder.RANDOM)
    val sortOrder: LiveData<SortOrder> = _sortOrder

    fun loadMusic() {
        viewModelScope.launch {
            _isLoading.value = true
            val scanned = MusicScanner.scanDevice(getApplication())
            _songs.value = scanned.shuffled()   // fresh random order every session
            _isLoading.value = false
        }
    }

    fun setCurrentSong(song: Song) {
        _currentSong.value = song
    }

    fun setTab(tab: Int) {
        _currentTab.value = tab
    }

    fun setSearchQuery(query: String) {
        _searchQuery.value = query
    }

    fun setSortOrder(order: SortOrder) {
        _sortOrder.value = order
    }

    fun filteredSongs(): List<Song> {
        val query = _searchQuery.value?.lowercase() ?: ""
        val all = _songs.value ?: emptyList()
        val filtered = if (query.isEmpty()) all
        else all.filter {
            it.title.lowercase().contains(query) ||
            it.artist.lowercase().contains(query) ||
            it.album.lowercase().contains(query)
        }
        return when (_sortOrder.value) {
            SortOrder.ARTIST   -> filtered.sortedWith(compareBy({ it.artist.lowercase() }, { it.title.lowercase() }))
            SortOrder.DURATION -> filtered.sortedBy { it.duration }
            SortOrder.TITLE    -> filtered.sortedBy { it.title.lowercase() }
            else               -> filtered   // RANDOM: keep the shuffled storage order
        }
    }
}
