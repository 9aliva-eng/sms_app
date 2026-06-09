package com.smsapp

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.smsapp.databinding.FragmentSettingsBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Settings screen:
 *  - Mode toggle: Agent / Admin
 *  - Phone name
 *  - Google Sheets ID
 *  - Google Sheets API key
 *  - SIM mode (AUTO / SIM1 / SIM2)
 *  - Check for updates
 */
class SettingsFragment : Fragment() {

    private var _binding: FragmentSettingsBinding? = null
    private val binding get() = _binding!!
    private lateinit var prefs: PreferencesManager

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentSettingsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        prefs = PreferencesManager(requireContext())

        // Populate fields from saved preferences
        binding.switchAdminMode.isChecked  = prefs.isAdminMode
        binding.etPhoneName.setText(prefs.phoneName)
        binding.etSheetsId.setText(prefs.sheetsId)
        binding.etApiKey.setText(prefs.apiKey)
        binding.etScriptUrl.setText(prefs.scriptUrl)

        binding.rgSimMode.check(
            when (prefs.simMode) {
                SimMode.AUTO -> R.id.rbSimAuto
                SimMode.SIM1 -> R.id.rbSim1
                SimMode.SIM2 -> R.id.rbSim2
            }
        )

        // Mode label
        updateModeLabel(prefs.isAdminMode)
        binding.switchAdminMode.setOnCheckedChangeListener { _, isChecked ->
            updateModeLabel(isChecked)
        }

        binding.btnSave.setOnClickListener { saveSettings() }

        // ── Check for updates ────────────────────────────────────────────
        binding.btnCheckUpdate.setOnClickListener {
            Toast.makeText(context, "Проверка обновлений...", Toast.LENGTH_SHORT).show()

            lifecycleScope.launch {
                try {
                    val update = withContext(Dispatchers.IO) {
                        try {
                            UpdateChecker(requireContext()).checkForUpdate()
                        } catch (e: Exception) {
                            Log.e("UpdateChecker", "checkForUpdate error", e)
                            null
                        }
                    }

                    if (update == null) {
                        Toast.makeText(context, "У вас последняя версия", Toast.LENGTH_SHORT).show()
                        return@launch
                    }

                    // Открываем страницу релиза на GitHub в браузере
                    val releaseUrl = "https://github.com/9aliva-eng/sms_app/releases/latest"
                    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(releaseUrl))
                    startActivity(intent)

                    Toast.makeText(context, "Скачайте APK с GitHub", Toast.LENGTH_LONG).show()
                } catch (e: Exception) {
                    Log.e("UpdateChecker", "Unexpected error", e)
                    Toast.makeText(context, "Ошибка: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }

        // Устанавливаем реальную версию из build.gradle
        try {
            val pkgInfo = requireContext().packageManager.getPackageInfo(requireContext().packageName, 0)
            binding.tvVersion.text = "Версия: ${pkgInfo.versionName}"
        } catch (e: Exception) {
            binding.tvVersion.text = "Версия: 1.9.2"
        }
    }

    private fun updateModeLabel(isAdmin: Boolean) {
        binding.tvModeLabel.text = if (isAdmin) "Режим: АДМИН" else "Режим: АГЕНТ"
    }

    private fun saveSettings() {
        val phoneName = binding.etPhoneName.text.toString().trim()
        val sheetsId  = binding.etSheetsId.text.toString().trim()
        val apiKey    = binding.etApiKey.text.toString().trim()
        val scriptUrl = binding.etScriptUrl.text.toString().trim()

        if (phoneName.isEmpty()) {
            binding.etPhoneName.error = "Обязательное поле"
            return
        }
        if (sheetsId.isEmpty()) {
            binding.etSheetsId.error = "Обязательное поле"
            return
        }
        if (apiKey.isEmpty()) {
            binding.etApiKey.error = "Обязательное поле"
            return
        }
        if (scriptUrl.isEmpty()) {
            binding.etScriptUrl.error = "Обязательное поле"
            return
        }

        prefs.isAdminMode = binding.switchAdminMode.isChecked
        prefs.phoneName   = phoneName
        prefs.sheetsId    = sheetsId
        prefs.apiKey      = apiKey
        prefs.scriptUrl   = scriptUrl
        prefs.simMode     = when (binding.rgSimMode.checkedRadioButtonId) {
            R.id.rbSim1 -> SimMode.SIM1
            R.id.rbSim2 -> SimMode.SIM2
            else        -> SimMode.AUTO
        }

        Toast.makeText(context, "Настройки сохранены", Toast.LENGTH_SHORT).show()

        // Navigate to appropriate main screen
        val mainActivity = activity as? MainActivity ?: return
        if (prefs.isAdminMode) {
            mainActivity.showFragment(AdminFragment(), "Режим АДМИН")
        } else {
            mainActivity.showFragment(AgentFragment(), "Режим АГЕНТ")
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
