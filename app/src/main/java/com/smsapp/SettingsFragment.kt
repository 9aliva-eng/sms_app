package com.smsapp

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.Fragment
import com.smsapp.databinding.FragmentSettingsBinding

/**
 * Settings screen:
 *  - Mode toggle: Agent / Admin
 *  - Phone name
 *  - Google Sheets ID
 *  - Google Sheets API key
 *  - SIM mode (AUTO / SIM1 / SIM2)
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
