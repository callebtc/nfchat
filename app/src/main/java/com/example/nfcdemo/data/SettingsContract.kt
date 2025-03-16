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
        const val KEY_USE_INTERNAL_BROWSER = "use_internal_browser"
        const val KEY_AUTO_SEND_SHARED = "auto_send_shared"
        const val KEY_CLOSE_AFTER_SHARED_SEND = "close_after_shared_send"
        
        // Background behavior settings
        const val KEY_ENABLE_BACKGROUND_NFC = "enable_background_nfc"
        const val KEY_BRING_TO_FOREGROUND = "bring_to_foreground"
        
        // Advanced settings keys for chunked message transfer
        const val KEY_MAX_CHUNK_SIZE = "max_chunk_size"
        const val KEY_CHUNK_DELAY = "chunk_delay"
        const val KEY_TRANSFER_RETRY_TIMEOUT_MS = "transfer_retry_timeout_ms"
        
        // Cashu handler settings
        const val KEY_ENABLE_CASHU_HANDLER = "enable_cashu_handler"
        const val KEY_CASHU_URL_PATTERN = "cashu_url_pattern"
        const val KEY_CASHU_USE_APP = "cashu_use_app"
    }
} 