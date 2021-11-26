package org.moire.ultrasonic.fragment

import android.os.Bundle
import android.view.MenuItem
import android.view.View
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import org.moire.ultrasonic.R
import org.moire.ultrasonic.domain.Artist
import org.moire.ultrasonic.domain.GenericEntry
import org.moire.ultrasonic.domain.Identifiable
import org.moire.ultrasonic.service.RxBus
import org.moire.ultrasonic.subsonic.DownloadHandler
import org.moire.ultrasonic.util.Constants
import org.moire.ultrasonic.util.Settings

/**
 * An extension of the MultiListFragment, with a few helper functions geared
 * towards the display of MusicDirectory.Entries.
 * @param T: The type of data which will be used (must extend GenericEntry)
 */
abstract class EntryListFragment<T : GenericEntry> : MultiListFragment<T>() {

    /**
     * Whether to show the folder selector
     */
    // FIXME
    fun showFolderHeader(): Boolean {
        return listModel.showSelectFolderHeader(arguments) &&
            !listModel.isOffline() && !Settings.shouldUseId3Tags
    }

    override fun onContextMenuItemSelected(menuItem: MenuItem, item: T): Boolean {
        val isArtist = (item is Artist)

        return handleContextMenu(menuItem, item, isArtist, downloadHandler, this)
    }

    override fun onItemClick(item: T) {
        val bundle = Bundle()
        bundle.putString(Constants.INTENT_EXTRA_NAME_ID, item.id)
        bundle.putString(Constants.INTENT_EXTRA_NAME_NAME, item.name)
        bundle.putString(Constants.INTENT_EXTRA_NAME_PARENT_ID, item.id)
        bundle.putBoolean(Constants.INTENT_EXTRA_NAME_ARTIST, (item is Artist))
        findNavController().navigate(R.id.trackCollectionFragment, bundle)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // FIXME: What to do when the user has modified the folder filter
        RxBus.musicFolderChangedEventObservable.subscribe {
            if (!listModel.isOffline()) {
                val currentSetting = listModel.activeServer
                currentSetting.musicFolderId = it
                serverSettingsModel.updateItem(currentSetting)
            }
            viewAdapter.notifyDataSetChanged()
            listModel.refresh(refreshListView!!, arguments)
        }
    }

    companion object {
        @Suppress("LongMethod")
        internal fun handleContextMenu(
            menuItem: MenuItem,
            item: Identifiable,
            isArtist: Boolean,
            downloadHandler: DownloadHandler,
            fragment: Fragment
        ): Boolean {
            when (menuItem.itemId) {
                R.id.menu_play_now ->
                    downloadHandler.downloadRecursively(
                        fragment,
                        item.id,
                        save = false,
                        append = false,
                        autoPlay = true,
                        shuffle = false,
                        background = false,
                        playNext = false,
                        unpin = false,
                        isArtist = isArtist
                    )
                R.id.menu_play_next ->
                    downloadHandler.downloadRecursively(
                        fragment,
                        item.id,
                        save = false,
                        append = false,
                        autoPlay = true,
                        shuffle = true,
                        background = false,
                        playNext = true,
                        unpin = false,
                        isArtist = isArtist
                    )
                R.id.menu_play_last ->
                    downloadHandler.downloadRecursively(
                        fragment,
                        item.id,
                        save = false,
                        append = true,
                        autoPlay = false,
                        shuffle = false,
                        background = false,
                        playNext = false,
                        unpin = false,
                        isArtist = isArtist
                    )
                R.id.menu_pin ->
                    downloadHandler.downloadRecursively(
                        fragment,
                        item.id,
                        save = true,
                        append = true,
                        autoPlay = false,
                        shuffle = false,
                        background = false,
                        playNext = false,
                        unpin = false,
                        isArtist = isArtist
                    )
                R.id.menu_unpin ->
                    downloadHandler.downloadRecursively(
                        fragment,
                        item.id,
                        save = false,
                        append = false,
                        autoPlay = false,
                        shuffle = false,
                        background = false,
                        playNext = false,
                        unpin = true,
                        isArtist = isArtist
                    )
                R.id.menu_download ->
                    downloadHandler.downloadRecursively(
                        fragment,
                        item.id,
                        save = false,
                        append = false,
                        autoPlay = false,
                        shuffle = false,
                        background = true,
                        playNext = false,
                        unpin = false,
                        isArtist = isArtist
                    )
                else -> return false
            }
            return true
        }
    }
}
