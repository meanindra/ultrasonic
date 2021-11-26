/*
 * DownloadsFragment.kt
 * Copyright (C) 2009-2021 Ultrasonic developers
 *
 * Distributed under terms of the GNU GPLv3 license.
 */

package org.moire.ultrasonic.fragment

import android.app.Application
import android.os.Bundle
import android.view.MenuItem
import android.view.View
import androidx.fragment.app.viewModels
import androidx.lifecycle.LiveData
import org.koin.core.component.inject
import org.moire.ultrasonic.R
import org.moire.ultrasonic.adapters.TrackViewBinder
import org.moire.ultrasonic.model.GenericListModel
import org.moire.ultrasonic.service.DownloadFile
import org.moire.ultrasonic.service.Downloader
import org.moire.ultrasonic.util.Util

/**
 * Displays currently running downloads.
 * For now its a read-only view, there are no manipulations of the download list possible.
 *
 * A consideration would be to base this class on TrackCollectionFragment and thereby inheriting the
 * buttons useful to manipulate the list.
 */
class DownloadsFragment : MultiListFragment<DownloadFile>() {

    /**
     * The ViewModel to use to get the data
     */
    override val listModel: DownloadListModel by viewModels()

    /**
     * The id of the target in the navigation graph where we should go,
     * after the user has clicked on an item
     */
    // FIXME
    override val itemClickTarget: Int = R.id.trackCollectionFragment

    /**
     * The central function to pass a query to the model and return a LiveData object
     */
    override fun getLiveData(args: Bundle?): LiveData<List<DownloadFile>> {
        return listModel.getList()
    }

    override fun onContextMenuItemSelected(menuItem: MenuItem, item: DownloadFile): Boolean {
        // TODO: Add code to enable manipulation of the download list
        return true
    }

    override fun onItemClick(item: DownloadFile) {
        // TODO: Add code to enable manipulation of the download list
    }

    override fun setTitle(title: String?) {
        FragmentTitle.setTitle(this, Util.appContext().getString(R.string.menu_downloads))
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        viewAdapter.register(
            TrackViewBinder(
                checkable = false,
                draggable = false,
                context = requireContext(),
                lifecycleOwner = viewLifecycleOwner
            )
        )

        val liveDataList = listModel.getList()

        viewAdapter.submitList(liveDataList.value)
    }
}

class DownloadListModel(application: Application) : GenericListModel(application) {
    private val downloader by inject<Downloader>()

    fun getList(): LiveData<List<DownloadFile>> {
        return downloader.observableDownloads
    }
}
