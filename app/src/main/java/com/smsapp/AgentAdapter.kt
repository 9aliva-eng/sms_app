package com.smsapp

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView

/**
 * RecyclerView adapter that shows each agent's status in the Admin screen.
 * Supports "start agent" and "stop agent" button clicks.
 */
class AgentAdapter(
    private val onStartClick: (Agent) -> Unit,
    private val onStopClick: (Agent) -> Unit
) : ListAdapter<Agent, AgentAdapter.AgentViewHolder>(AgentDiffCallback()) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): AgentViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_agent, parent, false)
        return AgentViewHolder(view)
    }

    override fun onBindViewHolder(holder: AgentViewHolder, position: Int) {
        holder.bind(getItem(position), onStartClick, onStopClick)
    }

    class AgentViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val tvName: TextView          = itemView.findViewById(R.id.tvAgentName)
        private val tvSim1: TextView          = itemView.findViewById(R.id.tvSim1Status)
        private val tvSim2: TextView          = itemView.findViewById(R.id.tvSim2Status)
        private val tvLastSeen: TextView      = itemView.findViewById(R.id.tvLastSeen)
        private val tvAgentStatus: TextView   = itemView.findViewById(R.id.tvAgentStatus)
        private val btnStartAgent: Button     = itemView.findViewById(R.id.btnStartAgent)
        private val btnStopAgent: Button      = itemView.findViewById(R.id.btnStopAgent)

        fun bind(agent: Agent, onStartClick: (Agent) -> Unit, onStopClick: (Agent) -> Unit) {
            tvName.text = agent.name
            tvSim1.text = buildString {
                append("SIM1: ${agent.sim1Number.ifEmpty { "—" }}")
                append("  остаток: ${agent.sim1Balance}")
                append("  отправлено: ${agent.sim1Sent}")
            }
            tvSim2.text = buildString {
                append("SIM2: ${agent.sim2Number.ifEmpty { "—" }}")
                append("  остаток: ${agent.sim2Balance}")
                append("  отправлено: ${agent.sim2Sent}")
            }
            tvLastSeen.text = "Активность: ${agent.lastSeen.ifEmpty { "—" }}"
            tvAgentStatus.text = "Статус: ${agent.agentStatus}"

            // Highlight active/stopped
            val color = if (agent.agentStatus == Agent.STATUS_ACTIVE) {
                itemView.context.getColor(R.color.color_start)
            } else {
                itemView.context.getColor(R.color.color_stop)
            }
            tvAgentStatus.setTextColor(color)

            // Start button
            btnStartAgent.setOnClickListener { onStartClick(agent) }
            btnStartAgent.visibility = if (agent.agentStatus == Agent.STATUS_ACTIVE) View.GONE else View.VISIBLE

            // Stop button
            btnStopAgent.setOnClickListener { onStopClick(agent) }
            btnStopAgent.visibility = if (agent.agentStatus == Agent.STATUS_ACTIVE) View.VISIBLE else View.GONE
        }
    }

    class AgentDiffCallback : DiffUtil.ItemCallback<Agent>() {
        override fun areItemsTheSame(oldItem: Agent, newItem: Agent) = oldItem.rowIndex == newItem.rowIndex
        override fun areContentsTheSame(oldItem: Agent, newItem: Agent) = oldItem == newItem
    }
}
