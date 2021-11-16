/*
 * TrackCollectionFragment.kt
 * Copyright (C) 2009-2021 Ultrasonic developers
 *
 * Distributed under terms of the GNU GPLv3 license.
 */

package org.moire.ultrasonic.fragment

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Menu
import android.view.MenuInflater
import android.view.MenuItem
import android.view.View
import android.widget.AdapterView.AdapterContextMenuInfo
import android.widget.ImageView
import android.widget.TextView
import androidx.core.view.isVisible
import androidx.fragment.app.viewModels
import androidx.lifecycle.LiveData
import androidx.lifecycle.Observer
import androidx.lifecycle.viewModelScope
import androidx.navigation.Navigation
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import java.util.Collections
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.launch
import org.koin.android.ext.android.inject
import org.moire.ultrasonic.R
import org.moire.ultrasonic.adapters.HeaderViewBinder
import org.moire.ultrasonic.adapters.TrackViewBinder
import org.moire.ultrasonic.data.ActiveServerProvider.Companion.isOffline
import org.moire.ultrasonic.domain.Identifiable
import org.moire.ultrasonic.domain.MusicDirectory
import org.moire.ultrasonic.fragment.FragmentTitle.Companion.setTitle
import org.moire.ultrasonic.service.MediaPlayerController
import org.moire.ultrasonic.subsonic.NetworkAndStorageChecker
import org.moire.ultrasonic.subsonic.ShareHandler
import org.moire.ultrasonic.util.AlbumHeader
import org.moire.ultrasonic.util.CancellationToken
import org.moire.ultrasonic.util.CommunicationError
import org.moire.ultrasonic.util.Constants
import org.moire.ultrasonic.util.EntryByDiscAndTrackComparator
import org.moire.ultrasonic.util.Settings
import org.moire.ultrasonic.util.Util
import timber.log.Timber

/**
 * Displays a group of tracks, eg. the songs of an album, of a playlist etc.
 * TODO: Move Clickhandler into ViewBinders
 * TODO: Fix clikc handlers and context menus etc.
 */
