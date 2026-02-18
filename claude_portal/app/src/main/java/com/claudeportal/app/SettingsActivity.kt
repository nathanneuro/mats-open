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
        updateFontSizeDisplay(binding.textFontSizeValue, currentFontSize)
        updateFontSizeDisplay(binding.textThinkingFontSizeValue, currentThinkingFontSize)
        updateFontSizeDisplay(binding.textTmuxFontSizeValue, currentTmuxFontSize)

        binding.switchKeepScreenOn.isChecked = settings.keepScreenOn
        binding.switchSaveHistory.isChecked = settings.saveHistoryBetweenSessions
        binding.switchShowExtraKeys.isChecked = settings.showExtraKeys
        binding.switchVibrate.isChecked = settings.vibrateOnKeyPress
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

        // Terminal font size +/-
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

        // Thinking bar font size +/-
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

        // Tmux bar font size +/-
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

        binding.buttonSave.setOnClickListener { saveSettings() }

        binding.buttonClearHistory.setOnClickListener {
            lifecycleScope.launch {
                historyRepo.clearAllHistory()
                Toast.makeText(this@SettingsActivity, R.string.history_cleared, Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun saveSettings() {
        val settings = AppSettings(
            arrowPosition = if (binding.radioArrowRight.isChecked) ArrowPosition.RIGHT else ArrowPosition.LEFT,
            arrowOpacity = binding.seekbarOpacity.progress / 100f,
            fontSize = currentFontSize,
            thinkingFontSize = currentThinkingFontSize,
            tmuxFontSize = currentTmuxFontSize,
            keepScreenOn = binding.switchKeepScreenOn.isChecked,
            saveHistoryBetweenSessions = binding.switchSaveHistory.isChecked,
            showExtraKeys = binding.switchShowExtraKeys.isChecked,
            vibrateOnKeyPress = binding.switchVibrate.isChecked
        )

        lifecycleScope.launch {
            settingsRepo.updateSettings(settings)
            Toast.makeText(this@SettingsActivity, R.string.settings_saved, Toast.LENGTH_SHORT).show()
            finish()
        }
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }
}
