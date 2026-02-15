package com.claudessh.app

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.claudessh.app.data.ConnectionRepository
import com.claudessh.app.databinding.ActivityConnectionBinding
import com.claudessh.app.databinding.DialogConnectionBinding
import com.claudessh.app.models.AuthMethod
import com.claudessh.app.models.ConnectionProfile
import kotlinx.coroutines.launch
import java.io.File

class ConnectionActivity : AppCompatActivity() {

    private lateinit var binding: ActivityConnectionBinding
    private val connectionRepo by lazy { ConnectionRepository(this) }
    private val connections = mutableListOf<ConnectionProfile>()
    private lateinit var adapter: ConnectionAdapter

    // State for the file picker callback
    private var pendingKeyFilename: String? = null
    private var activeDialogBinding: DialogConnectionBinding? = null

    private val pickKeyFile = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        uri ?: return@registerForActivityResult
        val dialogBinding = activeDialogBinding ?: return@registerForActivityResult

        val filename = "key_${System.currentTimeMillis()}"
        val keysDir = File(filesDir, "keys")
        keysDir.mkdirs()
        val destFile = File(keysDir, filename)

        try {
            contentResolver.openInputStream(uri)?.use { input ->
                destFile.outputStream().use { output ->
                    input.copyTo(output)
                }
            }
            pendingKeyFilename = filename
            dialogBinding.textKeyStatus.text = getString(R.string.key_imported)
            dialogBinding.textKeyStatus.setTextColor(getColor(R.color.status_connected))
        } catch (e: Exception) {
            Toast.makeText(this, "Failed to import key: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityConnectionBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        adapter = ConnectionAdapter(
            connections = connections,
            onConnect = { profile -> connectTo(profile) },
            onEdit = { profile -> showEditDialog(profile) },
            onDelete = { profile -> deleteConnection(profile) }
        )

        binding.recyclerView.layoutManager = LinearLayoutManager(this)
        binding.recyclerView.adapter = adapter

        binding.fabAdd.setOnClickListener {
            showEditDialog(null)
        }

        loadConnections()
    }

    private fun loadConnections() {
        lifecycleScope.launch {
            connections.clear()
            connections.addAll(connectionRepo.getConnections())
            adapter.notifyDataSetChanged()
            binding.emptyView.visibility = if (connections.isEmpty()) View.VISIBLE else View.GONE
        }
    }

