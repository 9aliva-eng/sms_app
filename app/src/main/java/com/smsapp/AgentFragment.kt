package com.smsapp

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.Fragment
import com.smsapp.databinding.FragmentAgentBinding

/**
 * Minimal agent UI:
 *  - Start / Stop button
 *  - Counter "sent today"
 *  - Status label
 *  - SIM mode selector (AUTO / SIM1 / SIM2)
 */
class AgentFragment : Fragment() {

    private var _binding: FragmentAgentBinding? = null
    private val binding get() = _binding!!
    private lateinit var prefs: PreferencesManager

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentAgentBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        prefs = PreferencesManager(requireContext())

        // ── Observe LiveData ──────────────────────────────────────────────
        SmsServiceState.isRunning.observe(viewLifecycleOwner) { running ->
            updateUi(running)
        }
        SmsServiceState.sim1Sent.observe(viewLifecycleOwner) { s1 ->
            val s2 = SmsServiceState.sim2Sent.value ?: 0
            binding.tvSentCount.text = "Отправлено сегодня: ${s1 + s2}"
            binding.tvSim1Sent.text  = "SIM1: $s1 СМС"
        }
        SmsServiceState.sim2Sent.observe(viewLifecycleOwner) { s2 ->
            val s1 = SmsServiceState.sim1Sent.value ?: 0
            binding.tvSentCount.text = "Отправлено сегодня: ${s1 + s2}"
            binding.tvSim2Sent.text  = "SIM2: $s2 СМС"
        }
        SmsServiceState.statusText.observe(viewLifecycleOwner) { text ->
            binding.tvStatus.text = text
        }
        SmsServiceState.event.observe(viewLifecycleOwner) { event ->
            when (event) {
                is ServiceEvent.AllSimsExhausted ->
                    Toast.makeText(context, "Все SIM исчерпаны!", Toast.LENGTH_LONG).show()
                is ServiceEvent.SmsError ->
                    binding.tvStatus.text = "Ошибка: ${event.phone}"
                else -> {}
            }
        }

        // ── SIM mode selector ─────────────────────────────────────────────
        binding.rgSimMode.check(
            when (prefs.simMode) {
                SimMode.AUTO -> R.id.rbSimAuto
                SimMode.SIM1 -> R.id.rbSim1
                SimMode.SIM2 -> R.id.rbSim2
            }
        )
        binding.rgSimMode.setOnCheckedChangeListener { _, checkedId ->
            prefs.simMode = when (checkedId) {
                R.id.rbSim1 -> SimMode.SIM1
                R.id.rbSim2 -> SimMode.SIM2
                else        -> SimMode.AUTO
            }
        }

        // ── Start / Stop button ───────────────────────────────────────────
        binding.btnStartStop.setOnClickListener {
            val running = SmsServiceState.isRunning.value == true
            if (running) {
                requireContext().startService(SmsService.stopIntent(requireContext()))
            } else {
                if (!prefs.isConfigured()) {
                    Toast.makeText(context, "Сначала заполните Настройки!", Toast.LENGTH_SHORT).show()
                    (activity as? MainActivity)?.showFragment(SettingsFragment(), "Настройки")
                    return@setOnClickListener
                }
                requireContext().startForegroundService(SmsService.startIntent(requireContext()))
            }
        }

        // Set phone name label
        binding.tvPhoneName.text = "Телефон: ${prefs.phoneName}"
    }

    private fun updateUi(running: Boolean) {
        if (running) {
            binding.btnStartStop.text = "СТОП"
            binding.btnStartStop.setBackgroundColor(resources.getColor(R.color.color_stop, null))
            binding.tvStatus.text = "Работает..."
            binding.indicatorDot.setBackgroundResource(R.drawable.dot_green)
        } else {
            binding.btnStartStop.text = "СТАРТ"
            binding.btnStartStop.setBackgroundColor(resources.getColor(R.color.color_start, null))
            binding.tvStatus.text = "Остановлено"
            binding.indicatorDot.setBackgroundResource(R.drawable.dot_red)
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
