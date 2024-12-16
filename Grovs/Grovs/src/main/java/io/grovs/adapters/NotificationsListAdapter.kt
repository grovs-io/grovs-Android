package io.grovs.adapters

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import io.grovs.databinding.LayoutNotificationListItemBinding
import java.lang.IllegalStateException

sealed class NotificationsListItem {
    data class Notification(val item: io.grovs.model.notifications.Notification) : NotificationsListItem()
}

class NotificationsListAdapter(val context: Context, var data: List<NotificationsListItem>, val onNotificationSelected: (item: NotificationsListItem) -> Unit) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    enum class NotificationSelectionItemTypes(val value: Int) {
        ITEM(0)
    }

    class LayoutNotificationListItemHolder(val binding: LayoutNotificationListItemBinding, val context: Context) : RecyclerView.ViewHolder(binding.root) {

    }

    override fun getItemViewType(position: Int): Int {
        val item = data[position]
        return when (item) {
            is NotificationsListItem.Notification -> NotificationSelectionItemTypes.ITEM.value
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val layoutInflater = LayoutInflater.from(parent.context)
        return when (viewType) {
            NotificationSelectionItemTypes.ITEM.value -> LayoutNotificationListItemHolder(
                LayoutNotificationListItemBinding.inflate(layoutInflater, parent, false), context)
            else -> throw IllegalStateException("Invalid type")
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        val item = data[position]
        when (item) {
            is NotificationsListItem.Notification -> {
                val viewHolder = holder as? LayoutNotificationListItemHolder
                if (item.item.read) {
                    viewHolder?.binding?.unreadIndicatorFrameLayout?.visibility = View.INVISIBLE
                } else {
                    viewHolder?.binding?.unreadIndicatorFrameLayout?.visibility = View.VISIBLE
                }
                viewHolder?.binding?.titleTextView?.text = item.item.title
                viewHolder?.binding?.subtitleTextView?.text = item.item.subtitle
                viewHolder?.binding?.button?.setOnClickListener {
                    onNotificationSelected.invoke(item)
                }
            }
        }

    }

    override fun getItemCount(): Int {
        return data.count()
    }

    fun updateData(data: List<NotificationsListItem>) {
        this.data = data
        notifyDataSetChanged()
    }

}