class TrackCollectionFragment :
    MultiListFragment<MusicDirectory.Entry>() {

    private var albumButtons: View? = null
    private var emptyView: TextView? = null
    private var selectButton: ImageView? = null
    private var playNowButton: ImageView? = null
    private var playNextButton: ImageView? = null
    private var playLastButton: ImageView? = null
    private var pinButton: ImageView? = null
    private var unpinButton: ImageView? = null
    private var downloadButton: ImageView? = null
    private var deleteButton: ImageView? = null
    private var moreButton: ImageView? = null
    private var playAllButtonVisible = false
    private var shareButtonVisible = false
    private var playAllButton: MenuItem? = null
    private var shareButton: MenuItem? = null

    private val mediaPlayerController: MediaPlayerController by inject()
    private val networkAndStorageChecker: NetworkAndStorageChecker by inject()
    private val shareHandler: ShareHandler by inject()
    private var cancellationToken: CancellationToken? = null

    override val listModel: TrackCollectionModel by viewModels()

    /**
     * The id of the main layout
     */
    override val mainLayout: Int = R.layout.track_list

    /**
     * The id of the refresh view
     */
    override val refreshListId: Int = R.id.generic_list_refresh

    /**
     * The id of the RecyclerView
     */
    override val recyclerViewId = R.id.generic_list_recycler

    /**
     * The id of the target in the navigation graph where we should go,
     * after the user has clicked on an item
     */
    // FIXME
    override val itemClickTarget: Int = R.id.trackCollectionFragment

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        cancellationToken = CancellationToken()

        albumButtons = view.findViewById(R.id.menu_album)

        // Setup refresh handler
        refreshListView = view.findViewById(refreshListId)
        refreshListView?.setOnRefreshListener {
            updateDisplay(true)
        }

        listModel.currentList.observe(viewLifecycleOwner, updateInterfaceWithEntries)
        listModel.songsForGenre.observe(viewLifecycleOwner, songsForGenreObserver)

//        listView!!.setOnItemClickListener { parent, theView, position, _ ->
//            if (position >= 0) {
//                val entry = parent.getItemAtPosition(position) as MusicDirectory.Entry?
//                if (entry != null && entry.isDirectory) {
//                    val bundle = Bundle()
//                    bundle.putString(Constants.INTENT_EXTRA_NAME_ID, entry.id)
//                    bundle.putBoolean(Constants.INTENT_EXTRA_NAME_IS_ALBUM, entry.isDirectory)
//                    bundle.putString(Constants.INTENT_EXTRA_NAME_NAME, entry.title)
//                    bundle.putString(Constants.INTENT_EXTRA_NAME_PARENT_ID, entry.parent)
//                    Navigation.findNavController(theView).navigate(
//                        R.id.trackCollectionFragment,
//                        bundle
//                    )
//                } else if (entry != null && entry.isVideo) {
//                    VideoPlayer.playVideo(requireContext(), entry)
//                } else {
//                    enableButtons()
//                }
//            }
//        }
//
//        listView!!.setOnItemLongClickListener { _, theView, _, _ ->
//            if (theView is AlbumView) {
//                return@setOnItemLongClickListener false
//            }
//            if (theView is SongView) {
//                theView.maximizeOrMinimize()
//                return@setOnItemLongClickListener true
//            }
//            return@setOnItemLongClickListener false
//        }

        selectButton = view.findViewById(R.id.select_album_select)
        playNowButton = view.findViewById(R.id.select_album_play_now)
        playNextButton = view.findViewById(R.id.select_album_play_next)
        playLastButton = view.findViewById(R.id.select_album_play_last)
        pinButton = view.findViewById(R.id.select_album_pin)
        unpinButton = view.findViewById(R.id.select_album_unpin)
        downloadButton = view.findViewById(R.id.select_album_download)
        deleteButton = view.findViewById(R.id.select_album_delete)
        moreButton = view.findViewById(R.id.select_album_more)
        emptyView = TextView(requireContext())

        selectButton!!.setOnClickListener {
            selectAllOrNone()
        }

        playNowButton!!.setOnClickListener {
            playNow(false)
        }

        playNextButton!!.setOnClickListener {
            downloadHandler.download(
                this@TrackCollectionFragment, append = true,
                save = false, autoPlay = false, playNext = true, shuffle = false,
                songs = getSelectedSongs()
            )
        }

        playLastButton!!.setOnClickListener {
            playNow(true)
        }

        pinButton!!.setOnClickListener {
            downloadBackground(true)
        }

        unpinButton!!.setOnClickListener {
            unpin()
        }

        downloadButton!!.setOnClickListener {
            downloadBackground(false)
        }

        deleteButton!!.setOnClickListener {
            delete()
        }

        registerForContextMenu(listView!!)
        setHasOptionsMenu(true)

        // Create a View Manager
        viewManager = LinearLayoutManager(this.context)

        // Hook up the view with the manager and the adapter
        listView = view.findViewById<RecyclerView>(recyclerViewId).apply {
            setHasFixedSize(true)
            layoutManager = viewManager
            adapter = viewAdapter
        }

        viewAdapter.register(
            HeaderViewBinder(
                context = requireContext()
            )
        )

        viewAdapter.register(
            TrackViewBinder(
                checkable = true,
                draggable = false,
                context = requireContext(),
                lifecycleOwner = viewLifecycleOwner
            )
        )

        enableButtons()

        // Update the buttons when the selection has changed
        viewAdapter.selectionRevision.observe(
            viewLifecycleOwner,
            {
                enableButtons()
            }
        )

        // Loads the data
        updateDisplay(false)
    }

    val handler = CoroutineExceptionHandler { _, exception ->
        Handler(Looper.getMainLooper()).post {
            CommunicationError.handleError(exception, context)
        }
        refreshListView!!.isRefreshing = false
    }

    private fun updateDisplay(refresh: Boolean) {
        // FIXME: Use refresh
        getLiveData(requireArguments())
    }

    override fun onContextItemSelected(menuItem: MenuItem): Boolean {
        Timber.d("onContextItemSelected")
        val info = menuItem.menuInfo as AdapterContextMenuInfo? ?: return true

        val entry = viewAdapter.getCurrentList()[info.position] as MusicDirectory.Entry?
            ?: return true

        val entryId = entry.id

        when (menuItem.itemId) {
            R.id.menu_play_now -> {
                downloadHandler.downloadRecursively(
                    this, entryId, save = false, append = false,
                    autoPlay = true, shuffle = false, background = false,
                    playNext = false, unpin = false, isArtist = false
                )
            }
            R.id.menu_play_next -> {
                downloadHandler.downloadRecursively(
                    this, entryId, save = false, append = false,
                    autoPlay = false, shuffle = false, background = false,
                    playNext = true, unpin = false, isArtist = false
                )
            }
            R.id.menu_play_last -> {
                downloadHandler.downloadRecursively(
                    this, entryId, save = false, append = true,
                    autoPlay = false, shuffle = false, background = false,
                    playNext = false, unpin = false, isArtist = false
                )
            }
            R.id.menu_pin -> {
                downloadHandler.downloadRecursively(
                    this, entryId, save = true, append = true,
                    autoPlay = false, shuffle = false, background = false,
                    playNext = false, unpin = false, isArtist = false
                )
            }
            R.id.menu_unpin -> {
                downloadHandler.downloadRecursively(
                    this, entryId, save = false, append = false,
                    autoPlay = false, shuffle = false, background = false,
                    playNext = false, unpin = true, isArtist = false
                )
            }
            R.id.menu_download -> {
                downloadHandler.downloadRecursively(
                    this, entryId, save = false, append = false,
                    autoPlay = false, shuffle = false, background = true,
                    playNext = false, unpin = false, isArtist = false
                )
            }
            R.id.select_album_play_all -> {
                // TODO: Why is this being handled here?!
                playAll()
            }
            R.id.menu_item_share -> {
                val entries: MutableList<MusicDirectory.Entry?> = ArrayList(1)
                entries.add(entry)
                shareHandler.createShare(
                    this, entries, refreshListView,
                    cancellationToken!!
                )
                return true
            }
            else -> {
                return super.onContextItemSelected(menuItem)
            }
        }
        return true
    }

    override fun onPrepareOptionsMenu(menu: Menu) {
        super.onPrepareOptionsMenu(menu)
        playAllButton = menu.findItem(R.id.select_album_play_all)

        if (playAllButton != null) {
            playAllButton!!.isVisible = playAllButtonVisible
        }

        shareButton = menu.findItem(R.id.menu_item_share)

        if (shareButton != null) {
            shareButton!!.isVisible = shareButtonVisible
        }
    }

    override fun onCreateOptionsMenu(menu: Menu, inflater: MenuInflater) {
        inflater.inflate(R.menu.select_album, menu)
        super.onCreateOptionsMenu(menu, inflater)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        val itemId = item.itemId
        if (itemId == R.id.select_album_play_all) {
            playAll()
            return true
        } else if (itemId == R.id.menu_item_share) {
            shareHandler.createShare(
                this, getSelectedSongs(),
                refreshListView, cancellationToken!!
            )
            return true
        }

        return false
    }

    override fun onDestroyView() {
        cancellationToken!!.cancel()
        super.onDestroyView()
    }

    private fun playNow(append: Boolean) {
        val selectedSongs = getSelectedSongs()

        if (selectedSongs.isNotEmpty()) {
            downloadHandler.download(
                this, append, false, !append, playNext = false,
                shuffle = false, songs = selectedSongs
            )
            selectAll(selected = false, toast = false)
        } else {
            playAll(false, append)
        }
    }

    /**
     * Get the size of the underlying list
     */
    private val childCount: Int
        get() {
            val count = viewAdapter.getCurrentList().count()
            if (listModel.showHeader) {
                return count - 1
            } else {
                return count
            }
        }

    private fun playAll(shuffle: Boolean = false, append: Boolean = false) {
        var hasSubFolders = false

        for (item in viewAdapter.getCurrentList()) {
            if (item is MusicDirectory.Entry && item.isDirectory) {
                hasSubFolders = true
                break
            }
        }

        val isArtist = requireArguments().getBoolean(Constants.INTENT_EXTRA_NAME_ARTIST, false)
        val id = requireArguments().getString(Constants.INTENT_EXTRA_NAME_ID)

        if (hasSubFolders && id != null) {
            downloadHandler.downloadRecursively(
                fragment = this,
                id = id,
                save = false,
                append = append,
                autoPlay = !append,
                shuffle = shuffle,
                background = false,
                playNext = false,
                unpin = false,
                isArtist = isArtist
            )
        } else {
            downloadHandler.download(
                fragment = this,
                append = append,
                save = false,
                autoPlay = !append,
                playNext = false,
                shuffle = shuffle,
                songs = getAllSongs()
            )
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun getAllSongs(): List<MusicDirectory.Entry> {
        return viewAdapter.getCurrentList().filter {
            it is MusicDirectory.Entry && !it.isDirectory
        } as List<MusicDirectory.Entry>
    }

    private fun selectAllOrNone() {
        val someUnselected = viewAdapter.selectedSet.size < childCount

        selectAll(someUnselected, true)
    }

    private fun selectAll(selected: Boolean, toast: Boolean) {
        var selectedCount = viewAdapter.selectedSet.size * -1

        selectedCount += viewAdapter.setSelectionStatusOfAll(selected)

        // Display toast: N tracks selected
        if (toast) {
            val toastResId = R.string.select_album_n_selected
            Util.toast(activity, getString(toastResId, selectedCount.coerceAtLeast(0)))
        }
    }

    private fun enableButtons(selection: List<MusicDirectory.Entry> = getSelectedSongs()) {
        val enabled = selection.isNotEmpty()
        var unpinEnabled = false
        var deleteEnabled = false

        var pinnedCount = 0

        for (song in selection) {
            val downloadFile = mediaPlayerController.getDownloadFileForSong(song)
            if (downloadFile.isWorkDone) {
                deleteEnabled = true
            }
            if (downloadFile.isSaved) {
                pinnedCount++
                unpinEnabled = true
            }
        }

        playNowButton?.isVisible = enabled
        playNextButton?.isVisible = enabled
        playLastButton?.isVisible = enabled
        pinButton?.isVisible = (enabled && !isOffline() && selection.size > pinnedCount)
        unpinButton?.isVisible = (enabled && unpinEnabled)
        downloadButton?.isVisible = (enabled && !deleteEnabled && !isOffline())
        deleteButton?.isVisible = (enabled && deleteEnabled)
    }

    private fun downloadBackground(save: Boolean) {
        var songs = getSelectedSongs()

        if (songs.isEmpty()) {
            songs = getAllSongs()
        }

        downloadBackground(save, songs)
    }

    private fun downloadBackground(save: Boolean, songs: List<MusicDirectory.Entry?>) {
        val onValid = Runnable {
            networkAndStorageChecker.warnIfNetworkOrStorageUnavailable()
            mediaPlayerController.downloadBackground(songs, save)

            if (save) {
                Util.toast(
                    context,
                    resources.getQuantityString(
                        R.plurals.select_album_n_songs_pinned, songs.size, songs.size
                    )
                )
            } else {
                Util.toast(
                    context,
                    resources.getQuantityString(
                        R.plurals.select_album_n_songs_downloaded, songs.size, songs.size
                    )
                )
            }
        }
        onValid.run()
    }

    private fun delete() {
        val songs = getSelectedSongs()

        Util.toast(
            context,
            resources.getQuantityString(
                R.plurals.select_album_n_songs_deleted, songs.size, songs.size
            )
        )

        mediaPlayerController.delete(songs)
    }

    private fun unpin() {
        val songs = getSelectedSongs()
        Util.toast(
            context,
            resources.getQuantityString(
                R.plurals.select_album_n_songs_unpinned, songs.size, songs.size
            )
        )
        mediaPlayerController.unpin(songs)
    }

    private val songsForGenreObserver = Observer<MusicDirectory> { musicDirectory ->

        // Hide more button when results are less than album list size
        if (musicDirectory.getChildren().size < requireArguments().getInt(
            Constants.INTENT_EXTRA_NAME_ALBUM_LIST_SIZE, 0
        )
        ) {
            moreButton!!.visibility = View.GONE
        } else {
            moreButton!!.visibility = View.VISIBLE
        }

        moreButton!!.setOnClickListener {
            val theGenre = requireArguments().getString(Constants.INTENT_EXTRA_NAME_GENRE_NAME)
            val size = requireArguments().getInt(Constants.INTENT_EXTRA_NAME_ALBUM_LIST_SIZE, 0)
            val theOffset = requireArguments().getInt(
                Constants.INTENT_EXTRA_NAME_ALBUM_LIST_OFFSET, 0
            ) + size
            val bundle = Bundle()
            bundle.putString(Constants.INTENT_EXTRA_NAME_GENRE_NAME, theGenre)
            bundle.putInt(Constants.INTENT_EXTRA_NAME_ALBUM_LIST_SIZE, size)
            bundle.putInt(Constants.INTENT_EXTRA_NAME_ALBUM_LIST_OFFSET, theOffset)

            Navigation.findNavController(requireView())
                .navigate(R.id.trackCollectionFragment, bundle)
        }
    }

    private val updateInterfaceWithEntries = Observer<List<MusicDirectory.Entry>> {

        val entryList: MutableList<MusicDirectory.Entry> = it.toMutableList()

        if (listModel.currentListIsSortable && Settings.shouldSortByDisc) {
            Collections.sort(entryList, EntryByDiscAndTrackComparator())
        }

        var allVideos = true
        var songCount = 0

        for (entry in entryList) {
            if (!entry.isVideo) {
                allVideos = false
            }
            if (!entry.isDirectory) {
                songCount++
            }
        }

        val listSize = requireArguments().getInt(Constants.INTENT_EXTRA_NAME_ALBUM_LIST_SIZE, 0)

        if (songCount > 0) {
            pinButton!!.visibility = View.VISIBLE
            unpinButton!!.visibility = View.VISIBLE
            downloadButton!!.visibility = View.VISIBLE
            deleteButton!!.visibility = View.VISIBLE
            selectButton!!.visibility = if (allVideos) View.GONE else View.VISIBLE
            playNowButton!!.visibility = View.VISIBLE
            playNextButton!!.visibility = View.VISIBLE
            playLastButton!!.visibility = View.VISIBLE

            if (listSize == 0 || songCount < listSize) {
                moreButton!!.visibility = View.GONE
            } else {
                moreButton!!.visibility = View.VISIBLE
                if (requireArguments().getInt(Constants.INTENT_EXTRA_NAME_RANDOM, 0) > 0) {
                    moreButton!!.setOnClickListener {
                        val offset = requireArguments().getInt(
                            Constants.INTENT_EXTRA_NAME_ALBUM_LIST_OFFSET, 0
                        ) + listSize
                        val bundle = Bundle()
                        bundle.putInt(Constants.INTENT_EXTRA_NAME_RANDOM, 1)
                        bundle.putInt(Constants.INTENT_EXTRA_NAME_ALBUM_LIST_SIZE, listSize)
                        bundle.putInt(Constants.INTENT_EXTRA_NAME_ALBUM_LIST_OFFSET, offset)
                        Navigation.findNavController(requireView()).navigate(
                            R.id.trackCollectionFragment, bundle
                        )
                    }
                }
            }
        } else {

            // TODO: This code path can be removed when getArtist has been moved to
            // AlbumListFragment (getArtist returns the albums of an artist)
            pinButton!!.visibility = View.GONE
            unpinButton!!.visibility = View.GONE
            downloadButton!!.visibility = View.GONE
            deleteButton!!.visibility = View.GONE
            selectButton!!.visibility = View.GONE
            playNowButton!!.visibility = View.GONE
            playNextButton!!.visibility = View.GONE
            playLastButton!!.visibility = View.GONE

            if (listSize == 0 || entryList.size < listSize) {
                albumButtons!!.visibility = View.GONE
            } else {
                moreButton!!.visibility = View.VISIBLE
            }
        }

        enableButtons()

        val isAlbumList = requireArguments().containsKey(
            Constants.INTENT_EXTRA_NAME_ALBUM_LIST_TYPE
        )

        playAllButtonVisible = !(isAlbumList || entryList.isEmpty()) && !allVideos
        shareButtonVisible = !isOffline() && songCount > 0

        if (playAllButton != null) {
            playAllButton!!.isVisible = playAllButtonVisible
        }

        if (shareButton != null) {
            shareButton!!.isVisible = shareButtonVisible
        }

        if (songCount > 0 && listModel.showHeader) {
            val name = listModel.currentDirectory.value?.name
            val intentAlbumName = requireArguments().getString(Constants.INTENT_EXTRA_NAME_NAME, "Name")!!
            val albumHeader = AlbumHeader(it, name ?: intentAlbumName, songCount)
            val mixedList: MutableList<Identifiable> = mutableListOf(albumHeader)
            mixedList.addAll(entryList)
            viewAdapter.submitList(mixedList)
        } else {
            viewAdapter.submitList(entryList)
        }

        val playAll = requireArguments().getBoolean(Constants.INTENT_EXTRA_NAME_AUTOPLAY, false)
        if (playAll && songCount > 0) {
            playAll(
                requireArguments().getBoolean(Constants.INTENT_EXTRA_NAME_SHUFFLE, false),
                false
            )
        }

        listModel.currentListIsSortable = true
    }

    private fun getSelectedSongs(): List<MusicDirectory.Entry> {
        // Walk through selected set and get the Entries based on the saved ids.
        return viewAdapter.getCurrentList().mapNotNull {
            if (it is MusicDirectory.Entry && viewAdapter.isSelected(it.longId))
                it
            else
                null
        }
    }

    override fun setTitle(title: String?) {
        setTitle(this@TrackCollectionFragment, title)
    }

    fun setTitle(id: Int) {
        setTitle(this@TrackCollectionFragment, id)
    }

    @Suppress("LongMethod")
    override fun getLiveData(args: Bundle?): LiveData<List<MusicDirectory.Entry>> {
        if (args == null) return listModel.currentList
        val id = args.getString(Constants.INTENT_EXTRA_NAME_ID)
        val isAlbum = args.getBoolean(Constants.INTENT_EXTRA_NAME_IS_ALBUM, false)
        val name = args.getString(Constants.INTENT_EXTRA_NAME_NAME)
        val parentId = args.getString(Constants.INTENT_EXTRA_NAME_PARENT_ID)
        val playlistId = args.getString(Constants.INTENT_EXTRA_NAME_PLAYLIST_ID)
        val podcastChannelId = args.getString(
            Constants.INTENT_EXTRA_NAME_PODCAST_CHANNEL_ID
        )
        val playlistName = args.getString(Constants.INTENT_EXTRA_NAME_PLAYLIST_NAME)
        val shareId = args.getString(Constants.INTENT_EXTRA_NAME_SHARE_ID)
        val shareName = args.getString(Constants.INTENT_EXTRA_NAME_SHARE_NAME)
        val genreName = args.getString(Constants.INTENT_EXTRA_NAME_GENRE_NAME)

        val getStarredTracks = args.getInt(Constants.INTENT_EXTRA_NAME_STARRED, 0)
        val getVideos = args.getInt(Constants.INTENT_EXTRA_NAME_VIDEOS, 0)
        val getRandomTracks = args.getInt(Constants.INTENT_EXTRA_NAME_RANDOM, 0)
        val albumListSize = args.getInt(
            Constants.INTENT_EXTRA_NAME_ALBUM_LIST_SIZE, 0
        )
        val albumListOffset = args.getInt(
            Constants.INTENT_EXTRA_NAME_ALBUM_LIST_OFFSET, 0
        )
        val refresh = args.getBoolean(Constants.INTENT_EXTRA_NAME_REFRESH, true)

        listModel.viewModelScope.launch(handler) {
            refreshListView!!.isRefreshing = true

            listModel.getMusicFolders(refresh)

            if (playlistId != null) {
                setTitle(playlistName!!)
                listModel.getPlaylist(playlistId, playlistName)
            } else if (podcastChannelId != null) {
                setTitle(getString(R.string.podcasts_label))
                listModel.getPodcastEpisodes(podcastChannelId)
            } else if (shareId != null) {
                setTitle(shareName)
                listModel.getShare(shareId)
            } else if (genreName != null) {
                setTitle(genreName)
                listModel.getSongsForGenre(genreName, albumListSize, albumListOffset)
            } else if (getStarredTracks != 0) {
                setTitle(getString(R.string.main_songs_starred))
                listModel.getStarred()
            } else if (getVideos != 0) {
                setTitle(R.string.main_videos)
                listModel.getVideos(refresh)
            } else if (getRandomTracks != 0) {
                setTitle(R.string.main_songs_random)
                listModel.getRandom(albumListSize)
            } else {
                setTitle(name)
                if (!isOffline() && Settings.shouldUseId3Tags) {
                    if (isAlbum) {
                        listModel.getAlbum(refresh, id!!, name, parentId)
                    } else {
                        listModel.getArtist(refresh, id!!, name)
                    }
                } else {
                    listModel.getMusicDirectory(refresh, id!!, name, parentId)
                }
            }

            refreshListView!!.isRefreshing = false
        }
        return listModel.currentList
    }

    override fun onContextMenuItemSelected(
        menuItem: MenuItem,
        item: MusicDirectory.Entry
    ): Boolean {
        // TODO
        return false
    }

    override fun onItemClick(item: MusicDirectory.Entry) {
        // nothing
    }
}
