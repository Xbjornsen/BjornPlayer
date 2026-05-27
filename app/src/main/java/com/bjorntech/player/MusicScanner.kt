package com.bjorntech.player

import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

object MusicScanner {

    suspend fun scanDevice(context: Context): List<Song> = withContext(Dispatchers.IO) {
        val songs = mutableListOf<Song>()

        val projection = arrayOf(
            MediaStore.Audio.Media._ID,
            MediaStore.Audio.Media.TITLE,
            MediaStore.Audio.Media.ARTIST,
            MediaStore.Audio.Media.ALBUM,
            MediaStore.Audio.Media.DURATION,
            MediaStore.Audio.Media.ALBUM_ID
        )

        val selection = "${MediaStore.Audio.Media.IS_MUSIC} != 0 AND ${MediaStore.Audio.Media.DURATION} > 10000"
        val sortOrder = "${MediaStore.Audio.Media.ARTIST} ASC, ${MediaStore.Audio.Media.ALBUM} ASC, ${MediaStore.Audio.Media.TRACK} ASC"

        context.contentResolver.query(
            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
            projection,
            selection,
            null,
            sortOrder
        )?.use { cursor ->
            val idCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
            val titleCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
            val artistCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
            val albumCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM)
            val durationCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)
            val albumIdCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM_ID)

            while (cursor.moveToNext()) {
                val id = cursor.getLong(idCol)
                val albumId = cursor.getLong(albumIdCol)
                val songUri = Uri.withAppendedPath(
                    MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, id.toString()
                )
                val albumArtUri = Uri.parse("content://media/external/audio/albumart/$albumId")

                songs.add(
                    Song(
                        id = id,
                        title = cursor.getString(titleCol) ?: "Unknown Title",
                        artist = cursor.getString(artistCol) ?: "Unknown Artist",
                        album = cursor.getString(albumCol) ?: "Unknown Album",
                        duration = cursor.getLong(durationCol),
                        uri = songUri,
                        albumArtUri = albumArtUri
                    )
                )
            }
        }

        songs.distinctBy { "${it.title.trim().lowercase()}|${it.artist.trim().lowercase()}" }
    }

    fun groupByArtist(songs: List<Song>): Map<String, List<Song>> {
        return songs.groupBy { it.artist }.toSortedMap()
    }

    fun groupByAlbum(songs: List<Song>): Map<String, List<Song>> {
        return songs.groupBy { it.album }.toSortedMap()
    }
}
