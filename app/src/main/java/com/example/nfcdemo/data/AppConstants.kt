package com.example.nfcdemo.data

/**
 * Constants used throughout the application
 */
object AppConstants {
    // Default settings values
    object DefaultSettings {
        // Link settings
        const val AUTO_OPEN_LINKS = true
        const val USE_INTERNAL_BROWSER = false
        
        // Sharing settings
        const val AUTO_SEND_SHARED = true
        const val CLOSE_AFTER_SHARED_SEND = false
        
        // Background behavior settings
        const val ENABLE_BACKGROUND_NFC = true
        const val BRING_TO_FOREGROUND = true
        
        // Chunked message transfer settings
        const val MAX_CHUNK_SIZE = 2048
        const val CHUNK_DELAY_MS = 50L
        const val TRANSFER_RETRY_TIMEOUT_MS = 5000L
        
        // Message settings
        const val MESSAGE_LENGTH_LIMIT = 200
        
        // Transfer settings
        const val MAX_SEND_ATTEMPTS = 3
        const val VIBRATION_DURATION_MS = 200L
    }
    
    // String representations of default values for database storage
    object DefaultSettingsStrings {
        const val MAX_CHUNK_SIZE = DefaultSettings.MAX_CHUNK_SIZE.toString()
        const val CHUNK_DELAY_MS = DefaultSettings.CHUNK_DELAY_MS.toString()
        const val TRANSFER_RETRY_TIMEOUT_MS = DefaultSettings.TRANSFER_RETRY_TIMEOUT_MS.toString()
    }
    
    // Minimum values for settings
    object MinimumValues {
        const val MIN_CHUNK_SIZE = 100
        const val MIN_CHUNK_DELAY_MS = 50
        const val MIN_TRANSFER_RETRY_TIMEOUT_MS = 0 // 0 means disabled
    }
} 