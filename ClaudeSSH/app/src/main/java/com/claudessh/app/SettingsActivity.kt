package com.claudessh.app

import android.os.Bundle
import android.widget.SeekBar
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.claudessh.app.data.HistoryRepository
import com.claudessh.app.data.SettingsRepository
import com.claudessh.app.databinding.ActivitySettingsBinding
import com.claudessh.app.models.AppSettings
import com.claudessh.app.models.ArrowPosition
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class SettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySettingsBinding
    private val settingsRepo by lazy { SettingsRepository(this) }
    private val historyRepo by lazy { HistoryRepository(this) }

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
        binding.seekbarFontSize.progress = settings.fontSize - 8 // min 8sp
        binding.textFontSizeValue.text = "${settings.fontSize}sp"
        binding.switchKeepScreenOn.isChecked = settings.keepScreenOn
        binding.switchSaveHistory.isChecked = settings.saveHistoryBetweenSessions
        binding.switchShowExtraKeys.isChecked = settings.showExtraKeys
        binding.switchVibrate.isChecked = settings.vibrateOnKeyPress
    }

    private fun setupListeners() {
        binding.seekbarOpacity.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                binding.textOpacityValue.text = "${progress}%"
            }
            override fun onStartTrackingTouch(seekBar: SeekBar) {}
            override fun onStopTrackingTouch(seekBar: SeekBar) {}
        })

        binding.seekbarFontSize.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                binding.textFontSizeValue.text = "${progress + 8}sp"
            }
            override fun onStartTrackingTouch(seekBar: SeekBar) {}
            override fun onStopTrackingTouch(seekBar: SeekBar) {}
        })

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
            fontSize = binding.seekbarFontSize.progress + 8,
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
