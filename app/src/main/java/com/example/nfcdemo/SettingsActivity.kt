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
    private lateinit var btnBack: ImageView
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)
        
        // Initialize database helper
        dbHelper = MessageDbHelper(this)
        
        // Initialize views
        cbAutoOpenLinks = findViewById(R.id.cbAutoOpenLinks)
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
    }
    
    override fun onDestroy() {
        super.onDestroy()
        dbHelper.close()
    }
} 