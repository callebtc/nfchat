package com.example.nfcdemo

import android.app.Activity
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
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
    
    // Cashu handler settings
    private lateinit var cbEnableCashuHandler: CheckBox
    private lateinit var etCashuUrlPattern: EditText
    private lateinit var cbCashuUseApp: CheckBox
    private lateinit var cbCashuUseInternalBrowser: CheckBox
    
    // Original values to check if they've changed
    private var originalMaxChunkSize = AppConstants.DefaultSettingsStrings.MAX_CHUNK_SIZE
    private var originalChunkDelay = AppConstants.DefaultSettingsStrings.CHUNK_DELAY_MS
    private var originalTransferRetryTimeoutMs = AppConstants.DefaultSettingsStrings.TRANSFER_RETRY_TIMEOUT_MS
    private var originalCashuUrlPattern = AppConstants.DefaultSettingsStrings.CASHU_URL_PATTERN
    
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
        
        // Initialize Cashu handler views
        cbEnableCashuHandler = findViewById(R.id.cbEnableCashuHandler)
        etCashuUrlPattern = findViewById(R.id.etCashuUrlPattern)
        cbCashuUseApp = findViewById(R.id.cbCashuUseApp)
        cbCashuUseInternalBrowser = findViewById(R.id.cbCashuUseInternalBrowser)
        
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
        
        // Load Cashu handler settings
        val enableCashuHandler = dbHelper.getBooleanSetting(
            SettingsContract.SettingsEntry.KEY_ENABLE_CASHU_HANDLER, 
            AppConstants.DefaultSettings.ENABLE_CASHU_HANDLER
        )
        cbEnableCashuHandler.isChecked = enableCashuHandler
        
        originalCashuUrlPattern = dbHelper.getSetting(
            SettingsContract.SettingsEntry.KEY_CASHU_URL_PATTERN,
            AppConstants.DefaultSettingsStrings.CASHU_URL_PATTERN
        )
        etCashuUrlPattern.setText(originalCashuUrlPattern)
        
        val cashuUseApp = dbHelper.getBooleanSetting(
            SettingsContract.SettingsEntry.KEY_CASHU_USE_APP, 
            AppConstants.DefaultSettings.CASHU_USE_APP
        )
        cbCashuUseApp.isChecked = cashuUseApp
        
        val cashuUseInternalBrowser = dbHelper.getBooleanSetting(
            SettingsContract.SettingsEntry.KEY_CASHU_USE_INTERNAL_BROWSER, 
            AppConstants.DefaultSettings.CASHU_USE_INTERNAL_BROWSER
        )
        cbCashuUseInternalBrowser.isChecked = cashuUseInternalBrowser
    }
    
    private fun setupListeners() {
        // Back button
        btnBack.setOnClickListener {
            // Validate and save numeric settings before closing
            validateAndSaveNumericSettings()
            validateAndSaveCashuSettings()
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
            
            // Update the NFC intent filter components' enabled state
            updateNfcComponentState(isChecked)
        }
        
        // Bring to foreground checkbox
        cbBringToForeground.setOnCheckedChangeListener { _, isChecked ->
            dbHelper.setBooleanSetting(
                SettingsContract.SettingsEntry.KEY_BRING_TO_FOREGROUND,
                isChecked
            )
        }
        
        // Enable Cashu handler checkbox
        cbEnableCashuHandler.setOnCheckedChangeListener { _, isChecked ->
            dbHelper.setBooleanSetting(
                SettingsContract.SettingsEntry.KEY_ENABLE_CASHU_HANDLER,
                isChecked
            )
        }
        
        // Cashu use app checkbox
        cbCashuUseApp.setOnCheckedChangeListener { _, isChecked ->
            dbHelper.setBooleanSetting(
                SettingsContract.SettingsEntry.KEY_CASHU_USE_APP,
                isChecked
            )
        }
        
        // Cashu use internal browser checkbox
        cbCashuUseInternalBrowser.setOnCheckedChangeListener { _, isChecked ->
            dbHelper.setBooleanSetting(
                SettingsContract.SettingsEntry.KEY_CASHU_USE_INTERNAL_BROWSER,
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
    
    /**
     * Validate and save Cashu settings
     */
    private fun validateAndSaveCashuSettings() {
        // Validate and save Cashu URL pattern
        val cashuUrlPattern = etCashuUrlPattern.text.toString()
        if (cashuUrlPattern.isNotEmpty() && cashuUrlPattern != originalCashuUrlPattern) {
            // Ensure the URL pattern contains the {token} placeholder
            val validUrlPattern = if (cashuUrlPattern.contains("{token}")) {
                cashuUrlPattern
            } else {
                // If no placeholder, append it to the end
                "$cashuUrlPattern{token}"
            }
            
            Log.d(TAG, "Saving Cashu URL pattern: $validUrlPattern")
            dbHelper.setSetting(
                SettingsContract.SettingsEntry.KEY_CASHU_URL_PATTERN,
                validUrlPattern
            )
        }
    }
    
    /**
     * Update the NFC component state based on the background NFC setting
     * This enables or disables the NFC intent filters in the manifest
     */
    private fun updateNfcComponentState(enabled: Boolean) {
        val packageManager = packageManager
        val componentName = ComponentName(this, MainActivity::class.java)
        
        // Get the current component state
        val componentState = packageManager.getComponentEnabledSetting(componentName)
        
        // Calculate the new state
        val newState = if (enabled) {
            PackageManager.COMPONENT_ENABLED_STATE_DEFAULT
        } else {
            // When disabled, we still want the app to be launchable normally,
            // but we'll disable the NFC intent filters in MainActivity
            PackageManager.COMPONENT_ENABLED_STATE_DEFAULT
        }
        
        // Only update if the state has changed
        if (componentState != newState) {
            Log.d(TAG, "Updating NFC component state to: $newState")
            
            try {
                // This approach doesn't work well for disabling specific intent filters
                // Instead, we'll use a flag in the app to control this behavior
                // packageManager.setComponentEnabledSetting(componentName, newState, PackageManager.DONT_KILL_APP)
                
                // Broadcast an intent to notify the app about the setting change
                val intent = Intent(ACTION_BACKGROUND_NFC_SETTING_CHANGED)
                intent.putExtra(EXTRA_BACKGROUND_NFC_ENABLED, enabled)
                sendBroadcast(intent)
                
                Log.d(TAG, "Sent broadcast to update NFC background behavior: enabled=$enabled")
            } catch (e: Exception) {
                Log.e(TAG, "Error updating component state: ${e.message}")
            }
        }
    }
    
    companion object {
        // Action for broadcasting NFC background setting changes
        const val ACTION_BACKGROUND_NFC_SETTING_CHANGED = "com.example.nfcdemo.ACTION_BACKGROUND_NFC_SETTING_CHANGED"
        const val EXTRA_BACKGROUND_NFC_ENABLED = "background_nfc_enabled"
    }
    
    override fun onDestroy() {
        super.onDestroy()
        dbHelper.close()
    }
} 