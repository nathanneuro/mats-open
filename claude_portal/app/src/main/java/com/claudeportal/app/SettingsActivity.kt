package com.claudeportal.app

import android.os.Bundle
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.claudeportal.app.data.HistoryRepository
import com.claudeportal.app.data.SettingsRepository
import com.claudeportal.app.databinding.ActivitySettingsBinding
import com.claudeportal.app.models.AppSettings
import com.claudeportal.app.models.ArrowPosition
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class SettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySettingsBinding
    private val settingsRepo by lazy { SettingsRepository(this) }
    private val historyRepo by lazy { HistoryRepository(this) }

    private var currentFontSize = 14
    private var currentThinkingFontSize = 13
    private var currentTmuxFontSize = 12
    private var currentGraphShrinkPercent = 38
    private var currentEmulatedTerminalWidth = 100

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        loadCurrentSettings()
        setupListeners()
    }

    private fun loadCurrentSettings() {
        lifecycleScope.launch {
            val settings = settingsRepo.settingsFlow.first()
            applyToUi(settings)
        }
    }

    private fun applyToUi(settings: AppSettings) {
        binding.radioArrowRight.isChecked = settings.arrowPosition == ArrowPosition.RIGHT
        binding.radioArrowLeft.isChecked = settings.arrowPosition == ArrowPosition.LEFT
        binding.seekbarOpacity.progress = (settings.arrowOpacity * 100).toInt()
        binding.textOpacityValue.text = "${(settings.arrowOpacity * 100).toInt()}%"

        currentFontSize = settings.fontSize
        currentThinkingFontSize = settings.thinkingFontSize
        currentTmuxFontSize = settings.tmuxFontSize
        currentGraphShrinkPercent = settings.graphShrinkPercent
        currentEmulatedTerminalWidth = settings.emulatedTerminalWidth

        updateFontSizeDisplay(binding.textFontSizeValue, currentFontSize)
        updateFontSizeDisplay(binding.textThinkingFontSizeValue, currentThinkingFontSize)
        updateFontSizeDisplay(binding.textTmuxFontSizeValue, currentTmuxFontSize)
        binding.textGraphShrinkValue.text = "${currentGraphShrinkPercent}%"
        binding.textEmulatedWidthValue.text = "${currentEmulatedTerminalWidth} cols"

        binding.switchKeepScreenOn.isChecked = settings.keepScreenOn
        binding.switchSaveHistory.isChecked = settings.saveHistoryBetweenSessions
    }

    private fun updateFontSizeDisplay(view: TextView, size: Int) {
        view.text = "${size}sp"
    }

    private fun setupListeners() {
        binding.seekbarOpacity.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                binding.textOpacityValue.text = "${progress}%"
            }
            override fun onStartTrackingTouch(seekBar: SeekBar) {}
            override fun onStopTrackingTouch(seekBar: SeekBar) {}
        })

        binding.btnFontMinus.setOnClickListener {
            if (currentFontSize > 4) {
                currentFontSize--
                updateFontSizeDisplay(binding.textFontSizeValue, currentFontSize)
            }
        }
        binding.btnFontPlus.setOnClickListener {
            if (currentFontSize < 16) {
                currentFontSize++
                updateFontSizeDisplay(binding.textFontSizeValue, currentFontSize)
            }
        }

        binding.btnThinkingFontMinus.setOnClickListener {
            if (currentThinkingFontSize > 4) {
                currentThinkingFontSize--
                updateFontSizeDisplay(binding.textThinkingFontSizeValue, currentThinkingFontSize)
            }
        }
        binding.btnThinkingFontPlus.setOnClickListener {
            if (currentThinkingFontSize < 16) {
                currentThinkingFontSize++
                updateFontSizeDisplay(binding.textThinkingFontSizeValue, currentThinkingFontSize)
            }
        }

        binding.btnTmuxFontMinus.setOnClickListener {
            if (currentTmuxFontSize > 4) {
                currentTmuxFontSize--
                updateFontSizeDisplay(binding.textTmuxFontSizeValue, currentTmuxFontSize)
            }
        }
        binding.btnTmuxFontPlus.setOnClickListener {
            if (currentTmuxFontSize < 16) {
                currentTmuxFontSize++
                updateFontSizeDisplay(binding.textTmuxFontSizeValue, currentTmuxFontSize)
            }
        }

        // Graph shrink %: 10–100 in 5-point steps. 100% disables compression.
        binding.btnGraphShrinkMinus.setOnClickListener {
            if (currentGraphShrinkPercent > 10) {
                currentGraphShrinkPercent =
                    (currentGraphShrinkPercent - 5).coerceAtLeast(10)
                binding.textGraphShrinkValue.text = "${currentGraphShrinkPercent}%"
            }
        }
        binding.btnGraphShrinkPlus.setOnClickListener {
            if (currentGraphShrinkPercent < 100) {
                currentGraphShrinkPercent =
                    (currentGraphShrinkPercent + 5).coerceAtMost(100)
                binding.textGraphShrinkValue.text = "${currentGraphShrinkPercent}%"
            }
        }

        // Emulated terminal width: 40–300 in 10-col steps.
        binding.btnEmulatedWidthMinus.setOnClickListener {
            if (currentEmulatedTerminalWidth > 40) {
                currentEmulatedTerminalWidth =
                    (currentEmulatedTerminalWidth - 10).coerceAtLeast(40)
                binding.textEmulatedWidthValue.text = "${currentEmulatedTerminalWidth} cols"
            }
        }
        binding.btnEmulatedWidthPlus.setOnClickListener {
            if (currentEmulatedTerminalWidth < 300) {
                currentEmulatedTerminalWidth =
                    (currentEmulatedTerminalWidth + 10).coerceAtMost(300)
                binding.textEmulatedWidthValue.text = "${currentEmulatedTerminalWidth} cols"
            }
        }

        binding.buttonClearHistory.setOnClickListener {
            lifecycleScope.launch {
                historyRepo.clearAllHistory()
                Toast.makeText(this@SettingsActivity, R.string.history_cleared, Toast.LENGTH_SHORT).show()
            }
        }
    }

    /** Persist the current UI state. Called from any exit path (toolbar
     *  back arrow, system back gesture, finish-on-pause). Save runs off
     *  the main thread; exit isn't blocked on it. The success path is
     *  silent — saving is the assumed default. A toast surfaces only
     *  when the DataStore write throws, which is rare enough that
     *  silence would otherwise mask data loss. */
    private fun saveSettings() {
        val settings = AppSettings(
            arrowPosition = if (binding.radioArrowRight.isChecked) ArrowPosition.RIGHT else ArrowPosition.LEFT,
            arrowOpacity = binding.seekbarOpacity.progress / 100f,
            fontSize = currentFontSize,
            thinkingFontSize = currentThinkingFontSize,
            tmuxFontSize = currentTmuxFontSize,
            keepScreenOn = binding.switchKeepScreenOn.isChecked,
            saveHistoryBetweenSessions = binding.switchSaveHistory.isChecked,
            graphShrinkPercent = currentGraphShrinkPercent,
            emulatedTerminalWidth = currentEmulatedTerminalWidth
        )
        val ctx = applicationContext
        lifecycleScope.launch {
            try {
                settingsRepo.updateSettings(settings)
            } catch (e: Exception) {
                Toast.makeText(
                    ctx,
                    "Settings save failed: ${e.message}",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    override fun onSupportNavigateUp(): Boolean {
        saveSettings()
        finish()
        return true
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        saveSettings()
        @Suppress("DEPRECATION")
        super.onBackPressed()
    }
}
