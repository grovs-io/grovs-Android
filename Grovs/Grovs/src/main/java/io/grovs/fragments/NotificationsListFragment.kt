package io.grovs.fragments

import android.os.Bundle
import androidx.fragment.app.Fragment
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.os.bundleOf
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import io.grovs.R
import io.grovs.adapters.NotificationsListAdapter
import io.grovs.adapters.NotificationsListItem
import io.grovs.databinding.FragmentNotificationsListBinding
import io.grovs.model.notifications.Notification
import io.grovs.utils.hideProgressBar
import io.grovs.utils.safeNavigate
import io.grovs.utils.showProgressBar
import io.grovs.viewmodels.NotificationsMainViewModel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch


class NotificationsListFragment : Fragment() {
    private lateinit var binding: FragmentNotificationsListBinding
    private val viewModel: NotificationsMainViewModel by viewModels(ownerProducer = { requireParentFragment().requireParentFragment() })

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        // Inflate the layout for this fragment
        binding = FragmentNotificationsListBinding.inflate(inflater, container, false)

        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        setup()
    }

    private fun setup() {
        binding.recyclerView.layoutManager = LinearLayoutManager(context, LinearLayoutManager.VERTICAL, false)
        setItems(notificationsToListItems(emptyList()))

        // Observe loading state to show a progress indicator if needed
        lifecycleScope.launch {
            viewModel.isLoading.collectLatest { isLoading ->
                if (isLoading) {
                    if (viewModel.notifications.value.isEmpty()) {
                        showProgressBar()
                    }
                } else {
                    hideProgressBar()
                }
            }
        }

        lifecycleScope.launch {
            viewModel.notifications.collectLatest { items ->
                (binding.recyclerView.adapter as? NotificationsListAdapter)?.updateData(notificationsToListItems(items))
            }
        }

        // Add scroll listener to load the next page when reaching the bottom
        binding.recyclerView.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                super.onScrolled(recyclerView, dx, dy)

                val layoutManager = recyclerView.layoutManager as LinearLayoutManager
                val visibleItemCount = layoutManager.childCount
                val totalItemCount = layoutManager.itemCount
                val firstVisibleItemPosition = layoutManager.findFirstVisibleItemPosition()

                // Load more if scrolled to the bottom and not already loading
                if (!viewModel.isLoading.value &&
                    visibleItemCount + firstVisibleItemPosition >= totalItemCount &&
                    firstVisibleItemPosition >= 0
                ) {
                    viewModel.loadMoreNotifications()
                }
            }
        })

        viewModel.loadMoreNotifications()
    }

    private fun notificationsToListItems(notifications: List<Notification>): List<NotificationsListItem> {
        var listItems = mutableListOf<NotificationsListItem>()
        val items = notifications.sortedByDescending { it.updatedAt }.map { NotificationsListItem.Notification(it) }
        listItems.addAll(items)

        return listItems
    }

    private fun setItems(items: List<NotificationsListItem>) {
        binding.recyclerView.adapter = NotificationsListAdapter(requireContext(), items, onNotificationSelected = { item ->
            when (item) {
                is NotificationsListItem.Notification -> {
                    findNavController().safeNavigate(R.id.notificationsListFragment, R.id.action_showNotificationDetailsFragment, bundleOf(
                        ARG_NOTIFICATION to item.item))
                }
            }
        })
    }

}