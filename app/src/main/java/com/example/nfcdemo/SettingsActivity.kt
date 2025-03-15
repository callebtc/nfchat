package com.example.nfcdemo

import android.app.Activity
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.widget.CheckBox
import android.widget.EditText
import android.widget.ImageView
import com.example.nfcdemo.data.MessageDbHelper
import com.example.nfcdemo.data.SettingsContract

class SettingsActivity : Activity() {
    
    private lateinit var dbHelper: MessageDbHelper
    private lateinit var cbAutoOpenLinks: CheckBox
    private lateinit var cbAutoSendShared: CheckBox
    private lateinit var cbCloseAfterSharedSend: CheckBox
    private lateinit var etMaxChunkSize: EditText
    private lateinit var etChunkDelay: EditText
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
        etMaxChunkSize = findViewById(R.id.etMaxChunkSize)
        etChunkDelay = findViewById(R.id.etChunkDelay)
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
        
        // Load max chunk size setting
        val maxChunkSize = dbHelper.getSetting(
            SettingsContract.SettingsEntry.KEY_MAX_CHUNK_SIZE,
            "500"
        )
        etMaxChunkSize.setText(maxChunkSize)
        
        // Load chunk delay setting
        val chunkDelay = dbHelper.getSetting(
            SettingsContract.SettingsEntry.KEY_CHUNK_DELAY,
            "200"
        )
        etChunkDelay.setText(chunkDelay)
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
        
        // Max chunk size text change listener
        etMaxChunkSize.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            
            override fun afterTextChanged(s: Editable?) {
                val value = s.toString()
                if (value.isNotEmpty()) {
                    val chunkSize = value.toIntOrNull() ?: 500
                    // Ensure minimum chunk size of 100
                    val validChunkSize = maxOf(chunkSize, 100)
                    if (validChunkSize.toString() != value) {
                        etMaxChunkSize.setText(validChunkSize.toString())
                        etMaxChunkSize.setSelection(validChunkSize.toString().length)
                    } else {
                        dbHelper.setSetting(
                            SettingsContract.SettingsEntry.KEY_MAX_CHUNK_SIZE,
                            validChunkSize.toString()
                        )
                    }
                }
            }
        })
        
        // Chunk delay text change listener
        etChunkDelay.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            
            override fun afterTextChanged(s: Editable?) {
                val value = s.toString()
                if (value.isNotEmpty()) {
                    val delay = value.toIntOrNull() ?: 200
                    // Ensure minimum delay of 50ms
                    val validDelay = maxOf(delay, 50)
                    if (validDelay.toString() != value) {
                        etChunkDelay.setText(validDelay.toString())
                        etChunkDelay.setSelection(validDelay.toString().length)
                    } else {
                        dbHelper.setSetting(
                            SettingsContract.SettingsEntry.KEY_CHUNK_DELAY,
                            validDelay.toString()
                        )
                    }
                }
            }
        })
    }
    
    override fun onDestroy() {
        super.onDestroy()
        dbHelper.close()
    }
} 