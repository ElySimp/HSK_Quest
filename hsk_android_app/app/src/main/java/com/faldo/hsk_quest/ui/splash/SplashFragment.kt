package com.faldo.hsk_quest.ui.splash

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import com.faldo.hsk_quest.R
import com.faldo.hsk_quest.databinding.FragmentSplashBinding
import com.faldo.hsk_quest.util.Constants
import com.faldo.hsk_quest.util.appContainer
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Shows the logo, restores the stored session, then routes to Home or Login.
 */
class SplashFragment : Fragment() {

    private var _binding: FragmentSplashBinding? = null
    private val binding get() = _binding!!

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        _binding = FragmentSplashBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        playIntroAnimation()

        viewLifecycleOwner.lifecycleScope.launch {
            val hasSession = async { appContainer.authRepository.restoreSession() }
            delay(Constants.SPLASH_MIN_DURATION_MS)
            val nav = findNavController()
            if (nav.currentDestination?.id != R.id.splashFragment) return@launch
            nav.navigate(
                if (hasSession.await()) R.id.action_splash_to_home else R.id.action_splash_to_login
            )
        }
    }

    private fun playIntroAnimation() {
        binding.tvLogo.apply {
            scaleX = 0.6f
            scaleY = 0.6f
            alpha = 0f
            animate().scaleX(1f).scaleY(1f).alpha(1f).setDuration(600).start()
        }
        listOf(binding.tvAppName, binding.tvTagline).forEachIndexed { i, v ->
            v.alpha = 0f
            v.translationY = 24f
            v.animate().alpha(1f).translationY(0f)
                .setStartDelay(300L + i * 150L).setDuration(450).start()
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
