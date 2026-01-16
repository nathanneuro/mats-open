package com.crosscheck.app

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.crosscheck.app.api.ApiClient
import com.crosscheck.app.data.QueryRepository
import com.crosscheck.app.data.SettingsRepository
import com.crosscheck.app.models.AppSettings
import com.crosscheck.app.query.QueryManager
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var settingsRepository: SettingsRepository
    private lateinit var queryRepository: QueryRepository
    private lateinit var queryManager: QueryManager

    private lateinit var questionInput: EditText
    private lateinit var submitButton: Button
    private lateinit var settingsButton: Button
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
        queryRepository = QueryRepository(this)
        queryManager = QueryManager(ApiClient(), queryRepository)

        initViews()
        setupListeners()
        observeQueryState()
        checkForCrashRecovery()
    }

    private fun initViews() {
        questionInput = findViewById(R.id.questionInput)
        submitButton = findViewById(R.id.submitButton)
        settingsButton = findViewById(R.id.settingsButton)
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

        settingsButton.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }

        retryButton.setOnClickListener {
            retryQuery()
        }

        toggleRawButton.setOnClickListener {
            toggleRawResponses()
        }
    }

    /**
     * Check if there's an incomplete query from a crash/interruption
     */
    private fun checkForCrashRecovery() {
        lifecycleScope.launch {
            val currentQuery = queryRepository.loadCurrentQuery()
            if (currentQuery != null && currentQuery.canRetry()) {
                Toast.makeText(
                    this@MainActivity,
                    "Recovered incomplete query from previous session",
                    Toast.LENGTH_LONG
                ).show()
                // Populate the UI with the failed query
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
        val currentHistory = queryManager.getCurrentQueryHistory()
        if (currentHistory != null && currentHistory.canRetry()) {
            lifecycleScope.launch {
                hideKeyboard()
                Toast.makeText(
                    this@MainActivity,
                    getString(R.string.resuming_from_stage, currentHistory.getNextStage()),
                    Toast.LENGTH_SHORT
                ).show()
                queryManager.retryQuery(currentHistory)
            }
        } else {
            Toast.makeText(this, "Nothing to retry", Toast.LENGTH_SHORT).show()
        }
    }

    private fun submitQuery() {
        val question = questionInput.text.toString().trim()

        if (question.isEmpty()) {
            Toast.makeText(this, "Please enter a question", Toast.LENGTH_SHORT).show()
            return
        }

        lifecycleScope.launch {
            val settings = settingsRepository.settingsFlow.first()

            if (!settings.isValid()) {
                Toast.makeText(
                    this@MainActivity,
                    getString(R.string.error_no_providers),
                    Toast.LENGTH_LONG
                ).show()
                return@launch
            }

            hideKeyboard()
            queryManager.executeQuery(question, settings)
        }
    }

    private fun observeQueryState() {
        lifecycleScope.launch {
            queryManager.queryState.collect { state ->
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
                retryButton.visibility = View.VISIBLE // Show retry button on error!
                submitButton.isEnabled = true

                // Show any partial responses we have
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

                // Show any partial responses we have while loading next stage
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

    override fun onResume() {
        super.onResume()
        queryManager.reset()
    }
}
