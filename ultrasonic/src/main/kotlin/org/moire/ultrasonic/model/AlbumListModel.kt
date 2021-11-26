package org.moire.ultrasonic.model

import android.app.Application
import android.os.Bundle
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import org.moire.ultrasonic.api.subsonic.models.AlbumListType
import org.moire.ultrasonic.domain.MusicDirectory
import org.moire.ultrasonic.service.MusicService
import org.moire.ultrasonic.util.Constants
import org.moire.ultrasonic.util.Settings

class AlbumListModel(application: Application) : GenericListModel(application) {

    val list: MutableLiveData<List<MusicDirectory.Album>> = MutableLiveData(listOf())
    var lastType: String? = null
    private var loadedUntil: Int = 0

    fun getAlbumList(
        refresh: Boolean,
        swipe: SwipeRefreshLayout,
        args: Bundle
    ): LiveData<List<MusicDirectory.Album>> {
        // Don't reload the data if navigating back to the view that was active before.
        // This way, we keep the scroll position
        val albumListType = args.getString(Constants.INTENT_EXTRA_NAME_ALBUM_LIST_TYPE)!!

        if (refresh || list.value!!.isEmpty() || albumListType != lastType) {
            lastType = albumListType
            backgroundLoadFromServer(refresh, swipe, args)
        }
        return list
    }

    fun getAlbumsOfArtist(musicService: MusicService, refresh: Boolean, id: String, name: String?) {
        list.postValue(musicService.getArtist(id, name, refresh))
    }

    override fun load(
        isOffline: Boolean,
        useId3Tags: Boolean,
        musicService: MusicService,
        refresh: Boolean,
        args: Bundle
    ) {
        super.load(isOffline, useId3Tags, musicService, refresh, args)

        val albumListType = args.getString(Constants.INTENT_EXTRA_NAME_ALBUM_LIST_TYPE)!!
        val size = args.getInt(Constants.INTENT_EXTRA_NAME_ALBUM_LIST_SIZE, 0)
        var offset = args.getInt(Constants.INTENT_EXTRA_NAME_ALBUM_LIST_OFFSET, 0)
        val append = args.getBoolean(Constants.INTENT_EXTRA_NAME_APPEND, false)

        val musicDirectory: MusicDirectory
        val musicFolderId = if (showSelectFolderHeader(args)) {
            activeServerProvider.getActiveServer().musicFolderId
        } else {
            null
        }

        // Handle the logic for endless scrolling:
        // If appending the existing list, set the offset from where to load
        if (append) offset += (size + loadedUntil)

        if (albumListType == Constants.ALBUMS_OF_ARTIST) {
            return getAlbumsOfArtist(
                musicService,
                refresh,
                args.getString(Constants.INTENT_EXTRA_NAME_ID, ""),
                args.getString(Constants.INTENT_EXTRA_NAME_NAME, "")
            )
        }

        if (useId3Tags) {
            musicDirectory = musicService.getAlbumList2(
                albumListType, size,
                offset, musicFolderId
            )
        } else {
            musicDirectory = musicService.getAlbumList(
                albumListType, size,
                offset, musicFolderId
            )
        }

        currentListIsSortable = isCollectionSortable(albumListType)

        // TODO: Change signature of  musicService.getAlbumList to return a List
        @Suppress("UNCHECKED_CAST")
        if (append && list.value != null) {
            val list = ArrayList<MusicDirectory.Child>()
            list.addAll(this.list.value!!)
            list.addAll(musicDirectory.getChildren())
            this.list.postValue(list as List<MusicDirectory.Album>)
        } else {
            list.postValue(musicDirectory.getChildren() as List<MusicDirectory.Album>)
        }

        loadedUntil = offset
    }

    override fun showSelectFolderHeader(args: Bundle?): Boolean {
        if (args == null) return false

        val albumListType = args.getString(Constants.INTENT_EXTRA_NAME_ALBUM_LIST_TYPE)!!

        val isAlphabetical = (albumListType == AlbumListType.SORTED_BY_NAME.toString()) ||
            (albumListType == AlbumListType.SORTED_BY_ARTIST.toString())

        return !isOffline() && !Settings.shouldUseId3Tags && isAlphabetical
    }

    private fun isCollectionSortable(albumListType: String): Boolean {
        return albumListType != "newest" && albumListType != "random" &&
            albumListType != "highest" && albumListType != "recent" &&
            albumListType != "frequent"
    }
}
