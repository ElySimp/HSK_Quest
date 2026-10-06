package com.faldo.hsk_quest

import android.os.Bundle
import android.view.View
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.GravityCompat
import androidx.drawerlayout.widget.DrawerLayout
import androidx.lifecycle.lifecycleScope
import androidx.navigation.NavController
import androidx.navigation.fragment.NavHostFragment
import androidx.navigation.ui.AppBarConfiguration
import androidx.navigation.ui.NavigationUI
import androidx.navigation.ui.setupWithNavController
import com.faldo.hsk_quest.databinding.ActivityMainBinding
import kotlinx.coroutines.launch

/**
 * Single activity hosting NavHostFragment with DrawerLayout and MaterialToolbar.
 * Controls top-level navigation, hamburger menu, and immersive screens (battle/auth).
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

        // Custom handling for logout item in drawer
        binding.navView.setNavigationItemSelectedListener { menuItem ->
            binding.drawerLayout.closeDrawer(GravityCompat.START)
            when (menuItem.itemId) {
                R.id.nav_logout -> {
                    lifecycleScope.launch {
                        (application as HskQuestApp).container.authRepository.logout()
                        navController.navigate(R.id.loginFragment)
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
                binding.drawerLayout.setDrawerLockMode(DrawerLayout.LOCK_MODE_LOCKED_CLOSED)
            } else {
                binding.toolbar.visibility = View.VISIBLE
                binding.drawerLayout.setDrawerLockMode(DrawerLayout.LOCK_MODE_UNLOCKED)
            }
        }
    }
}