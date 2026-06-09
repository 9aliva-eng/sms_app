package com.smsapp

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import com.smsapp.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var prefs: PreferencesManager

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        val denied = results.filterValues { !it }.keys
        if (denied.isNotEmpty()) {
            Toast.makeText(
                this,
                "Некоторые разрешения не предоставлены. Приложение может работать некорректно.",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        prefs = PreferencesManager(this)

        setSupportActionBar(binding.toolbar)

        requestRequiredPermissions()

        binding.bottomNav.setOnItemSelectedListener { item ->
            when (item.itemId) {
                R.id.nav_main     -> { showMainScreen(); true }
                R.id.nav_settings -> { showFragment(SettingsFragment(), "Настройки"); true }
                else              -> false
            }
        }

        if (!prefs.isConfigured()) {
            showFragment(SettingsFragment(), "Настройки")
            binding.bottomNav.selectedItemId = R.id.nav_settings
        } else {
            showMainScreen()

            // ── Запускаем фоновый Worker для удалённого управления ──────
            AgentCheckWorker.schedule(this)
        }
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_main, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_settings -> {
                showFragment(SettingsFragment(), "Настройки")
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    private fun showMainScreen() {
        if (prefs.isAdminMode) {
            showFragment(AdminFragment(), "Режим АДМИН")
        } else {
            showFragment(AgentFragment(), "Режим АГЕНТ")
        }
    }

    fun showFragment(fragment: Fragment, title: String) {
        supportActionBar?.title = title
        supportFragmentManager.beginTransaction()
            .replace(R.id.fragment_container, fragment)
            .commit()
    }

    private fun requestRequiredPermissions() {
        val required = mutableListOf(
            Manifest.permission.SEND_SMS,
            Manifest.permission.READ_PHONE_STATE,
            Manifest.permission.READ_PHONE_NUMBERS
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            required.add(Manifest.permission.POST_NOTIFICATIONS)
        }

        val missing = required.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (missing.isNotEmpty()) {
            permissionLauncher.launch(missing.toTypedArray())
        }
    }
}