    private fun connectTo(profile: ConnectionProfile) {
        val intent = Intent(this, MainActivity::class.java).apply {
            putExtra(MainActivity.EXTRA_CONNECTION_ID, profile.id)
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        startActivity(intent)
    }

    private fun showEditDialog(existing: ConnectionProfile?) {
        val dialogBinding = DialogConnectionBinding.inflate(layoutInflater)
        activeDialogBinding = dialogBinding
        pendingKeyFilename = existing?.privateKeyPath

        existing?.let { profile ->
            dialogBinding.editName.setText(profile.name)
            dialogBinding.editHost.setText(profile.host)
            dialogBinding.editPort.setText(profile.port.toString())
            dialogBinding.editUsername.setText(profile.username)
            dialogBinding.editPassword.setText(profile.password ?: "")
            dialogBinding.editStartCommand.setText(profile.startCommand ?: "")
            dialogBinding.switchAutoTmux.isChecked = profile.autoAttachTmux
            dialogBinding.radioKey.isChecked = profile.authMethod == AuthMethod.KEY
            dialogBinding.radioPassword.isChecked = profile.authMethod == AuthMethod.PASSWORD

            if (profile.authMethod == AuthMethod.KEY && profile.privateKeyPath != null) {
                val keyFile = File(File(filesDir, "keys"), profile.privateKeyPath)
                if (keyFile.exists()) {
                    dialogBinding.textKeyStatus.text = getString(R.string.key_imported)
                    dialogBinding.textKeyStatus.setTextColor(getColor(R.color.status_connected))
                }
            }
        }

        dialogBinding.buttonBrowseKey.setOnClickListener {
            pickKeyFile.launch(arrayOf("*/*"))
        }

        dialogBinding.authMethodGroup.setOnCheckedChangeListener { _, checkedId ->
            when (checkedId) {
                R.id.radioKey -> {
                    dialogBinding.layoutKeyPath.visibility = View.VISIBLE
                    dialogBinding.layoutPassword.visibility = View.GONE
                }
                R.id.radioPassword -> {
                    dialogBinding.layoutKeyPath.visibility = View.GONE
                    dialogBinding.layoutPassword.visibility = View.VISIBLE
                }
            }
        }

        AlertDialog.Builder(this, R.style.Theme_ClaudeSSH_Dialog)
            .setTitle(if (existing != null) R.string.edit_connection else R.string.new_connection)
            .setView(dialogBinding.root)
            .setPositiveButton(R.string.save) { _, _ ->
                val profile = ConnectionProfile(
                    id = existing?.id ?: java.util.UUID.randomUUID().toString(),
                    name = dialogBinding.editName.text.toString().ifBlank { "Server" },
                    host = dialogBinding.editHost.text.toString(),
                    port = dialogBinding.editPort.text.toString().toIntOrNull() ?: 22,
                    username = dialogBinding.editUsername.text.toString(),
                    authMethod = if (dialogBinding.radioKey.isChecked) AuthMethod.KEY else AuthMethod.PASSWORD,
                    privateKeyPath = if (dialogBinding.radioKey.isChecked) pendingKeyFilename else null,
                    password = dialogBinding.editPassword.text.toString().ifBlank { null },
                    autoAttachTmux = dialogBinding.switchAutoTmux.isChecked,
                    tmuxSessionName = "",
                    startCommand = dialogBinding.editStartCommand.text.toString().ifBlank { null }
                )
                saveConnection(profile)
                activeDialogBinding = null
            }
            .setNegativeButton(R.string.cancel) { _, _ ->
                activeDialogBinding = null
            }
            .show()
    }

    private fun saveConnection(profile: ConnectionProfile) {
        lifecycleScope.launch {
            connectionRepo.saveConnection(profile)
            loadConnections()
            Toast.makeText(this@ConnectionActivity, R.string.connection_saved, Toast.LENGTH_SHORT).show()
        }
    }

    private fun deleteConnection(profile: ConnectionProfile) {
        AlertDialog.Builder(this)
            .setTitle(R.string.delete_connection)
            .setMessage(getString(R.string.delete_connection_confirm, profile.name))
            .setPositiveButton(R.string.delete) { _, _ ->
                lifecycleScope.launch {
                    connectionRepo.deleteConnection(profile.id)
                    loadConnections()
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }
}

class ConnectionAdapter(
    private val connections: List<ConnectionProfile>,
    private val onConnect: (ConnectionProfile) -> Unit,
    private val onEdit: (ConnectionProfile) -> Unit,
    private val onDelete: (ConnectionProfile) -> Unit
) : RecyclerView.Adapter<ConnectionAdapter.ViewHolder>() {

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val name: TextView = view.findViewById(R.id.connectionName)
        val details: TextView = view.findViewById(R.id.connectionDetails)
        val tmuxInfo: TextView = view.findViewById(R.id.connectionTmux)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_connection, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val profile = connections[position]
        holder.name.text = profile.name
        holder.details.text = "${profile.username}@${profile.host}:${profile.port}"
        holder.tmuxInfo.text = if (profile.autoAttachTmux) {
            "tmux: ${profile.tmuxSessionName}"
        } else {
            "tmux: disabled"
        }

        holder.itemView.setOnClickListener { onConnect(profile) }
        holder.itemView.setOnLongClickListener {
            AlertDialog.Builder(holder.itemView.context)
                .setTitle(profile.name)
                .setItems(arrayOf("Edit", "Delete")) { _, which ->
                    when (which) {
                        0 -> onEdit(profile)
                        1 -> onDelete(profile)
                    }
                }
                .show()
            true
        }
    }

    override fun getItemCount() = connections.size
}
