package com.example.nfcdemo.data

import android.provider.BaseColumns

/**
 * Contract class that defines the settings table schema
 */
object SettingsContract {
    // Table contents are defined inside this object
    object SettingsEntry : BaseColumns {
        const val TABLE_NAME = "settings"
        const val COLUMN_KEY = "key"
        const val COLUMN_VALUE = "value"
        
        // Settings keys
        const val KEY_AUTO_OPEN_LINKS = "auto_open_links"
    }
} 