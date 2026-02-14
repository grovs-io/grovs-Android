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
import io.grovs.service.GrovsService
import io.grovs.R
import io.grovs.databinding.FragmentNotificationsMainBinding
import io.grovs.utils.applySystemBarInsets
import io.grovs.viewmodels.NotificationsMainViewModel

class NotificationsMainFragment(val grovsService: GrovsService) : DialogFragment() {
    private lateinit var binding: FragmentNotificationsMainBinding
    private val viewModel: NotificationsMainViewModel by viewModels()

    var onDialogDismissed: (()->Unit)? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setStyle(STYLE_NO_TITLE, R.style.GrovsFullScreenDialogStyle)
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

        applySystemBarInsets()
        setup()
    }

    override fun onCancel(dialog: DialogInterface) {
        super.onCancel(dialog)

        onDialogDismissed?.invoke()
    }

    @SuppressLint("RestrictedApi")
    private fun setup() {
        viewModel.grovsService = grovsService

        binding.backButton.setOnClickListener {
            val navHost = childFragmentManager.findFragmentById(R.id.notificationsHostFragment) as NavHostFragment
            navHost.navController.navigateUp()
        }
        binding.closeButton.setOnClickListener {
            dismiss()
            onDialogDismissed?.invoke()
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