package com.faldo.hsk_quest

import android.content.Context
import android.content.res.Configuration
import android.os.Bundle
import android.view.View
import android.view.animation.OvershootInterpolator
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.GravityCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.drawerlayout.widget.DrawerLayout
import androidx.lifecycle.lifecycleScope
import androidx.navigation.NavController
import androidx.navigation.NavOptions
import androidx.navigation.fragment.NavHostFragment
import androidx.navigation.ui.AppBarConfiguration
import androidx.navigation.ui.setupWithNavController
import androidx.recyclerview.widget.RecyclerView
import com.faldo.hsk_quest.databinding.ActivityMainBinding
import com.faldo.hsk_quest.util.DisplayScaleNormalizer
import kotlinx.coroutines.launch

/**
 * Single activity hosting NavHostFragment with DrawerLayout, MaterialToolbar, and 5-Tab BottomNavigationView.
 * Controls directional tab transitions, drawer animations, window insets, and immersive screens (battle/auth).
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var navController: NavController
    private lateinit var appBarConfiguration: AppBarConfiguration

    private val tabOrder = listOf(
        R.id.homeFragment,
        R.id.worldMapFragment,
        R.id.questFragment,
        R.id.petFragment,
        R.id.characterFragment,
    )

    private var currentTabIndex: Int = 0

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(DisplayScaleNormalizer.wrapContext(newBase))
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        DisplayScaleNormalizer.applyInPlace(this)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        DisplayScaleNormalizer.applyInPlace(this)
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupWindowInsets()

        val navHostFragment = supportFragmentManager
            .findFragmentById(R.id.nav_host_fragment) as NavHostFragment
        navController = navHostFragment.navController

        // Top-level destinations that display the hamburger icon
        val topLevelDestinations = setOf(
            R.id.homeFragment,
            R.id.worldMapFragment,
            R.id.questFragment,
            R.id.characterFragment,
            R.id.petFragment,
            R.id.shopFragment,
            R.id.gachaFragment,
            R.id.leaderboardFragment,
            R.id.practiceFragment,
            R.id.settingsFragment,
        )

        appBarConfiguration = AppBarConfiguration(topLevelDestinations, binding.drawerLayout)

        // Setup Toolbar with NavController
        binding.toolbar.setupWithNavController(navController, appBarConfiguration)

        // Setup 5-Tab BottomNavigationView with directional slide + fade animations
        binding.bottomNav.setOnItemSelectedListener { menuItem ->
            val targetId = menuItem.itemId
            if (targetId !in tabOrder) return@setOnItemSelectedListener false

            animateBottomTabSelection(targetId)

            val currentDestId = navController.currentDestination?.id
            if (currentDestId == targetId) {
                return@setOnItemSelectedListener true
            }

            navigateToTabWithAnimation(targetId)
            true
        }

        binding.bottomNav.setOnItemReselectedListener { menuItem ->
            val targetId = menuItem.itemId
            animateBottomTabSelection(targetId)
            if (navController.currentDestination?.id != targetId && targetId in tabOrder) {
                navigateToTabWithAnimation(targetId)
            }
        }

        // Custom handling for drawer items with smooth transition animations
        binding.navView.setNavigationItemSelectedListener { menuItem ->
            binding.drawerLayout.closeDrawer(GravityCompat.START)
            when (menuItem.itemId) {
                R.id.nav_logout -> {
                    lifecycleScope.launch {
                        val container = (application as HskQuestApp).container
                        container.authRepository.logout()
                        container.questRepository.clearMemoryCache()
                        container.petRepository.clearMemoryCache()
                        container.shopRepository.clearMemoryCache()
                        container.gachaRepository.clearMemoryCache()
                        val navOptions = NavOptions.Builder()
                            .setPopUpTo(R.id.nav_graph, true)
                            .setEnterAnim(R.anim.screen_enter)
                            .setExitAnim(R.anim.screen_exit)
                            .build()
                        navController.navigate(R.id.loginFragment, null, navOptions)
                    }
                    true
                }
                in tabOrder -> {
                    if (navController.currentDestination?.id != menuItem.itemId) {
                        navigateToTabWithAnimation(menuItem.itemId)
                    }
                    true
                }
                else -> {
                    if (navController.currentDestination?.id != menuItem.itemId) {
                        val navOptions = NavOptions.Builder()
                            .setLaunchSingleTop(true)
                            .setPopUpTo(R.id.homeFragment, false)
                            .setEnterAnim(R.anim.screen_enter)
                            .setExitAnim(R.anim.screen_exit)
                            .setPopEnterAnim(R.anim.screen_pop_enter)
                            .setPopExitAnim(R.anim.screen_pop_exit)
                            .build()
                        navController.navigate(menuItem.itemId, null, navOptions)
                    }
                    true
                }
            }
        }

        // Hide toolbar and lock drawer on auth/splash/battle screens
        navController.addOnDestinationChangedListener { _, destination, _ ->
            val isImmersive = when (destination.id) {
                R.id.splashFragment,
                R.id.loginFragment,
                R.id.registerFragment,
                R.id.battleFragment -> true
                else -> false
            }

            if (isImmersive) {
                binding.toolbar.visibility = View.GONE
                binding.bottomNav.visibility = View.GONE
                binding.drawerLayout.setDrawerLockMode(DrawerLayout.LOCK_MODE_LOCKED_CLOSED)
            } else {
                binding.toolbar.visibility = View.VISIBLE
                binding.bottomNav.visibility = View.VISIBLE
                binding.bottomNav.translationY = 0f
                binding.drawerLayout.setDrawerLockMode(DrawerLayout.LOCK_MODE_UNLOCKED)

                // Keep checked bottom nav tab and drawer item in sync
                binding.navView.setCheckedItem(destination.id)
                val tabIdx = tabOrder.indexOf(destination.id)
                if (tabIdx >= 0) {
                    currentTabIndex = tabIdx
                    binding.bottomNav.menu.findItem(destination.id)?.isChecked = true
                }
            }
        }
    }

    /**
     * Navigates between primary bottom tabs using directional slide + fade + scale animations
     * determined by the relative tab index (left-to-right vs right-to-left).
     */
    private fun navigateToTabWithAnimation(targetId: Int) {
        val targetIndex = tabOrder.indexOf(targetId).coerceAtLeast(0)
        val currentDestId = navController.currentDestination?.id
        val fromIndex = tabOrder.indexOf(currentDestId).takeIf { it >= 0 } ?: currentTabIndex

        val movingRight = targetIndex > fromIndex
        val enterAnim = if (movingRight) R.anim.slide_in_right_fade else R.anim.slide_in_left_fade
        val exitAnim = if (movingRight) R.anim.slide_out_left_fade else R.anim.slide_out_right_fade

        currentTabIndex = targetIndex

        val isGoingHome = targetId == R.id.homeFragment
        val navOptions = NavOptions.Builder()
            .setLaunchSingleTop(true)
            .setPopUpTo(R.id.homeFragment, inclusive = isGoingHome)
            .setEnterAnim(enterAnim)
            .setExitAnim(exitAnim)
            .setPopEnterAnim(R.anim.slide_in_left_fade)
            .setPopExitAnim(R.anim.slide_out_right_fade)
            .build()

        navController.navigate(targetId, null, navOptions)
    }

    /**
     * Plays a tactile spring pop animation on the selected BottomNavigationView tab item.
     */
    private fun animateBottomTabSelection(itemId: Int) {
        val itemView = binding.bottomNav.findViewById<View>(itemId) ?: return
        itemView.animate().cancel()
        itemView.scaleX = 0.90f
        itemView.scaleY = 0.90f
        itemView.animate()
            .scaleX(1.0f)
            .scaleY(1.0f)
            .setDuration(240L)
            .setInterpolator(OvershootInterpolator(2.2f))
            .start()
    }

    fun setBottomNavVisible(visible: Boolean) {
        binding.bottomNav.translationY = 0f
        binding.bottomNav.visibility = if (visible) View.VISIBLE else View.GONE
    }

    fun slideHideBottomNav() {
        // Bottom nav is cleanly docked below NavHostFragment so it never covers content.
    }

    fun slideShowBottomNav() {
        binding.bottomNav.translationY = 0f
    }

    fun attachScrollListener(scrollView: View) {
        // Retained for binary/source compatibility; docked bottom bar does not overlap scroll content.
    }

    fun attachRecyclerScrollListener(recyclerView: RecyclerView) {
        // Retained for binary/source compatibility; docked bottom bar does not overlap recycler content.
    }

    /**
     * Applies system status bar, navigation bar, cutout, and IME insets to the main content
     * container and navigation drawer so top/bottom controls never overlap system bars.
     */
    private fun setupWindowInsets() {
        val extraTopBufferPx = (resources.displayMetrics.density * 4).toInt()

        ViewCompat.setOnApplyWindowInsetsListener(binding.mainContentContainer) { view, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or
                    WindowInsetsCompat.Type.displayCutout() or
                    WindowInsetsCompat.Type.ime()
            )
            view.updatePadding(
                left = bars.left,
                top = bars.top + extraTopBufferPx,
                right = bars.right,
                bottom = bars.bottom,
            )
            insets
        }

        ViewCompat.setOnApplyWindowInsetsListener(binding.navView) { view, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or
                    WindowInsetsCompat.Type.displayCutout()
            )
            view.updatePadding(
                top = bars.top + extraTopBufferPx,
                bottom = bars.bottom,
            )
            insets
        }

        // Prevent BottomNavigationView from double-padding bottom system bar insets
        ViewCompat.setOnApplyWindowInsetsListener(binding.bottomNav) { _, insets ->
            insets
        }
    }
}
