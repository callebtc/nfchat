package com.example.nfcdemo

import android.app.Activity
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.util.Log
import android.widget.CheckBox
import android.widget.EditText
import android.widget.ImageView
import com.example.nfcdemo.data.MessageDbHelper
import com.example.nfcdemo.data.SettingsContract

class SettingsActivity : Activity() {
    
    private val TAG = "SettingsActivity"
    private lateinit var dbHelper: MessageDbHelper
    private lateinit var cbAutoOpenLinks: CheckBox
    private lateinit var cbUseInternalBrowser: CheckBox
    private lateinit var cbAutoSendShared: CheckBox
    private lateinit var cbCloseAfterSharedSend: CheckBox
    private lateinit var etMaxChunkSize: EditText
    private lateinit var etChunkDelay: EditText
    private lateinit var etTransferTimeout: EditText
    private lateinit var btnBack: ImageView
    
    // Original values to check if they've changed
    private var originalMaxChunkSize = "500"
    private var originalChunkDelay = "200"
    private var originalTransferTimeout = "2"
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)
        
        // Initialize database helper
        dbHelper = MessageDbHelper(this)
        
        // Initialize views
        cbAutoOpenLinks = findViewById(R.id.cbAutoOpenLinks)
        cbUseInternalBrowser = findViewById(R.id.cbUseInternalBrowser)
        cbAutoSendShared = findViewById(R.id.cbAutoSendShared)
        cbCloseAfterSharedSend = findViewById(R.id.cbCloseAfterSharedSend)
        etMaxChunkSize = findViewById(R.id.etMaxChunkSize)
        etChunkDelay = findViewById(R.id.etChunkDelay)
        etTransferTimeout = findViewById(R.id.etTransferTimeout)
        btnBack = findViewById(R.id.btnBack)
        
        // Load current settings
        loadSettings()
        
        // Set up listeners
        setupListeners()
    }
    
    private fun loadSettings() {
        // Load auto open links setting
        val autoOpenLinks = dbHelper.getBooleanSetting(
            SettingsContract.SettingsEntry.KEY_AUTO_OPEN_LINKS, 
            true
        )
        cbAutoOpenLinks.isChecked = autoOpenLinks
        
        // Load use internal browser setting
        val useInternalBrowser = dbHelper.getBooleanSetting(
            SettingsContract.SettingsEntry.KEY_USE_INTERNAL_BROWSER, 
            false
        )
        cbUseInternalBrowser.isChecked = useInternalBrowser
        
        // Load auto send shared setting
        val autoSendShared = dbHelper.getBooleanSetting(
            SettingsContract.SettingsEntry.KEY_AUTO_SEND_SHARED, 
            true
        )
        cbAutoSendShared.isChecked = autoSendShared
        
        // Load close after shared send setting
        val closeAfterSharedSend = dbHelper.getBooleanSetting(
            SettingsContract.SettingsEntry.KEY_CLOSE_AFTER_SHARED_SEND, 
            false
        )
        cbCloseAfterSharedSend.isChecked = closeAfterSharedSend
        
        // Load max chunk size setting
        originalMaxChunkSize = dbHelper.getSetting(
            SettingsContract.SettingsEntry.KEY_MAX_CHUNK_SIZE,
            "500"
        )
        etMaxChunkSize.setText(originalMaxChunkSize)
        
        // Load chunk delay setting
        originalChunkDelay = dbHelper.getSetting(
            SettingsContract.SettingsEntry.KEY_CHUNK_DELAY,
            "200"
        )
        etChunkDelay.setText(originalChunkDelay)
        
        // Load transfer timeout setting
        originalTransferTimeout = dbHelper.getSetting(
            SettingsContract.SettingsEntry.KEY_TRANSFER_TIMEOUT,
            "2"
        )
        etTransferTimeout.setText(originalTransferTimeout)
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
    }
    
    /**
     * Validate and save all numeric settings
     */
    private fun validateAndSaveNumericSettings() {
        // Validate and save max chunk size
        val maxChunkSizeStr = etMaxChunkSize.text.toString()
        if (maxChunkSizeStr.isNotEmpty() && maxChunkSizeStr != originalMaxChunkSize) {
            val maxChunkSize = maxChunkSizeStr.toIntOrNull() ?: 500
            val validMaxChunkSize = maxOf(maxChunkSize, 100) // Minimum 100 characters
            Log.d(TAG, "Saving max chunk size: $validMaxChunkSize")
            dbHelper.setSetting(
                SettingsContract.SettingsEntry.KEY_MAX_CHUNK_SIZE,
                validMaxChunkSize.toString()
            )
        }
        
        // Validate and save chunk delay
        val chunkDelayStr = etChunkDelay.text.toString()
        if (chunkDelayStr.isNotEmpty() && chunkDelayStr != originalChunkDelay) {
            val chunkDelay = chunkDelayStr.toIntOrNull() ?: 200
            val validChunkDelay = maxOf(chunkDelay, 50) // Minimum 50ms
            Log.d(TAG, "Saving chunk delay: $validChunkDelay")
            dbHelper.setSetting(
                SettingsContract.SettingsEntry.KEY_CHUNK_DELAY,
                validChunkDelay.toString()
            )
        }
        
        // Validate and save transfer timeout
        val transferTimeoutStr = etTransferTimeout.text.toString()
        if (transferTimeoutStr.isNotEmpty() && transferTimeoutStr != originalTransferTimeout) {
            val transferTimeout = transferTimeoutStr.toIntOrNull() ?: 2
            val validTransferTimeout = maxOf(transferTimeout, 1) // Minimum 1 second
            Log.d(TAG, "Saving transfer timeout: $validTransferTimeout")
            dbHelper.setSetting(
                SettingsContract.SettingsEntry.KEY_TRANSFER_TIMEOUT,
                validTransferTimeout.toString()
            )
        }
    }
    
    override fun onDestroy() {
        super.onDestroy()
        dbHelper.close()
    }
} 