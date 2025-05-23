package com.example.nfcdemo.data

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.provider.BaseColumns
import android.util.Log
import com.example.nfcdemo.MessageAdapter
import java.util.Date
import java.util.UUID

/**
 * Database helper for the messages database.
 * Manages database creation and version management.
 */
class MessageDbHelper(context: Context, dbName: String? = DATABASE_NAME) : 
    SQLiteOpenHelper(context, dbName ?: DATABASE_NAME, null, DATABASE_VERSION) {

    companion object {
        private const val TAG = "MessageDbHelper"
        
        // If you change the database schema, you must increment the database version
        const val DATABASE_VERSION = 6
        const val DATABASE_NAME = "Messages.db"

        // SQL statement to create the messages table - not using const because it uses string interpolation
        private val SQL_CREATE_MESSAGES_TABLE = 
            "CREATE TABLE ${MessageContract.MessageEntry.TABLE_NAME} (" +
            "${BaseColumns._ID} INTEGER PRIMARY KEY, " +
            "${MessageContract.MessageEntry.COLUMN_CONTENT} TEXT NOT NULL, " +
            "${MessageContract.MessageEntry.COLUMN_MESSAGE_ID} TEXT NOT NULL, " +
            "${MessageContract.MessageEntry.COLUMN_IS_SENT} INTEGER NOT NULL, " +
            "${MessageContract.MessageEntry.COLUMN_IS_DELIVERED} INTEGER NOT NULL, " +
            "${MessageContract.MessageEntry.COLUMN_TIMESTAMP} INTEGER NOT NULL)"
            
        // SQL statement to create the settings table
        private val SQL_CREATE_SETTINGS_TABLE =
            "CREATE TABLE ${SettingsContract.SettingsEntry.TABLE_NAME} (" +
            "${BaseColumns._ID} INTEGER PRIMARY KEY, " +
            "${SettingsContract.SettingsEntry.COLUMN_KEY} TEXT UNIQUE NOT NULL, " +
            "${SettingsContract.SettingsEntry.COLUMN_VALUE} TEXT NOT NULL)"

        // SQL statement to delete the messages table
        private val SQL_DELETE_MESSAGES_TABLE = "DROP TABLE IF EXISTS ${MessageContract.MessageEntry.TABLE_NAME}"
        
        // SQL statement to delete the settings table
        private val SQL_DELETE_SETTINGS_TABLE = "DROP TABLE IF EXISTS ${SettingsContract.SettingsEntry.TABLE_NAME}"
    }

    // Lock object for synchronization
    private val dbLock = Object()

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(SQL_CREATE_MESSAGES_TABLE)
        db.execSQL(SQL_CREATE_SETTINGS_TABLE)
        
        // Insert default settings
        insertDefaultSettings(db)
        
        Log.d(TAG, "Database created with version $DATABASE_VERSION")
    }
    
    private fun insertDefaultSettings(db: SQLiteDatabase) {
        // Auto open links is enabled by default
        val autoOpenLinksValues = ContentValues().apply {
            put(SettingsContract.SettingsEntry.COLUMN_KEY, SettingsContract.SettingsEntry.KEY_AUTO_OPEN_LINKS)
            put(SettingsContract.SettingsEntry.COLUMN_VALUE, AppConstants.DefaultSettings.AUTO_OPEN_LINKS.toString())
        }
        db.insert(SettingsContract.SettingsEntry.TABLE_NAME, null, autoOpenLinksValues)
        
        // Use internal browser is disabled by default
        val useInternalBrowserValues = ContentValues().apply {
            put(SettingsContract.SettingsEntry.COLUMN_KEY, SettingsContract.SettingsEntry.KEY_USE_INTERNAL_BROWSER)
            put(SettingsContract.SettingsEntry.COLUMN_VALUE, AppConstants.DefaultSettings.USE_INTERNAL_BROWSER.toString())
        }
        db.insert(SettingsContract.SettingsEntry.TABLE_NAME, null, useInternalBrowserValues)
        
        // Auto send shared content is enabled by default
        val autoSendSharedValues = ContentValues().apply {
            put(SettingsContract.SettingsEntry.COLUMN_KEY, SettingsContract.SettingsEntry.KEY_AUTO_SEND_SHARED)
            put(SettingsContract.SettingsEntry.COLUMN_VALUE, AppConstants.DefaultSettings.AUTO_SEND_SHARED.toString())
        }
        db.insert(SettingsContract.SettingsEntry.TABLE_NAME, null, autoSendSharedValues)
        
        // Close after sending shared message is disabled by default
        val closeAfterSharedSendValues = ContentValues().apply {
            put(SettingsContract.SettingsEntry.COLUMN_KEY, SettingsContract.SettingsEntry.KEY_CLOSE_AFTER_SHARED_SEND)
            put(SettingsContract.SettingsEntry.COLUMN_VALUE, AppConstants.DefaultSettings.CLOSE_AFTER_SHARED_SEND.toString())
        }
        db.insert(SettingsContract.SettingsEntry.TABLE_NAME, null, closeAfterSharedSendValues)
        
        // Enable background NFC is enabled by default
        val enableBackgroundNfcValues = ContentValues().apply {
            put(SettingsContract.SettingsEntry.COLUMN_KEY, SettingsContract.SettingsEntry.KEY_ENABLE_BACKGROUND_NFC)
            put(SettingsContract.SettingsEntry.COLUMN_VALUE, AppConstants.DefaultSettings.ENABLE_BACKGROUND_NFC.toString())
        }
        db.insert(SettingsContract.SettingsEntry.TABLE_NAME, null, enableBackgroundNfcValues)
        
        // Bring to foreground on message received is enabled by default
        val bringToForegroundValues = ContentValues().apply {
            put(SettingsContract.SettingsEntry.COLUMN_KEY, SettingsContract.SettingsEntry.KEY_BRING_TO_FOREGROUND)
            put(SettingsContract.SettingsEntry.COLUMN_VALUE, AppConstants.DefaultSettings.BRING_TO_FOREGROUND.toString())
        }
        db.insert(SettingsContract.SettingsEntry.TABLE_NAME, null, bringToForegroundValues)
        
        // Default max chunk size
        val maxChunkSizeValues = ContentValues().apply {
            put(SettingsContract.SettingsEntry.COLUMN_KEY, SettingsContract.SettingsEntry.KEY_MAX_CHUNK_SIZE)
            put(SettingsContract.SettingsEntry.COLUMN_VALUE, AppConstants.DefaultSettingsStrings.MAX_CHUNK_SIZE)
        }
        db.insert(SettingsContract.SettingsEntry.TABLE_NAME, null, maxChunkSizeValues)
        
        // Default chunk delay
        val chunkDelayValues = ContentValues().apply {
            put(SettingsContract.SettingsEntry.COLUMN_KEY, SettingsContract.SettingsEntry.KEY_CHUNK_DELAY)
            put(SettingsContract.SettingsEntry.COLUMN_VALUE, AppConstants.DefaultSettingsStrings.CHUNK_DELAY_MS)
        }
        db.insert(SettingsContract.SettingsEntry.TABLE_NAME, null, chunkDelayValues)
        
        // Default transfer retry timeout
        val transferRetryTimeoutValues = ContentValues().apply {
            put(SettingsContract.SettingsEntry.COLUMN_KEY, SettingsContract.SettingsEntry.KEY_TRANSFER_RETRY_TIMEOUT_MS)
            put(SettingsContract.SettingsEntry.COLUMN_VALUE, AppConstants.DefaultSettingsStrings.TRANSFER_RETRY_TIMEOUT_MS)
        }
        db.insert(SettingsContract.SettingsEntry.TABLE_NAME, null, transferRetryTimeoutValues)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        Log.d(TAG, "Upgrading database from version $oldVersion to $newVersion")
        
        // Migration path
        when (oldVersion) {
            1 -> {
                // Upgrade from version 1 to 2 - Add settings table
                db.execSQL(SQL_CREATE_SETTINGS_TABLE)
                insertDefaultSettings(db)
            }
            2 -> {
                // Upgrade from version 2 to 3 - Add new settings
                // Auto send shared content is enabled by default
                val autoSendSharedValues = ContentValues().apply {
                    put(SettingsContract.SettingsEntry.COLUMN_KEY, SettingsContract.SettingsEntry.KEY_AUTO_SEND_SHARED)
                    put(SettingsContract.SettingsEntry.COLUMN_VALUE, "true")
                }
                db.insert(SettingsContract.SettingsEntry.TABLE_NAME, null, autoSendSharedValues)
                
                // Close after sending shared message is disabled by default
                val closeAfterSharedSendValues = ContentValues().apply {
                    put(SettingsContract.SettingsEntry.COLUMN_KEY, SettingsContract.SettingsEntry.KEY_CLOSE_AFTER_SHARED_SEND)
                    put(SettingsContract.SettingsEntry.COLUMN_VALUE, "false")
                }
                db.insert(SettingsContract.SettingsEntry.TABLE_NAME, null, closeAfterSharedSendValues)
                
                // Use internal browser is disabled by default
                val useInternalBrowserValues = ContentValues().apply {
                    put(SettingsContract.SettingsEntry.COLUMN_KEY, SettingsContract.SettingsEntry.KEY_USE_INTERNAL_BROWSER)
                    put(SettingsContract.SettingsEntry.COLUMN_VALUE, "false")
                }
                db.insert(SettingsContract.SettingsEntry.TABLE_NAME, null, useInternalBrowserValues)
            }
            3, 4 -> {
                // Upgrade from version 3 or 4 to 5 - Add chunked message settings
                // Default max chunk size
                val maxChunkSizeValues = ContentValues().apply {
                    put(SettingsContract.SettingsEntry.COLUMN_KEY, SettingsContract.SettingsEntry.KEY_MAX_CHUNK_SIZE)
                    put(SettingsContract.SettingsEntry.COLUMN_VALUE, AppConstants.DefaultSettingsStrings.MAX_CHUNK_SIZE)
                }
                db.insert(SettingsContract.SettingsEntry.TABLE_NAME, null, maxChunkSizeValues)
                
                // Default chunk delay
                val chunkDelayValues = ContentValues().apply {
                    put(SettingsContract.SettingsEntry.COLUMN_KEY, SettingsContract.SettingsEntry.KEY_CHUNK_DELAY)
                    put(SettingsContract.SettingsEntry.COLUMN_VALUE, AppConstants.DefaultSettingsStrings.CHUNK_DELAY_MS)
                }
                db.insert(SettingsContract.SettingsEntry.TABLE_NAME, null, chunkDelayValues)
                
                // Default transfer retry timeout
                val transferRetryTimeoutValues = ContentValues().apply {
                    put(SettingsContract.SettingsEntry.COLUMN_KEY, SettingsContract.SettingsEntry.KEY_TRANSFER_RETRY_TIMEOUT_MS)
                    put(SettingsContract.SettingsEntry.COLUMN_VALUE, AppConstants.DefaultSettingsStrings.TRANSFER_RETRY_TIMEOUT_MS)
                }
                db.insert(SettingsContract.SettingsEntry.TABLE_NAME, null, transferRetryTimeoutValues)
            }
            5 -> {
                // Upgrade from version 5 to 6 - Add background behavior settings
                // Enable background NFC is enabled by default
                val enableBackgroundNfcValues = ContentValues().apply {
                    put(SettingsContract.SettingsEntry.COLUMN_KEY, SettingsContract.SettingsEntry.KEY_ENABLE_BACKGROUND_NFC)
                    put(SettingsContract.SettingsEntry.COLUMN_VALUE, AppConstants.DefaultSettings.ENABLE_BACKGROUND_NFC.toString())
                }
                db.insert(SettingsContract.SettingsEntry.TABLE_NAME, null, enableBackgroundNfcValues)
                
                // Bring to foreground on message received is enabled by default
                val bringToForegroundValues = ContentValues().apply {
                    put(SettingsContract.SettingsEntry.COLUMN_KEY, SettingsContract.SettingsEntry.KEY_BRING_TO_FOREGROUND)
                    put(SettingsContract.SettingsEntry.COLUMN_VALUE, AppConstants.DefaultSettings.BRING_TO_FOREGROUND.toString())
                }
                db.insert(SettingsContract.SettingsEntry.TABLE_NAME, null, bringToForegroundValues)
            }
            // Add more cases for future migrations
        }
        
        // If we're upgrading across multiple versions, we need to handle each step
        if (oldVersion < newVersion - 1) {
            onUpgrade(db, oldVersion + 1, newVersion)
        }
    }

    override fun onDowngrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        onUpgrade(db, oldVersion, newVersion)
    }

    /**
     * Insert a new message into the database
     */
    fun insertMessage(message: MessageAdapter.Message): Long {
        synchronized(dbLock) {
            val db = writableDatabase
            try {
                val values = ContentValues().apply {
                    put(MessageContract.MessageEntry.COLUMN_CONTENT, message.content)
                    put(MessageContract.MessageEntry.COLUMN_MESSAGE_ID, message.messageId)
                    put(MessageContract.MessageEntry.COLUMN_IS_SENT, if (message.isSent) 1 else 0)
                    put(MessageContract.MessageEntry.COLUMN_IS_DELIVERED, if (message.isDelivered) 1 else 0)
                    put(MessageContract.MessageEntry.COLUMN_TIMESTAMP, message.timestamp.time)
                }
                return db.insert(MessageContract.MessageEntry.TABLE_NAME, null, values)
            } finally {
                db.close()
            }
        }
    }

    /**
     * Update a message's delivery status
     */
    fun updateMessageDeliveryStatus(messageId: Long, isDelivered: Boolean): Int {
        synchronized(dbLock) {
            val db = writableDatabase
            try {
                val values = ContentValues().apply {
                    put(MessageContract.MessageEntry.COLUMN_IS_DELIVERED, if (isDelivered) 1 else 0)
                }
                return db.update(
                    MessageContract.MessageEntry.TABLE_NAME,
                    values,
                    "${BaseColumns._ID} = ?",
                    arrayOf(messageId.toString())
                )
            } finally {
                db.close()
            }
        }
    }

    /**
     * Get the most recent messages, limited by count
     */
    fun getRecentMessages(limit: Int): List<MessageAdapter.Message> {
        synchronized(dbLock) {
            val db = readableDatabase
            try {
                val projection = arrayOf(
                    BaseColumns._ID,
                    MessageContract.MessageEntry.COLUMN_CONTENT,
                    MessageContract.MessageEntry.COLUMN_MESSAGE_ID,
                    MessageContract.MessageEntry.COLUMN_IS_SENT,
                    MessageContract.MessageEntry.COLUMN_IS_DELIVERED,
                    MessageContract.MessageEntry.COLUMN_TIMESTAMP
                )
                
                val sortOrder = "${MessageContract.MessageEntry.COLUMN_TIMESTAMP} ASC"
                
                val cursor = db.query(
                    MessageContract.MessageEntry.TABLE_NAME,
                    projection,
                    null,
                    null,
                    null,
                    null,
                    sortOrder,
                    limit.toString()
                )
                
                val messages = mutableListOf<MessageAdapter.Message>()
                
                cursor.use {
                    while (it.moveToNext()) {
                        val content = it.getString(it.getColumnIndexOrThrow(MessageContract.MessageEntry.COLUMN_CONTENT))
                        val messageId = it.getString(it.getColumnIndexOrThrow(MessageContract.MessageEntry.COLUMN_MESSAGE_ID))
                        val isSent = it.getInt(it.getColumnIndexOrThrow(MessageContract.MessageEntry.COLUMN_IS_SENT)) == 1
                        val isDelivered = it.getInt(it.getColumnIndexOrThrow(MessageContract.MessageEntry.COLUMN_IS_DELIVERED)) == 1
                        val timestamp = it.getLong(it.getColumnIndexOrThrow(MessageContract.MessageEntry.COLUMN_TIMESTAMP))
                        
                        messages.add(
                            MessageAdapter.Message(
                                content = content,
                                messageId = messageId,
                                isSent = isSent,
                                isDelivered = isDelivered,
                                timestamp = Date(timestamp)
                            )
                        )
                    }
                }
                
                return messages
            } finally {
                db.close()
            }
        }
    }

    /**
     * Get a setting value by key
     * @param key The setting key
     * @param defaultValue The default value to return if the setting is not found
     * @return The setting value or defaultValue if not found
     */
    fun getSetting(key: String, defaultValue: String): String {
        synchronized(dbLock) {
            val db = readableDatabase
            try {
                val projection = arrayOf(SettingsContract.SettingsEntry.COLUMN_VALUE)
                val selection = "${SettingsContract.SettingsEntry.COLUMN_KEY} = ?"
                val selectionArgs = arrayOf(key)
                
                val cursor = db.query(
                    SettingsContract.SettingsEntry.TABLE_NAME,
                    projection,
                    selection,
                    selectionArgs,
                    null,
                    null,
                    null
                )
                
                var value = defaultValue
                
                cursor.use {
                    if (it.moveToFirst()) {
                        value = it.getString(it.getColumnIndexOrThrow(SettingsContract.SettingsEntry.COLUMN_VALUE))
                    }
                }
                
                return value
            } finally {
                db.close()
            }
        }
    }
    
    /**
     * Get a boolean setting value by key
     * @param key The setting key
     * @param defaultValue The default value to return if the setting is not found
     * @return The setting value as a boolean or defaultValue if not found
     */
    fun getBooleanSetting(key: String, defaultValue: Boolean): Boolean {
        val stringValue = getSetting(key, defaultValue.toString())
        return stringValue.toBoolean()
    }
    
    /**
     * Set a setting value
     * @param key The setting key
     * @param value The setting value
     * @return The number of rows affected
     */
    fun setSetting(key: String, value: String): Long {
        synchronized(dbLock) {
            val db = writableDatabase
            try {
                val values = ContentValues().apply {
                    put(SettingsContract.SettingsEntry.COLUMN_KEY, key)
                    put(SettingsContract.SettingsEntry.COLUMN_VALUE, value)
                }
                
                // Try to update first
                val rowsAffected = db.update(
                    SettingsContract.SettingsEntry.TABLE_NAME,
                    values,
                    "${SettingsContract.SettingsEntry.COLUMN_KEY} = ?",
                    arrayOf(key)
                )
                
                // If no rows were updated, insert a new row
                return if (rowsAffected > 0) {
                    rowsAffected.toLong()
                } else {
                    db.insert(SettingsContract.SettingsEntry.TABLE_NAME, null, values)
                }
            } finally {
                db.close()
            }
        }
    }
    
    /**
     * Set a boolean setting value
     * @param key The setting key
     * @param value The boolean setting value
     * @return The number of rows affected
     */
    fun setBooleanSetting(key: String, value: Boolean): Long {
        return setSetting(key, value.toString())
    }

    /**
     * Get all messages from the database
     */
    fun getAllMessages(): List<MessageAdapter.Message> {
        // Simply call getRecentMessages with a very large limit
        return getRecentMessages(Int.MAX_VALUE)
    }

    /**
     * Add a message to the database (for testing)
     */
    fun addMessage(content: String, isSent: Boolean, isDelivered: Boolean): Long {
        val db = writableDatabase
        
        val values = ContentValues().apply {
            put(MessageContract.MessageEntry.COLUMN_CONTENT, content)
            put(MessageContract.MessageEntry.COLUMN_MESSAGE_ID, UUID.randomUUID().toString())
            put(MessageContract.MessageEntry.COLUMN_IS_SENT, if (isSent) 1 else 0)
            put(MessageContract.MessageEntry.COLUMN_IS_DELIVERED, if (isDelivered) 1 else 0)
            put(MessageContract.MessageEntry.COLUMN_TIMESTAMP, System.currentTimeMillis())
        }
        
        return db.insert(MessageContract.MessageEntry.TABLE_NAME, null, values)
    }

    /**
     * Get messages from the database (for testing)
     */
    fun getMessages(limit: Int): Cursor {
        val db = readableDatabase
        
        val projection = arrayOf(
            BaseColumns._ID,
            MessageContract.MessageEntry.COLUMN_CONTENT,
            MessageContract.MessageEntry.COLUMN_MESSAGE_ID,
            MessageContract.MessageEntry.COLUMN_IS_SENT,
            MessageContract.MessageEntry.COLUMN_IS_DELIVERED,
            MessageContract.MessageEntry.COLUMN_TIMESTAMP
        )
        
        val sortOrder = "${MessageContract.MessageEntry.COLUMN_TIMESTAMP} ASC"
        
        return db.query(
            MessageContract.MessageEntry.TABLE_NAME,
            projection,
            null,
            null,
            null,
            null,
            sortOrder,
            limit.toString()
        )
    }

    /**
     * Delete a message from the database (for testing)
     */
    fun deleteMessage(messageId: Long): Int {
        val db = writableDatabase
        
        return db.delete(
            MessageContract.MessageEntry.TABLE_NAME,
            "${BaseColumns._ID} = ?",
            arrayOf(messageId.toString())
        )
    }

    /**
     * Save a setting (for testing)
     */
    fun saveSetting(key: String, value: String): Long {
        return setSetting(key, value)
    }

    /**
     * Clear all messages from the database
     */
    fun clearAllMessages(): Int {
        synchronized(dbLock) {
            val db = writableDatabase
            try {
                return db.delete(MessageContract.MessageEntry.TABLE_NAME, null, null)
            } finally {
                db.close()
            }
        }
    }
} 