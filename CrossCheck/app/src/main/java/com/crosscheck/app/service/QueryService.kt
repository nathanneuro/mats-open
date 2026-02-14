package com.crosscheck.app.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.crosscheck.app.MainActivity
import com.crosscheck.app.R
import com.crosscheck.app.api.ApiClient
import com.crosscheck.app.data.ChatRepository
import com.crosscheck.app.data.SettingsRepository
import com.crosscheck.app.models.AppSettings
import com.crosscheck.app.models.QueryHistory
import com.crosscheck.app.models.QueryResponse
import com.crosscheck.app.query.QueryManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class QueryService : Service() {

    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private lateinit var queryManager: QueryManager
    private lateinit var chatRepository: ChatRepository

    companion object {
        private const val CHANNEL_ID = "crosscheck_query"
        private const val NOTIFICATION_ID = 1

        private val _queryState = MutableStateFlow(QueryResponse())
        val queryState: StateFlow<QueryResponse> = _queryState.asStateFlow()

        private var _isRunning = false
        val isRunning: Boolean get() = _isRunning

        fun startQuery(context: Context, question: String, settings: AppSettings) {
            val intent = Intent(context, QueryService::class.java).apply {
                action = ACTION_EXECUTE
                putExtra(EXTRA_QUESTION, question)
                putExtra(EXTRA_SETTINGS_JSON, com.google.gson.Gson().toJson(settings))
            }
            context.startForegroundService(intent)
        }

        fun startRetry(context: Context) {
            val intent = Intent(context, QueryService::class.java).apply {
                action = ACTION_RETRY
            }
            context.startForegroundService(intent)
        }

        private const val ACTION_EXECUTE = "execute"
        private const val ACTION_RETRY = "retry"
        private const val EXTRA_QUESTION = "question"
        private const val EXTRA_SETTINGS_JSON = "settings_json"
    }

    override fun onCreate() {
        super.onCreate()
        val apiClient = ApiClient()
        chatRepository = ChatRepository(this)
        queryManager = QueryManager(apiClient, chatRepository)
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIFICATION_ID, buildNotification("Preparing query..."))
        _isRunning = true

        when (intent?.action) {
            ACTION_EXECUTE -> {
                val question = intent.getStringExtra(EXTRA_QUESTION) ?: return stopAndReturn()
                val settingsJson = intent.getStringExtra(EXTRA_SETTINGS_JSON) ?: return stopAndReturn()
                val settings = com.google.gson.Gson().fromJson(settingsJson, AppSettings::class.java)

                serviceScope.launch {
                    observeAndForwardState()
                    queryManager.executeQuery(question, settings)
                    finishUp()
                }
            }
            ACTION_RETRY -> {
                serviceScope.launch {
                    observeAndForwardState()
                    val history = queryManager.getCurrentQueryHistory()
                    if (history != null && history.canRetry()) {
                        queryManager.retryQuery(history)
                    }
                    finishUp()
                }
            }
            else -> return stopAndReturn()
        }

        return START_NOT_STICKY
    }

    private fun observeAndForwardState() {
        serviceScope.launch {
            queryManager.queryState.collect { state ->
                _queryState.value = state
                val stageText = when {
                    state.error != null -> "Query failed"
                    state.thirdResponse != null -> "Query complete"
                    state.isLoading -> "Stage ${state.currentStage} of 3..."
                    else -> "Querying..."
                }
                val nm = getSystemService(NotificationManager::class.java)
                nm.notify(NOTIFICATION_ID, buildNotification(stageText))
            }
        }
    }

    private fun finishUp() {
        _isRunning = false
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun stopAndReturn(): Int {
        _isRunning = false
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        _isRunning = false
        serviceScope.cancel()
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Query Progress",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Shows progress while CrossCheck queries AI models"
        }
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(channel)
    }

    private fun buildNotification(contentText: String): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("CrossCheck")
            .setContentText(contentText)
            .setSmallIcon(android.R.drawable.ic_menu_search)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
    }
}
