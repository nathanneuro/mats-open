package com.crosscheck.app

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.crosscheck.app.data.SettingsRepository
import com.crosscheck.app.models.ApiProvider
import com.crosscheck.app.models.AppSettings
import com.crosscheck.app.models.ProviderType
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class SettingsActivity : AppCompatActivity() {

    private lateinit var settingsRepository: SettingsRepository
    private lateinit var utilityModelContainer: LinearLayout
    private lateinit var providersContainer: LinearLayout
    private lateinit var stage1Spinner: Spinner
    private lateinit var stage2Spinner: Spinner
    private lateinit var stage3Spinner: Spinner
    private val providerViews = mutableListOf<ProviderView>()
    private var utilityModelView: ProviderView? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        val toolbar = findViewById<androidx.appcompat.widget.Toolbar>(R.id.toolbar)
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = getString(R.string.settings)

        settingsRepository = SettingsRepository(this)
        utilityModelContainer = findViewById(R.id.utilityModelContainer)
        providersContainer = findViewById(R.id.providersContainer)
        stage1Spinner = findViewById(R.id.stage1Spinner)
        stage2Spinner = findViewById(R.id.stage2Spinner)
        stage3Spinner = findViewById(R.id.stage3Spinner)

        findViewById<Button>(R.id.addProviderButton).setOnClickListener {
            addProviderView()
        }

        findViewById<Button>(R.id.saveButton).setOnClickListener {
            saveSettings()
        }

        loadSettings()
    }

    private fun loadSettings() {
        lifecycleScope.launch {
            val settings = settingsRepository.settingsFlow.first()

            // Load utility model if exists, otherwise create empty one
            val utilityView = LayoutInflater.from(this@SettingsActivity)
                .inflate(R.layout.item_provider, utilityModelContainer, false)
            utilityModelView = ProviderView(utilityView, settings.utilityModel, hideRemoveButton = true)
            utilityModelContainer.addView(utilityView)

            if (settings.providers.isEmpty()) {
                addProviderView()
            } else {
                settings.providers.forEach { provider ->
                    addProviderView(provider)
                }
            }

            updateStageSpinners()

            if (settings.queryOrder.size == 3) {
                val maxIndex = providerViews.size - 1
                stage1Spinner.setSelection(settings.queryOrder[0].coerceIn(0, maxIndex))
                stage2Spinner.setSelection(settings.queryOrder[1].coerceIn(0, maxIndex))
                stage3Spinner.setSelection(settings.queryOrder[2].coerceIn(0, maxIndex))
            }
        }
    }

    private fun addProviderView(provider: ApiProvider? = null) {
        val view = LayoutInflater.from(this).inflate(R.layout.item_provider, providersContainer, false)
        val providerView = ProviderView(view, provider)
        providerViews.add(providerView)
        providersContainer.addView(view)

        view.findViewById<Button>(R.id.removeButton).setOnClickListener {
            removeProviderView(providerView)
        }

        updateStageSpinners()
    }

    private fun removeProviderView(providerView: ProviderView) {
        providerViews.remove(providerView)
        providersContainer.removeView(providerView.view)
        updateStageSpinners()
    }

    private fun updateStageSpinners() {
        val providerNames = providerViews.mapIndexed { index, pv ->
            val typeName = when (pv.getProviderType()) {
                ProviderType.OPENROUTER -> "OpenRouter"
                ProviderType.ANTHROPIC -> "Anthropic"
                ProviderType.GEMINI -> "Google Gemini"
            }
            "Provider ${index + 1}: $typeName"
        }

        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, providerNames)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)

        stage1Spinner.adapter = adapter
        stage2Spinner.adapter = adapter
        stage3Spinner.adapter = adapter
    }

    private fun saveSettings() {
        if (providerViews.isEmpty()) {
            Toast.makeText(this, "Please add at least one provider", Toast.LENGTH_SHORT).show()
            return
        }

        val providers = providerViews.mapNotNull { it.getProvider() }

        if (providers.size != providerViews.size) {
            Toast.makeText(this, "Please fill in all provider fields", Toast.LENGTH_SHORT).show()
            return
        }

        if (providers.size < 3) {
            Toast.makeText(this, "Please add at least 3 providers for the 3 stages", Toast.LENGTH_SHORT).show()
            return
        }

        val queryOrder = listOf(
            stage1Spinner.selectedItemPosition,
            stage2Spinner.selectedItemPosition,
            stage3Spinner.selectedItemPosition
        )

        // Get utility model (optional)
        val utilityModel = utilityModelView?.getProvider()

        val settings = AppSettings(
            providers = providers,
            queryOrder = queryOrder,
            utilityModel = utilityModel
        )

        lifecycleScope.launch {
            settingsRepository.saveSettings(settings)
            Toast.makeText(this@SettingsActivity, "Settings saved", Toast.LENGTH_SHORT).show()
            finish()
        }
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    private inner class ProviderView(
        val view: View,
        provider: ApiProvider? = null,
        hideRemoveButton: Boolean = false
    ) {
        private val typeSpinner: Spinner = view.findViewById(R.id.providerTypeSpinner)
        private val apiKeyInput: EditText = view.findViewById(R.id.apiKeyInput)
        private val modelNameInput: EditText = view.findViewById(R.id.modelNameInput)
        private val removeButton: Button = view.findViewById(R.id.removeButton)

        init {
            val types = listOf("OpenRouter", "Anthropic", "Google Gemini")
            val adapter = ArrayAdapter(this@SettingsActivity, android.R.layout.simple_spinner_item, types)
            adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
            typeSpinner.adapter = adapter

            if (hideRemoveButton) {
                removeButton.visibility = View.GONE
            }

            provider?.let {
                typeSpinner.setSelection(when (it.type) {
                    ProviderType.OPENROUTER -> 0
                    ProviderType.ANTHROPIC -> 1
                    ProviderType.GEMINI -> 2
                })
                apiKeyInput.setText(it.apiKey)
                modelNameInput.setText(it.modelName)
            }
        }

        fun getProviderType(): ProviderType {
            return when (typeSpinner.selectedItemPosition) {
                0 -> ProviderType.OPENROUTER
                1 -> ProviderType.ANTHROPIC
                2 -> ProviderType.GEMINI
                else -> ProviderType.ANTHROPIC
            }
        }

        fun getProvider(): ApiProvider? {
            val apiKey = apiKeyInput.text.toString().trim()
            val modelName = modelNameInput.text.toString().trim()

            if (apiKey.isEmpty() || modelName.isEmpty()) {
                return null
            }

            return ApiProvider(
                type = getProviderType(),
                apiKey = apiKey,
                modelName = modelName
            )
        }
    }
}
