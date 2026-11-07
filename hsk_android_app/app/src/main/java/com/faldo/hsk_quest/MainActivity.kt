package com.faldo.hsk_quest

import android.os.Bundle
import android.view.View
import android.view.animation.AccelerateDecelerateInterpolator
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
import androidx.navigation.ui.NavigationUI
import androidx.navigation.ui.setupWithNavController
import androidx.recyclerview.widget.RecyclerView
import com.faldo.hsk_quest.databinding.ActivityMainBinding
import kotlinx.coroutines.launch

/**
 * Single activity hosting NavHostFragment with DrawerLayout and MaterialToolbar.
 * Controls top-level navigation, hamburger menu, window insets, and immersive screens (battle/auth).
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var navController: NavController
    private lateinit var appBarConfiguration: AppBarConfiguration

    override fun onCreate(savedInstanceState: Bundle?) {
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
            R.id.characterFragment,
            R.id.petFragment,
            R.id.shopFragment,
            R.id.gachaFragment,
            R.id.leaderboardFragment,
            R.id.practiceFragment,
        )

        appBarConfiguration = AppBarConfiguration(topLevelDestinations, binding.drawerLayout)

        // Setup Toolbar with NavController
        binding.toolbar.setupWithNavController(navController, appBarConfiguration)

        // Setup NavigationView menu clicks
        binding.navView.setupWithNavController(navController)

        // Setup 4-Tab BottomNavigationView with custom selection & reselection handling
        binding.bottomNav.setOnItemSelectedListener { menuItem ->
            when (menuItem.itemId) {
                R.id.homeFragment -> {
                    if (navController.currentDestination?.id != R.id.homeFragment) {
                        val popped = navController.popBackStack(R.id.homeFragment, false)
                        if (!popped) {
                            val navOptions = NavOptions.Builder()
                                .setPopUpTo(R.id.homeFragment, true)
                                .setLaunchSingleTop(true)
                                .build()
                            navController.navigate(R.id.homeFragment, null, navOptions)
                        }
                    }
                    true
                }
                R.id.worldMapFragment,
                R.id.petFragment,
                R.id.characterFragment -> {
                    if (navController.currentDestination?.id != menuItem.itemId) {
                        val popped = navController.popBackStack(menuItem.itemId, false)
                        if (!popped) {
                            val navOptions = NavOptions.Builder()
                                .setPopUpTo(R.id.homeFragment, false)
                                .setLaunchSingleTop(true)
                                .build()
                            navController.navigate(menuItem.itemId, null, navOptions)
                        }
                    }
                    true
                }
                else -> false
            }
        }

        binding.bottomNav.setOnItemReselectedListener { menuItem ->
            when (menuItem.itemId) {
                R.id.homeFragment -> {
                    // Tapping Home while on a secondary screen (Shop, Practice, Gacha, Leaderboard, etc.)
                    // returns immediately to the HomeFragment root.
                    if (navController.currentDestination?.id != R.id.homeFragment) {
                        val popped = navController.popBackStack(R.id.homeFragment, false)
                        if (!popped) {
                            navController.navigate(R.id.homeFragment)
                        }
                    }
                }
                R.id.worldMapFragment,
                R.id.petFragment,
                R.id.characterFragment -> {
                    if (navController.currentDestination?.id != menuItem.itemId) {
                        navController.popBackStack(menuItem.itemId, false)
                    }
                }
            }
        }

        // Custom handling for logout item in drawer
        binding.navView.setNavigationItemSelectedListener { menuItem ->
            binding.drawerLayout.closeDrawer(GravityCompat.START)
            when (menuItem.itemId) {
                R.id.nav_logout -> {
                    lifecycleScope.launch {
                        (application as HskQuestApp).container.authRepository.logout()
                        val navOptions = NavOptions.Builder()
                            .setPopUpTo(R.id.nav_graph, true)
                            .build()
                        navController.navigate(R.id.loginFragment, null, navOptions)
                    }
                    true
                }
                else -> {
                    NavigationUI.onNavDestinationSelected(menuItem, navController)
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
                binding.drawerLayout.setDrawerLockMode(DrawerLayout.LOCK_MODE_UNLOCKED)
                slideShowBottomNav()

                // Keep checked bottom nav tab in sync with current top-level tab
                when (destination.id) {
                    R.id.homeFragment,
                    R.id.worldMapFragment,
                    R.id.petFragment,
                    R.id.characterFragment -> {
                        binding.bottomNav.menu.findItem(destination.id)?.isChecked = true
                    }
                }
            }
        }
    }

    private var isBottomNavSlidDown: Boolean = false

    fun setBottomNavVisible(visible: Boolean) {
        if (visible) {
            isBottomNavSlidDown = false
            binding.bottomNav.translationY = 0f
            binding.bottomNav.visibility = View.VISIBLE
        } else {
            isBottomNavSlidDown = false
            binding.bottomNav.translationY = 0f
            binding.bottomNav.visibility = View.GONE
        }
    }

    fun slideHideBottomNav() {
        if (isBottomNavSlidDown || binding.bottomNav.visibility != View.VISIBLE) return
        isBottomNavSlidDown = true
        val slideDistance = binding.bottomNav.height.toFloat() + binding.mainContentContainer.paddingBottom.toFloat()
        binding.bottomNav.animate()
            .translationY(slideDistance)
            .setDuration(220L)
            .setInterpolator(AccelerateDecelerateInterpolator())
            .start()
    }

    fun slideShowBottomNav() {
        if (!isBottomNavSlidDown && binding.bottomNav.translationY == 0f) return
        isBottomNavSlidDown = false
        binding.bottomNav.animate()
            .translationY(0f)
            .setDuration(220L)
            .setInterpolator(AccelerateDecelerateInterpolator())
            .start()
    }

    fun attachScrollListener(scrollView: View) {
        scrollView.setOnScrollChangeListener { _, _, scrollY, _, oldScrollY ->
            val dy = scrollY - oldScrollY
            if (dy > 14) {
                slideHideBottomNav()
            } else if (dy < -14 || scrollY <= 10) {
                slideShowBottomNav()
            }
        }
    }

    fun attachRecyclerScrollListener(recyclerView: RecyclerView) {
        recyclerView.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                if (dy > 14) {
                    slideHideBottomNav()
                } else if (dy < -14 || !recyclerView.canScrollVertically(-1)) {
                    slideShowBottomNav()
                }
            }
        })
    }

    /**
     * Applies system status bar, navigation bar, cutout, and IME insets to the main content
     * container and navigation drawer so top/bottom controls never overlap system bars.
     */
    private fun setupWindowInsets() {
        val extraBufferPx = (resources.displayMetrics.density * 6).toInt()

        ViewCompat.setOnApplyWindowInsetsListener(binding.mainContentContainer) { view, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or
                    WindowInsetsCompat.Type.displayCutout() or
                    WindowInsetsCompat.Type.ime()
            )
            view.updatePadding(
                left = bars.left,
                top = bars.top + extraBufferPx,
                right = bars.right,
                bottom = bars.bottom + extraBufferPx,
            )
            insets
        }

        ViewCompat.setOnApplyWindowInsetsListener(binding.navView) { view, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or
                    WindowInsetsCompat.Type.displayCutout()
            )
            view.updatePadding(
                top = bars.top + extraBufferPx,
                bottom = bars.bottom + extraBufferPx,
            )
            insets
        }

        // Prevent BottomNavigationView from double-padding bottom system bar insets
        ViewCompat.setOnApplyWindowInsetsListener(binding.bottomNav) { _, insets ->
            insets
        }
    }
}