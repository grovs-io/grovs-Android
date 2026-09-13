package io.grovs.fragments

import android.annotation.SuppressLint
import android.content.DialogInterface
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.viewModels
import androidx.navigation.fragment.NavHostFragment
import androidx.navigation.get
import io.grovs.Grovs
import io.grovs.service.GrovsService
import io.grovs.R
import io.grovs.databinding.FragmentNotificationsMainBinding
import io.grovs.utils.applySystemBarInsets
import io.grovs.viewmodels.NotificationsMainViewModel

/**
 * Keeps a no-argument constructor: Android rebuilds saved fragments through it whenever the
 * host Activity is recreated. The service and dismiss callback arrive through [bind] before the
 * first show and live in the ViewModel from then on.
 */
class NotificationsMainFragment : DialogFragment() {
    private lateinit var binding: FragmentNotificationsMainBinding
    private val viewModel: NotificationsMainViewModel by viewModels()

    private var pendingService: GrovsService? = null
    private var pendingOnDismissed: (() -> Unit)? = null

    @JvmSynthetic
    internal fun bind(grovsService: GrovsService, onDismissed: (() -> Unit)?) {
        pendingService = grovsService
        pendingOnDismissed = onDismissed
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setStyle(STYLE_NO_TITLE, R.style.GrovsFullScreenDialogStyle)
        pendingService?.let {
            viewModel.grovsService = it
            viewModel.onDismissed = pendingOnDismissed
        }
        pendingService = null
        pendingOnDismissed = null
        if (viewModel.grovsService == null) {
            // Restored after process death: only the configured SDK can supply the service again.
            // The host's dismiss callback did not survive, so nothing is reported on close.
            viewModel.grovsService = Grovs.activeNotificationsManager?.grovsService
        }
        if (viewModel.grovsService == null) dismissAllowingStateLoss()
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        // Inflate the layout for this fragment
        binding = FragmentNotificationsMainBinding.inflate(inflater, container, false)

        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        if (viewModel.grovsService == null) return
        applySystemBarInsets()
        setup()
    }

    override fun onCancel(dialog: DialogInterface) {
        super.onCancel(dialog)

        viewModel.onDismissed?.invoke()
    }

    @SuppressLint("RestrictedApi")
    private fun setup() {
        binding.backButton.setOnClickListener {
            val navHost = childFragmentManager.findFragmentById(R.id.notificationsHostFragment) as NavHostFragment
            navHost.navController.navigateUp()
        }
        binding.closeButton.setOnClickListener {
            dismiss()
            viewModel.onDismissed?.invoke()
        }

        val navHost = childFragmentManager.findFragmentById(R.id.notificationsHostFragment) as NavHostFragment
        navHost.navController.addOnDestinationChangedListener { controller, destination, _ ->
            val hasBackEntries = navHost.navController.currentBackStack.value.size != 1
            if (hasBackEntries && (destination == navHost.navController.graph[R.id.notificationDetailsFragment])) {
                binding.backButton.visibility = View.VISIBLE
            } else {
                binding.backButton.visibility = View.GONE
            }
        }
    }

}
