package com.example.nfcdemo

import android.app.Activity
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.util.Log
import android.widget.CheckBox
import android.widget.EditText
import android.widget.ImageView
import com.example.nfcdemo.data.AppConstants
import com.example.nfcdemo.data.MessageDbHelper
import com.example.nfcdemo.data.SettingsContract

class SettingsActivity : Activity() {
    
    private val TAG = "SettingsActivity"
    private lateinit var dbHelper: MessageDbHelper
    private lateinit var cbAutoOpenLinks: CheckBox
    private lateinit var cbUseInternalBrowser: CheckBox
    private lateinit var cbAutoSendShared: CheckBox
    private lateinit var cbCloseAfterSharedSend: CheckBox
    private lateinit var cbEnableBackgroundNfc: CheckBox
    private lateinit var cbBringToForeground: CheckBox
    private lateinit var etMaxChunkSize: EditText
    private lateinit var etChunkDelay: EditText
    private lateinit var etTransferRetryTimeoutMs: EditText
    private lateinit var btnBack: ImageView
    
    // Original values to check if they've changed
    private var originalMaxChunkSize = AppConstants.DefaultSettingsStrings.MAX_CHUNK_SIZE
    private var originalChunkDelay = AppConstants.DefaultSettingsStrings.CHUNK_DELAY_MS
    private var originalTransferRetryTimeoutMs = AppConstants.DefaultSettingsStrings.TRANSFER_RETRY_TIMEOUT_MS
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)
        
        // Initialize database helper
        dbHelper = MessageDbHelper(this)
        
        // Initialize views
        btnBack = findViewById(R.id.btnBack)
        cbAutoOpenLinks = findViewById(R.id.cbAutoOpenLinks)
        cbUseInternalBrowser = findViewById(R.id.cbUseInternalBrowser)
        cbAutoSendShared = findViewById(R.id.cbAutoSendShared)
        cbCloseAfterSharedSend = findViewById(R.id.cbCloseAfterSharedSend)
        cbEnableBackgroundNfc = findViewById(R.id.cbEnableBackgroundNfc)
        cbBringToForeground = findViewById(R.id.cbBringToForeground)
        etMaxChunkSize = findViewById(R.id.etMaxChunkSize)
        etChunkDelay = findViewById(R.id.etChunkDelay)
        etTransferRetryTimeoutMs = findViewById(R.id.etTransferRetryTimeoutMs)
        
        // Load settings
        loadSettings()
        
        // Set up listeners
        setupListeners()
    }
    
    private fun loadSettings() {
        // Load auto open links setting
        val autoOpenLinks = dbHelper.getBooleanSetting(
            SettingsContract.SettingsEntry.KEY_AUTO_OPEN_LINKS, 
            AppConstants.DefaultSettings.AUTO_OPEN_LINKS
        )
        cbAutoOpenLinks.isChecked = autoOpenLinks
        
        // Load use internal browser setting
        val useInternalBrowser = dbHelper.getBooleanSetting(
            SettingsContract.SettingsEntry.KEY_USE_INTERNAL_BROWSER, 
            AppConstants.DefaultSettings.USE_INTERNAL_BROWSER
        )
        cbUseInternalBrowser.isChecked = useInternalBrowser
        
        // Load auto send shared setting
        val autoSendShared = dbHelper.getBooleanSetting(
            SettingsContract.SettingsEntry.KEY_AUTO_SEND_SHARED, 
            AppConstants.DefaultSettings.AUTO_SEND_SHARED
        )
        cbAutoSendShared.isChecked = autoSendShared
        
        // Load close after shared send setting
        val closeAfterSharedSend = dbHelper.getBooleanSetting(
            SettingsContract.SettingsEntry.KEY_CLOSE_AFTER_SHARED_SEND, 
            AppConstants.DefaultSettings.CLOSE_AFTER_SHARED_SEND
        )
        cbCloseAfterSharedSend.isChecked = closeAfterSharedSend
        
        // Load enable background NFC setting
        val enableBackgroundNfc = dbHelper.getBooleanSetting(
            SettingsContract.SettingsEntry.KEY_ENABLE_BACKGROUND_NFC, 
            AppConstants.DefaultSettings.ENABLE_BACKGROUND_NFC
        )
        cbEnableBackgroundNfc.isChecked = enableBackgroundNfc
        
        // Load bring to foreground setting
        val bringToForeground = dbHelper.getBooleanSetting(
            SettingsContract.SettingsEntry.KEY_BRING_TO_FOREGROUND, 
            AppConstants.DefaultSettings.BRING_TO_FOREGROUND
        )
        cbBringToForeground.isChecked = bringToForeground
        
        // Load max chunk size setting
        originalMaxChunkSize = dbHelper.getSetting(
            SettingsContract.SettingsEntry.KEY_MAX_CHUNK_SIZE,
            AppConstants.DefaultSettingsStrings.MAX_CHUNK_SIZE
        )
        etMaxChunkSize.setText(originalMaxChunkSize)
        
        // Load chunk delay setting
        originalChunkDelay = dbHelper.getSetting(
            SettingsContract.SettingsEntry.KEY_CHUNK_DELAY,
            AppConstants.DefaultSettingsStrings.CHUNK_DELAY_MS
        )
        etChunkDelay.setText(originalChunkDelay)
        
        // Load transfer retry timeout setting
        originalTransferRetryTimeoutMs = dbHelper.getSetting(
            SettingsContract.SettingsEntry.KEY_TRANSFER_RETRY_TIMEOUT_MS,
            AppConstants.DefaultSettingsStrings.TRANSFER_RETRY_TIMEOUT_MS
        )
        etTransferRetryTimeoutMs.setText(originalTransferRetryTimeoutMs)
    }
    
    private fun setupListeners() {
        // Back button
        btnBack.setOnClickListener {
            // Validate and save numeric settings before closing
            validateAndSaveNumericSettings()
            finish()
        }
        
        // Auto open links checkbox
        cbAutoOpenLinks.setOnCheckedChangeListener { _, isChecked ->
            dbHelper.setBooleanSetting(
                SettingsContract.SettingsEntry.KEY_AUTO_OPEN_LINKS,
                isChecked
            )
        }
        
        // Use internal browser checkbox
        cbUseInternalBrowser.setOnCheckedChangeListener { _, isChecked ->
            dbHelper.setBooleanSetting(
                SettingsContract.SettingsEntry.KEY_USE_INTERNAL_BROWSER,
                isChecked
            )
        }
        
        // Auto send shared checkbox
        cbAutoSendShared.setOnCheckedChangeListener { _, isChecked ->
            dbHelper.setBooleanSetting(
                SettingsContract.SettingsEntry.KEY_AUTO_SEND_SHARED,
                isChecked
            )
        }
        
        // Close after shared send checkbox
        cbCloseAfterSharedSend.setOnCheckedChangeListener { _, isChecked ->
            dbHelper.setBooleanSetting(
                SettingsContract.SettingsEntry.KEY_CLOSE_AFTER_SHARED_SEND,
                isChecked
            )
        }
        
        // Enable background NFC checkbox
        cbEnableBackgroundNfc.setOnCheckedChangeListener { _, isChecked ->
            dbHelper.setBooleanSetting(
                SettingsContract.SettingsEntry.KEY_ENABLE_BACKGROUND_NFC,
                isChecked
            )
        }
        
        // Bring to foreground checkbox
        cbBringToForeground.setOnCheckedChangeListener { _, isChecked ->
            dbHelper.setBooleanSetting(
                SettingsContract.SettingsEntry.KEY_BRING_TO_FOREGROUND,
                isChecked
            )
        }
    }
    
    /**
     * Validate and save all numeric settings
     */
    private fun validateAndSaveNumericSettings() {
        // Validate and save max chunk size
        val maxChunkSizeStr = etMaxChunkSize.text.toString()
        if (maxChunkSizeStr.isNotEmpty() && maxChunkSizeStr != originalMaxChunkSize) {
            val maxChunkSize = maxChunkSizeStr.toIntOrNull() ?: AppConstants.DefaultSettings.MAX_CHUNK_SIZE
            val validMaxChunkSize = maxOf(maxChunkSize, AppConstants.MinimumValues.MIN_CHUNK_SIZE)
            Log.d(TAG, "Saving max chunk size: $validMaxChunkSize")
            dbHelper.setSetting(
                SettingsContract.SettingsEntry.KEY_MAX_CHUNK_SIZE,
                validMaxChunkSize.toString()
            )
        }
        
        // Validate and save chunk delay
        val chunkDelayStr = etChunkDelay.text.toString()
        if (chunkDelayStr.isNotEmpty() && chunkDelayStr != originalChunkDelay) {
            val chunkDelay = chunkDelayStr.toIntOrNull() ?: AppConstants.DefaultSettings.CHUNK_DELAY_MS.toInt()
            val validChunkDelay = maxOf(chunkDelay, AppConstants.MinimumValues.MIN_CHUNK_DELAY_MS)
            Log.d(TAG, "Saving chunk delay: $validChunkDelay")
            dbHelper.setSetting(
                SettingsContract.SettingsEntry.KEY_CHUNK_DELAY,
                validChunkDelay.toString()
            )
        }
        
        // Validate and save transfer retry timeout
        val transferRetryTimeoutStr = etTransferRetryTimeoutMs.text.toString()
        if (transferRetryTimeoutStr.isNotEmpty() && transferRetryTimeoutStr != originalTransferRetryTimeoutMs) {
            val transferRetryTimeout = transferRetryTimeoutStr.toIntOrNull() ?: AppConstants.DefaultSettings.TRANSFER_RETRY_TIMEOUT_MS.toInt()
            val validTransferRetryTimeout = maxOf(transferRetryTimeout, AppConstants.MinimumValues.MIN_TRANSFER_RETRY_TIMEOUT_MS)
            Log.d(TAG, "Saving transfer retry timeout: $validTransferRetryTimeout")
            dbHelper.setSetting(
                SettingsContract.SettingsEntry.KEY_TRANSFER_RETRY_TIMEOUT_MS,
                validTransferRetryTimeout.toString()
            )
        }
    }
    
    override fun onDestroy() {
        super.onDestroy()
        dbHelper.close()
    }
} 