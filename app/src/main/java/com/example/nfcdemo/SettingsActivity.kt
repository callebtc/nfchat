package com.example.nfcdemo

import android.app.Activity
import android.os.Bundle
import android.widget.CheckBox
import android.widget.ImageView
import com.example.nfcdemo.data.MessageDbHelper
import com.example.nfcdemo.data.SettingsContract

class SettingsActivity : Activity() {
    
    private lateinit var dbHelper: MessageDbHelper
    private lateinit var cbAutoOpenLinks: CheckBox
    private lateinit var cbAutoSendShared: CheckBox
    private lateinit var cbCloseAfterSharedSend: CheckBox
    private lateinit var btnBack: ImageView
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)
        
        // Initialize database helper
        dbHelper = MessageDbHelper(this)
        
        // Initialize views
        cbAutoOpenLinks = findViewById(R.id.cbAutoOpenLinks)
        cbAutoSendShared = findViewById(R.id.cbAutoSendShared)
        cbCloseAfterSharedSend = findViewById(R.id.cbCloseAfterSharedSend)
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
    }
    
    private fun setupListeners() {
        // Back button
        btnBack.setOnClickListener {
            finish()
        }
        
        // Auto open links checkbox
        cbAutoOpenLinks.setOnCheckedChangeListener { _, isChecked ->
            dbHelper.setBooleanSetting(
                SettingsContract.SettingsEntry.KEY_AUTO_OPEN_LINKS,
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
    
    override fun onDestroy() {
        super.onDestroy()
        dbHelper.close()
    }
} 