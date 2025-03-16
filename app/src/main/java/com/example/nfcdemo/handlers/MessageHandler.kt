package com.example.nfcdemo.handlers

import android.content.Context
import com.example.nfcdemo.data.MessageDbHelper

/**
 * Interface for message handlers that can process and react to messages
 */
interface MessageHandler {
    /**
     * Process a message and perform any actions needed
     * @param context The context
     * @param message The message content to process
     * @param dbHelper The database helper for accessing settings
     * @return true if the message was handled, false otherwise
     */
    fun processMessage(context: Context, message: String, dbHelper: MessageDbHelper): Boolean
    
    /**
     * Check if this handler is enabled based on settings
     * @param dbHelper The database helper for accessing settings
     * @return true if the handler is enabled, false otherwise
     */
    fun isEnabled(dbHelper: MessageDbHelper): Boolean
} 