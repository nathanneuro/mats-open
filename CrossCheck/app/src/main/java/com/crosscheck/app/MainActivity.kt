package com.crosscheck.app

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.crosscheck.app.api.ApiClient
import com.crosscheck.app.data.ChatRepository
import com.crosscheck.app.data.SettingsRepository
import com.crosscheck.app.models.QueryMode
import com.crosscheck.app.service.ChatNamingService
import com.crosscheck.app.service.QueryService
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import android.widget.Spinner
import android.widget.ArrayAdapter
import android.widget.AdapterView
import androidx.appcompat.widget.Toolbar

class MainActivity : AppCompatActivity() {

    private lateinit var settingsRepository: SettingsRepository
    private lateinit var chatRepository: ChatRepository
    private lateinit var chatNamingService: ChatNamingService

    private lateinit var toolbar: Toolbar
    private lateinit var queryModeSpinner: Spinner
    private lateinit var questionInput: EditText
    private lateinit var submitButton: Button
    private lateinit var newChatButton: ImageButton
    private lateinit var historyButton: ImageButton
    private lateinit var retryButton: Button
    private lateinit var progressBar: ProgressBar
    private lateinit var statusText: TextView
    private lateinit var errorText: TextView
    private lateinit var responseContainer: LinearLayout
    private lateinit var finalAnswerText: TextView
    private lateinit var firstResponseText: TextView
    private lateinit var secondResponseText: TextView
    private lateinit var toggleRawButton: Button
    private lateinit var rawResponsesContainer: LinearLayout

    private var isRawResponsesVisible = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        settingsRepository = SettingsRepository(this)
        chatRepository = ChatRepository(this)
        val apiClient = ApiClient()
        chatNamingService = ChatNamingService(apiClient, chatRepository)

