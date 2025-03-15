package com.example.nfcdemo.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.provider.BaseColumns
import android.util.Log
import com.example.nfcdemo.MessageAdapter
import java.util.Date

/**
 * Database helper for the messages database.
 * Manages database creation and version management.
 */
class MessageDbHelper(context: Context) : SQLiteOpenHelper(context, DATABASE_NAME, null, DATABASE_VERSION) {

    companion object {
        private const val TAG = "MessageDbHelper"
        
        // If you change the database schema, you must increment the database version
        const val DATABASE_VERSION = 1
        const val DATABASE_NAME = "Messages.db"

        // SQL statement to create the messages table - not using const because it uses string interpolation
        private val SQL_CREATE_MESSAGES_TABLE = 
            "CREATE TABLE ${MessageContract.MessageEntry.TABLE_NAME} (" +
            "${BaseColumns._ID} INTEGER PRIMARY KEY, " +
            "${MessageContract.MessageEntry.COLUMN_CONTENT} TEXT NOT NULL, " +
            "${MessageContract.MessageEntry.COLUMN_IS_SENT} INTEGER NOT NULL, " +
            "${MessageContract.MessageEntry.COLUMN_IS_DELIVERED} INTEGER NOT NULL, " +
            "${MessageContract.MessageEntry.COLUMN_TIMESTAMP} INTEGER NOT NULL)"

        // SQL statement to delete the messages table
        private val SQL_DELETE_MESSAGES_TABLE = "DROP TABLE IF EXISTS ${MessageContract.MessageEntry.TABLE_NAME}"
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(SQL_CREATE_MESSAGES_TABLE)
        Log.d(TAG, "Database created with version $DATABASE_VERSION")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // This database is only a cache for messages, so its upgrade policy is
        // to simply discard the data and start over
        // In a real app, you would migrate the data!
        Log.d(TAG, "Upgrading database from version $oldVersion to $newVersion")
        
        // Example of a migration path
        when (oldVersion) {
            1 -> {
                // Upgrade from version 1 to 2
                // Example: db.execSQL("ALTER TABLE ${MessageContract.MessageEntry.TABLE_NAME} ADD COLUMN new_column TEXT")
            }
            // Add more cases for future migrations
        }
        
        // If you want to start fresh (not recommended for production)
        // db.execSQL(SQL_DELETE_MESSAGES_TABLE)
        // onCreate(db)
    }

    override fun onDowngrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        onUpgrade(db, oldVersion, newVersion)
    }

    /**
     * Insert a new message into the database
     */
    fun insertMessage(message: MessageAdapter.Message): Long {
        val db = writableDatabase
        
        val values = ContentValues().apply {
            put(MessageContract.MessageEntry.COLUMN_CONTENT, message.content)
            put(MessageContract.MessageEntry.COLUMN_IS_SENT, if (message.isSent) 1 else 0)
            put(MessageContract.MessageEntry.COLUMN_IS_DELIVERED, if (message.isDelivered) 1 else 0)
            put(MessageContract.MessageEntry.COLUMN_TIMESTAMP, message.timestamp.time)
        }
        
        return db.insert(MessageContract.MessageEntry.TABLE_NAME, null, values)
    }

    /**
     * Update a message's delivery status
     */
    fun updateMessageDeliveryStatus(messageId: Long, isDelivered: Boolean): Int {
        val db = writableDatabase
        
        val values = ContentValues().apply {
            put(MessageContract.MessageEntry.COLUMN_IS_DELIVERED, if (isDelivered) 1 else 0)
        }
        
        return db.update(
            MessageContract.MessageEntry.TABLE_NAME,
            values,
            "${BaseColumns._ID} = ?",
            arrayOf(messageId.toString())
        )
    }

    /**
     * Get the most recent messages, limited by count
     */
    fun getRecentMessages(limit: Int = 100): List<MessageAdapter.Message> {
        val db = readableDatabase
        
        val projection = arrayOf(
            BaseColumns._ID,
            MessageContract.MessageEntry.COLUMN_CONTENT,
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
        
        with(cursor) {
            while (moveToNext()) {
                val content = getString(getColumnIndexOrThrow(MessageContract.MessageEntry.COLUMN_CONTENT))
                val isSent = getInt(getColumnIndexOrThrow(MessageContract.MessageEntry.COLUMN_IS_SENT)) == 1
                val isDelivered = getInt(getColumnIndexOrThrow(MessageContract.MessageEntry.COLUMN_IS_DELIVERED)) == 1
                val timestamp = getLong(getColumnIndexOrThrow(MessageContract.MessageEntry.COLUMN_TIMESTAMP))
                
                messages.add(
                    MessageAdapter.Message(
                        content = content,
                        isSent = isSent,
                        isDelivered = isDelivered,
                        timestamp = Date(timestamp)
                    )
                )
            }
        }
        cursor.close()
        
        return messages
    }

    /**
     * Delete all messages from the database
     */
    fun clearAllMessages(): Int {
        val db = writableDatabase
        return db.delete(MessageContract.MessageEntry.TABLE_NAME, null, null)
    }
} 