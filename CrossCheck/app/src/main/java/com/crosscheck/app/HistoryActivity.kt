package com.crosscheck.app

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.crosscheck.app.data.ChatRepository
import com.crosscheck.app.models.Chat
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class HistoryActivity : AppCompatActivity() {

    private lateinit var chatRepository: ChatRepository
    private lateinit var recyclerView: RecyclerView
    private lateinit var emptyView: TextView
    private lateinit var adapter: ChatAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_history)

        chatRepository = ChatRepository(this)

        val toolbar = findViewById<Toolbar>(R.id.toolbar)
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = getString(R.string.history)

        recyclerView = findViewById(R.id.chatRecyclerView)
        emptyView = findViewById(R.id.emptyView)

        recyclerView.layoutManager = LinearLayoutManager(this)
        adapter = ChatAdapter(
            onChatClick = { chat ->
                // Switch to this chat and go back to main
                lifecycleScope.launch {
                    chatRepository.setCurrentChat(chat.id)
                    finish()
                }
            },
            onDeleteClick = { chat ->
                showDeleteConfirmation(chat)
            }
        )
        recyclerView.adapter = adapter

        observeChats()
    }

    private fun observeChats() {
        lifecycleScope.launch {
            chatRepository.chats.collect { chats ->
                if (chats.isEmpty()) {
                    recyclerView.visibility = View.GONE
                    emptyView.visibility = View.VISIBLE
                } else {
                    recyclerView.visibility = View.VISIBLE
                    emptyView.visibility = View.GONE
                    adapter.submitList(chats)
                }
            }
        }
    }

    private fun showDeleteConfirmation(chat: Chat) {
        AlertDialog.Builder(this)
            .setTitle(R.string.delete)
            .setMessage("Delete chat \"${chat.getDisplayName()}\"?")
            .setPositiveButton(R.string.delete) { _, _ ->
                lifecycleScope.launch {
                    chatRepository.deleteChat(chat.id)
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    class ChatAdapter(
        private val onChatClick: (Chat) -> Unit,
        private val onDeleteClick: (Chat) -> Unit
    ) : RecyclerView.Adapter<ChatAdapter.ChatViewHolder>() {

        private var chats = listOf<Chat>()

        fun submitList(newChats: List<Chat>) {
            chats = newChats
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ChatViewHolder {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_chat, parent, false)
            return ChatViewHolder(view, onChatClick, onDeleteClick)
        }

        override fun onBindViewHolder(holder: ChatViewHolder, position: Int) {
            holder.bind(chats[position])
        }

        override fun getItemCount() = chats.size

        class ChatViewHolder(
            itemView: View,
            private val onChatClick: (Chat) -> Unit,
            private val onDeleteClick: (Chat) -> Unit
        ) : RecyclerView.ViewHolder(itemView) {

            private val chatNameText: TextView = itemView.findViewById(R.id.chatNameText)
            private val chatInfoText: TextView = itemView.findViewById(R.id.chatInfoText)
            private val deleteButton: View = itemView.findViewById(R.id.deleteButton)

            private val dateFormat = SimpleDateFormat("MMM d, yyyy h:mm a", Locale.getDefault())

            fun bind(chat: Chat) {
                chatNameText.text = chat.getDisplayName()

                val queryCountText = itemView.context.getString(R.string.queries_count, chat.queryCount)
                val dateText = dateFormat.format(Date(chat.lastUpdatedAt))
                chatInfoText.text = "$queryCountText • $dateText"

                itemView.setOnClickListener { onChatClick(chat) }
                deleteButton.setOnClickListener { onDeleteClick(chat) }
            }
        }
    }
}