        initViews()
        setupListeners()
        setupQueryModeSpinner()
        observeQueryState()
        checkForCrashRecovery()
    }

    private fun initViews() {
        toolbar = findViewById(R.id.toolbar)
        setSupportActionBar(toolbar)

        queryModeSpinner = findViewById(R.id.queryModeSpinner)
        questionInput = findViewById(R.id.questionInput)
        submitButton = findViewById(R.id.submitButton)
        newChatButton = findViewById(R.id.newChatButton)
        historyButton = findViewById(R.id.historyButton)
        retryButton = findViewById(R.id.retryButton)
        progressBar = findViewById(R.id.progressBar)
        statusText = findViewById(R.id.statusText)
        errorText = findViewById(R.id.errorText)
        responseContainer = findViewById(R.id.responseContainer)
        finalAnswerText = findViewById(R.id.finalAnswerText)
        firstResponseText = findViewById(R.id.firstResponseText)
        secondResponseText = findViewById(R.id.secondResponseText)
        toggleRawButton = findViewById(R.id.toggleRawButton)
        rawResponsesContainer = findViewById(R.id.rawResponsesContainer)
    }

    private fun setupListeners() {
        submitButton.setOnClickListener {
            submitQuery()
        }

        newChatButton.setOnClickListener {
            createNewChat()
        }

        historyButton.setOnClickListener {
            startActivity(Intent(this, HistoryActivity::class.java))
        }

        retryButton.setOnClickListener {
            retryQuery()
        }

        toggleRawButton.setOnClickListener {
            toggleRawResponses()
        }
    }

    private fun setupQueryModeSpinner() {
        val modes = arrayOf(
            getString(R.string.mode_abc),
            getString(R.string.mode_ac),
            getString(R.string.mode_bc)
        )

        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, modes)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        queryModeSpinner.adapter = adapter

        queryModeSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                // Query mode will be read from spinner when submitting query
            }

            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
    }

    private fun createNewChat() {
        lifecycleScope.launch {
            chatRepository.createChat()
            questionInput.text?.clear()
            responseContainer.visibility = View.GONE
            errorText.visibility = View.GONE
            retryButton.visibility = View.GONE
            Toast.makeText(this@MainActivity, "Started new chat", Toast.LENGTH_SHORT).show()
        }
    }

    private fun checkForCrashRecovery() {
        lifecycleScope.launch {
            val currentQuery = chatRepository.loadCurrentQuery()
            if (currentQuery != null && currentQuery.canRetry()) {
                Toast.makeText(
                    this@MainActivity,
                    "Recovered incomplete query from previous session",
                    Toast.LENGTH_LONG
                ).show()
                questionInput.setText(currentQuery.question)
                updateUIForRecoveredQuery(currentQuery)
            }
        }
    }

    private fun updateUIForRecoveredQuery(query: com.crosscheck.app.models.QueryHistory) {
        if (query.firstResponse != null) {
            responseContainer.visibility = View.VISIBLE
            firstResponseText.text = query.firstResponse
        }
        if (query.secondResponse != null) {
            secondResponseText.text = query.secondResponse
        }
        if (query.lastError != null) {
            errorText.text = query.lastError
            errorText.visibility = View.VISIBLE
            retryButton.visibility = View.VISIBLE
        }
    }

    private fun retryQuery() {
        if (QueryService.isRunning) {
            Toast.makeText(this, "Query already in progress", Toast.LENGTH_SHORT).show()
            return
        }
        hideKeyboard()
        QueryService.startRetry(this)
    }

    private fun submitQuery() {
        val question = questionInput.text.toString().trim()

        if (question.isEmpty()) {
            Toast.makeText(this, "Please enter a question", Toast.LENGTH_SHORT).show()
            return
        }

        if (QueryService.isRunning) {
            Toast.makeText(this, "Query already in progress", Toast.LENGTH_SHORT).show()
            return
        }

        lifecycleScope.launch {
            var settings = settingsRepository.settingsFlow.first()

            if (!settings.isValid()) {
                Toast.makeText(
                    this@MainActivity,
                    getString(R.string.error_no_providers),
                    Toast.LENGTH_LONG
                ).show()
                return@launch
            }

            val queryMode = when (queryModeSpinner.selectedItemPosition) {
                0 -> QueryMode.ABC
                1 -> QueryMode.AC
                2 -> QueryMode.BC
                else -> QueryMode.ABC
            }

            settings = settings.copy(queryMode = queryMode)
            hideKeyboard()

            QueryService.startQuery(this@MainActivity, question, settings)

            // Auto-name chat after first query
            val chat = chatRepository.getCurrentChat()
            if (chat.queryCount == 0 && settings.utilityModel != null) {
                kotlinx.coroutines.delay(2000)
                chatNamingService.autoNameChat(chat.id, question, settings.utilityModel!!)
            }
        }
    }

    private fun observeQueryState() {
        lifecycleScope.launch {
            QueryService.queryState.collect { state ->
                updateUI(state)
            }
        }
    }

    private fun updateUI(state: com.crosscheck.app.models.QueryResponse) {
        responseContainer.visibility = View.GONE
        progressBar.visibility = View.GONE
        statusText.visibility = View.GONE
        errorText.visibility = View.GONE
        retryButton.visibility = View.GONE

        when {
            state.error != null -> {
                errorText.text = getString(R.string.error_occurred, state.error)
                errorText.visibility = View.VISIBLE
                retryButton.visibility = View.VISIBLE
                submitButton.isEnabled = true

                if (state.firstResponse != null || state.secondResponse != null) {
                    responseContainer.visibility = View.VISIBLE
                    firstResponseText.text = state.firstResponse ?: ""
                    secondResponseText.text = state.secondResponse ?: ""
                }
            }

            state.isLoading -> {
                progressBar.visibility = View.VISIBLE
                statusText.visibility = View.VISIBLE
                statusText.text = when (state.currentStage) {
                    1 -> "Stage 1: Getting initial answer..."
                    2 -> "Stage 2: Cross-checking..."
                    3 -> "Stage 3: Synthesizing final answer..."
                    else -> getString(R.string.loading)
                }
                submitButton.isEnabled = false

                if (state.firstResponse != null || state.secondResponse != null) {
                    responseContainer.visibility = View.VISIBLE
                    firstResponseText.text = state.firstResponse ?: ""
                    secondResponseText.text = state.secondResponse ?: ""
                }
            }

            state.thirdResponse != null -> {
                responseContainer.visibility = View.VISIBLE
                finalAnswerText.text = state.thirdResponse
                firstResponseText.text = state.firstResponse ?: ""
                secondResponseText.text = state.secondResponse ?: ""
                submitButton.isEnabled = true
                questionInput.text?.clear()
            }

            else -> {
                submitButton.isEnabled = true
            }
        }
    }

    private fun toggleRawResponses() {
        isRawResponsesVisible = !isRawResponsesVisible
        rawResponsesContainer.visibility = if (isRawResponsesVisible) View.VISIBLE else View.GONE
        toggleRawButton.text = if (isRawResponsesVisible) {
            getString(R.string.hide_raw_responses)
        } else {
            getString(R.string.show_raw_responses)
        }
    }

    private fun hideKeyboard() {
        val imm = getSystemService(INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager
        imm.hideSoftInputFromWindow(currentFocus?.windowToken, 0)
    }

    override fun onCreateOptionsMenu(menu: android.view.Menu?): Boolean {
        menuInflater.inflate(R.menu.menu_main, menu)
        return true
    }

    override fun onOptionsItemSelected(item: android.view.MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_settings -> {
                startActivity(Intent(this, SettingsActivity::class.java))
                true
            }
            R.id.action_about -> {
                startActivity(Intent(this, AboutActivity::class.java))
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }
}
