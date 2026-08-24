package com.pcscan.pcscan

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.firebase.auth.FirebaseAuth
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class SplashActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_splash)

        lifecycleScope.launch {
            delay(SPLASH_DURATION_MS)
            startActivity(
                Intent(this@SplashActivity, MainActivity::class.java).apply {
                    putExtra(EXTRA_START_DESTINATION, resolveStartDestination())
                }
            )
            finish()
        }
    }

    private fun resolveStartDestination(): Int {
        val currentUser = FirebaseAuth.getInstance().currentUser
        return if (currentUser != null && currentUser.isEmailVerified) {
            R.id.dashboardFragment
        } else {
            R.id.loginFragment
        }
    }

    companion object {
        const val EXTRA_START_DESTINATION = "startDestination"
        private const val SPLASH_DURATION_MS = 1800L
    }
}
