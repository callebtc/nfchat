package com.example.nfcdemo.data

import android.provider.BaseColumns

/**
 * Contract class that defines the database schema
 */
object MessageContract {
    // Table contents are defined inside this object
    object MessageEntry : BaseColumns {
        const val TABLE_NAME = "messages"
        const val COLUMN_CONTENT = "content"
        const val COLUMN_MESSAGE_ID = "message_id" // Unique ID for duplicate detection
        const val COLUMN_IS_SENT = "is_sent"
        const val COLUMN_IS_DELIVERED = "is_delivered"
        const val COLUMN_TIMESTAMP = "timestamp"
    }
} 