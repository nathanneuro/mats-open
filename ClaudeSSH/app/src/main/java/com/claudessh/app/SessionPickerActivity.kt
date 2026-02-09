package com.claudessh.app

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.claudessh.app.databinding.ActivitySessionPickerBinding
import com.claudessh.app.models.TmuxSession
import com.claudessh.app.ssh.SshManager
import kotlinx.coroutines.launch

class SessionPickerActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySessionPickerBinding
    private val sessions = mutableListOf<TmuxSession>()
    private lateinit var adapter: TmuxSessionAdapter

    // In production, this would be passed via a shared ViewModel or service locator.
    // For now, the activity creates a temporary reference.
    private var sshManager: SshManager? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySessionPickerBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        adapter = TmuxSessionAdapter(
            sessions = sessions,
            onAttach = { session -> attachSession(session) }
        )

        binding.recyclerView.layoutManager = LinearLayoutManager(this)
        binding.recyclerView.adapter = adapter

        binding.fabNewSession.setOnClickListener {
            showNewSessionDialog()
        }

        binding.buttonRefresh.setOnClickListener {
            loadSessions()
        }

        loadSessions()
    }

    private fun loadSessions() {
        binding.progressBar.visibility = View.VISIBLE
        binding.emptyView.visibility = View.GONE

        lifecycleScope.launch {
            val manager = sshManager
            if (manager == null || !manager.isConnected()) {
                binding.progressBar.visibility = View.GONE
                binding.emptyView.text = getString(R.string.not_connected_tmux)
                binding.emptyView.visibility = View.VISIBLE
                return@launch
            }

            val result = manager.listTmuxSessions()
            binding.progressBar.visibility = View.GONE

            result.onSuccess { list ->
                sessions.clear()
                sessions.addAll(list)
                adapter.notifyDataSetChanged()
                binding.emptyView.visibility = if (list.isEmpty()) View.VISIBLE else View.GONE
            }
            result.onFailure { error ->
                Toast.makeText(
                    this@SessionPickerActivity,
                    "Failed to list sessions: ${error.message}",
                    Toast.LENGTH_LONG
                ).show()
                binding.emptyView.visibility = View.VISIBLE
            }
        }
    }

    private fun attachSession(session: TmuxSession) {
        sshManager?.attachTmuxSession(session.name)
        finish()
    }

    private fun showNewSessionDialog() {
        val editText = android.widget.EditText(this).apply {
            hint = "Session name"
            setText("claude")
        }

        AlertDialog.Builder(this, R.style.Theme_ClaudeSSH_Dialog)
            .setTitle(R.string.new_tmux_session)
            .setView(editText)
            .setPositiveButton(R.string.create_with_claude) { _, _ ->
                val name = editText.text.toString().ifBlank { "claude" }
                sshManager?.createTmuxSessionWithClaude(name)
                finish()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }
}

class TmuxSessionAdapter(
    private val sessions: List<TmuxSession>,
    private val onAttach: (TmuxSession) -> Unit
) : RecyclerView.Adapter<TmuxSessionAdapter.ViewHolder>() {

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val name: TextView = view.findViewById(R.id.sessionName)
        val details: TextView = view.findViewById(R.id.sessionDetails)
        val status: View = view.findViewById(R.id.sessionStatus)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_tmux_session, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val session = sessions[position]
        holder.name.text = session.name
        holder.details.text = "${session.windows} window(s)"
        holder.status.setBackgroundResource(
            if (session.attached) R.drawable.status_connected else R.drawable.status_disconnected
        )
        holder.itemView.setOnClickListener { onAttach(session) }
    }

    override fun getItemCount() = sessions.size
}
