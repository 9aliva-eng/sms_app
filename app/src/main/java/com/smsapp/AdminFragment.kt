package com.smsapp

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.smsapp.databinding.FragmentAdminBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class AdminFragment : Fragment() {

    private var _binding: FragmentAdminBinding? = null
    private val binding get() = _binding!!
    private lateinit var prefs: PreferencesManager
    private lateinit var adapter: AgentAdapter

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentAdminBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        prefs = PreferencesManager(requireContext())

        // Адаптер с двумя коллбэками: Старт и Стоп
        adapter = AgentAdapter(
            onStartClick = { agent -> startAgent(agent) },
            onStopClick  = { agent -> stopAgent(agent) }
        )

        binding.rvAgents.layoutManager = LinearLayoutManager(requireContext())
        binding.rvAgents.adapter = adapter

        binding.btnRefresh.setOnClickListener { loadData() }
        binding.btnStopAll.setOnClickListener { stopAllAgents() }

        loadData()
    }

    private fun loadData() {
        if (!prefs.isConfigured()) {
            Toast.makeText(context, "Настройки не заданы!", Toast.LENGTH_SHORT).show()
            return
        }
        binding.progressBar.visibility = View.VISIBLE
        val repo = SheetsRepository(prefs.sheetsId, prefs.apiKey, prefs.scriptUrl)

        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val agentsResult = withContext(Dispatchers.IO) { repo.getAllAgents() }
                val agents = agentsResult.getOrDefault(emptyList())
                adapter.submitList(agents)

                val stats = withContext(Dispatchers.IO) { repo.getRecipientStats() }
                val sent  = stats[Recipient.STATUS_SENT] ?: 0
                val total = stats.values.sum()
                binding.tvProgress.text = "Прогресс: $sent / $total"

                val active = agents.count { it.agentStatus == Agent.STATUS_ACTIVE }
                binding.tvActiveAgents.text = "Активных агентов: $active"

                val totalSent = agents.sumOf { it.sim1Sent + it.sim2Sent }
                binding.tvTotalSent.text = "Всего отправлено: $totalSent"

            } catch (e: Exception) {
                Toast.makeText(context, "Ошибка загрузки: ${e.message}", Toast.LENGTH_SHORT).show()
            } finally {
                binding.progressBar.visibility = View.GONE
            }
        }
    }

    // ─── Старт агента ───────────────────────────────────────────────────
    private fun startAgent(agent: Agent) {
        if (!prefs.isConfigured()) return
        binding.progressBar.visibility = View.VISIBLE
        val repo = SheetsRepository(prefs.sheetsId, prefs.apiKey, prefs.scriptUrl)

        viewLifecycleOwner.lifecycleScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    repo.updateAgentStats(
                        agent.rowIndex,
                        agent.sim1Sent,
                        agent.sim2Sent,
                        Agent.STATUS_ACTIVE  // устанавливаем "активен"
                    )
                }
                Toast.makeText(context, "Команда «Старт» отправлена агенту ${agent.name}", Toast.LENGTH_SHORT).show()
                loadData()
            } catch (e: Exception) {
                Toast.makeText(context, "Ошибка: ${e.message}", Toast.LENGTH_SHORT).show()
            } finally {
                binding.progressBar.visibility = View.GONE
            }
        }
    }

    // ─── Стоп агента ────────────────────────────────────────────────────
    private fun stopAgent(agent: Agent) {
        if (!prefs.isConfigured()) return
        binding.progressBar.visibility = View.VISIBLE
        val repo = SheetsRepository(prefs.sheetsId, prefs.apiKey, prefs.scriptUrl)

        viewLifecycleOwner.lifecycleScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    repo.updateAgentStats(
                        agent.rowIndex,
                        agent.sim1Sent,
                        agent.sim2Sent,
                        Agent.STATUS_STOPPED  // устанавливаем "остановлен"
                    )
                }
                Toast.makeText(context, "Команда «Стоп» отправлена агенту ${agent.name}", Toast.LENGTH_SHORT).show()
                loadData()
            } catch (e: Exception) {
                Toast.makeText(context, "Ошибка: ${e.message}", Toast.LENGTH_SHORT).show()
            } finally {
                binding.progressBar.visibility = View.GONE
            }
        }
    }

    private fun stopAllAgents() {
        if (!prefs.isConfigured()) return
        binding.progressBar.visibility = View.VISIBLE
        val repo = SheetsRepository(prefs.sheetsId, prefs.apiKey, prefs.scriptUrl)

        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val agents = withContext(Dispatchers.IO) {
                    repo.getAllAgents().getOrDefault(emptyList())
                }
                withContext(Dispatchers.IO) {
                    agents.forEach { agent ->
                        repo.updateAgentStats(
                            agent.rowIndex,
                            agent.sim1Sent,
                            agent.sim2Sent,
                            Agent.STATUS_STOPPED
                        )
                    }
                }
                Toast.makeText(context, "Команда «стоп» отправлена всем агентам", Toast.LENGTH_SHORT).show()
                loadData()
            } catch (e: Exception) {
                Toast.makeText(context, "Ошибка: ${e.message}", Toast.LENGTH_SHORT).show()
            } finally {
                binding.progressBar.visibility = View.GONE
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
