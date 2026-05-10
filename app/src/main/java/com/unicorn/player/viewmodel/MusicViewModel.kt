package com.unicorn.player.viewmodel

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.unicorn.player.model.Song
import com.unicorn.player.repository.MusicRepository
import kotlinx.coroutines.launch

class MusicViewModel(private val repository: MusicRepository) : ViewModel() {

    private val _allSongs = MutableLiveData<List<Song>>(emptyList())
    val allSongs: LiveData<List<Song>> = _allSongs

    private val _isLoading = MutableLiveData(false)
    val isLoading: LiveData<Boolean> = _isLoading

    init {
        loadMusic()
        collectSongs()
    }

    private fun collectSongs() {
        viewModelScope.launch {
            repository.getAllSongs().collect { songs ->
                _allSongs.postValue(songs)
            }
        }
    }

    fun loadMusic() {
        viewModelScope.launch {
            _isLoading.postValue(true)
            try {
                repository.scanMusicFiles()
            } catch (e: Exception) {
                e.printStackTrace()
            } finally {
                _isLoading.postValue(false)
            }
        }
    }

    fun searchSongs(query: String) {
        viewModelScope.launch {
            if (query.isBlank()) {
                repository.getAllSongs().collect { songs ->
                    _allSongs.postValue(songs)
                }
            } else {
                repository.searchSongs(query).collect { songs ->
                    _allSongs.postValue(songs)
                }
            }
        }
    }
}