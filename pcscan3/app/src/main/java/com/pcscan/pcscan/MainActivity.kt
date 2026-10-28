package com.pcscan.pcscan

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.navigation.fragment.NavHostFragment
import com.google.firebase.auth.FirebaseAuth

class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        ThemeManager.initialize(this)
        ThemeManager.applyCurrentMode()
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        if (savedInstanceState != null) return

        val navHostFragment = supportFragmentManager.findFragmentById(R.id.nav_host_fragment) as NavHostFragment
        val navController = navHostFragment.navController
        val navGraph = navController.navInflater.inflate(R.navigation.nav_graph)
        navGraph.setStartDestination(resolveStartDestination())
        navController.graph = navGraph
    }

    private fun resolveStartDestination(): Int {
        val intentDestination = intent.getIntExtra(SplashActivity.EXTRA_START_DESTINATION, 0)
        if (intentDestination != 0) {
            return intentDestination
        }

        val currentUser = FirebaseAuth.getInstance().currentUser
        return if (currentUser != null && currentUser.isEmailVerified) {
            R.id.dashboardFragment
        } else {
            R.id.loginFragment
        }
    }
}
