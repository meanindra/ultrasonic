package org.moire.ultrasonic.adapters

import android.content.Context
import android.view.LayoutInflater
import android.view.MenuItem
import android.view.ViewGroup
import androidx.lifecycle.LifecycleOwner
import com.drakeet.multitype.ItemViewBinder
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import org.moire.ultrasonic.R
import org.moire.ultrasonic.domain.Identifiable
import org.moire.ultrasonic.domain.MusicDirectory
import org.moire.ultrasonic.service.DownloadFile
import org.moire.ultrasonic.service.Downloader

class TrackViewBinder(
    val onItemClick: (DownloadFile) -> Unit,
    val onContextMenuClick: ((MenuItem, DownloadFile) -> Boolean)? = null,
    val checkable: Boolean,
    val draggable: Boolean,
    context: Context,
    val lifecycleOwner: LifecycleOwner,
) : ItemViewBinder<Identifiable, TrackViewHolder>(), KoinComponent {

    // Set our layout files
    val layout = R.layout.list_item_track
    val contextMenuLayout = R.menu.context_menu_track

    private val downloader: Downloader by inject()
    private val imageHelper: Utils.ImageHelper = Utils.ImageHelper(context)

    override fun onCreateViewHolder(inflater: LayoutInflater, parent: ViewGroup): TrackViewHolder {
        return TrackViewHolder(inflater.inflate(layout, parent, false))
    }

    @Suppress("LongMethod")
    override fun onBindViewHolder(holder: TrackViewHolder, item: Identifiable) {
        val downloadFile: DownloadFile?
        val diffAdapter = adapter as BaseAdapter<*>

        when (item) {
            is MusicDirectory.Entry -> {
                downloadFile = downloader.getDownloadFileForSong(item)
            }
            is DownloadFile -> {
                downloadFile = item
            }
            else -> {
                return
            }
        }

        holder.imageHelper = imageHelper

        holder.setSong(
            file = downloadFile,
            checkable = checkable,
            draggable = draggable,
            diffAdapter.isSelected(item.longId)
        )

        holder.itemView.setOnLongClickListener {
            if (onContextMenuClick != null) {
                val popup = Utils.createPopupMenu(holder.itemView, contextMenuLayout)

                popup.setOnMenuItemClickListener { menuItem ->
                    onContextMenuClick?.invoke(menuItem, downloadFile)
                }
            } else {
                // Minimize or maximize the Text view (if song title is very long)
                if (!downloadFile.song.isDirectory) {
                    holder.maximizeOrMinimize()
                }
            }

            true
        }

        holder.itemView.setOnClickListener {
            if (!checkable) {
                onItemClick(downloadFile)
            } else {
                val nowChecked = !holder.check.isChecked
                holder.isChecked = nowChecked
            }
        }

        // Notify the adapter of selection changes
        holder.observableChecked.observe(
            lifecycleOwner,
            { newValue ->
                if (newValue) {
                    diffAdapter.notifySelected(item.longId)
                } else {
                    diffAdapter.notifyUnselected(item.longId)
                }
            }
        )

        // Listen to changes in selection status and update ourselves
        diffAdapter.selectionRevision.observe(
            lifecycleOwner,
            {
                val newStatus = diffAdapter.isSelected(item.longId)

                if (newStatus != holder.check.isChecked) holder.check.isChecked = newStatus
            }
        )

        // Observe download status
        downloadFile.status.observe(
            lifecycleOwner,
            {
                holder.updateStatus(it)
                diffAdapter.notifyChanged()
            }
        )

        downloadFile.progress.observe(
            lifecycleOwner,
            {
                holder.updateProgress(it)
            }
        )
    }
}
