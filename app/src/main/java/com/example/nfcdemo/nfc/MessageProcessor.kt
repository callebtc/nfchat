package com.example.nfcdemo.nfc

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import android.util.Patterns
import com.example.nfcdemo.WebViewActivity
import com.example.nfcdemo.WebViewActivityManager
import com.example.nfcdemo.data.AppConstants
import com.example.nfcdemo.data.MessageDbHelper
import com.example.nfcdemo.data.SettingsContract
import com.example.nfcdemo.handlers.CashuHandler
import com.example.nfcdemo.handlers.LinkHandler
import com.example.nfcdemo.handlers.MessageHandlerManager

/**
 * Utility class for processing messages
 */
object MessageProcessor {
    private const val TAG = "MessageProcessor"
    private var handlersInitialized = false
    
    /**
     * Initialize the message handlers
     */
    private fun initializeHandlers() {
        if (!handlersInitialized) {
            // Register the handlers
            MessageHandlerManager.registerHandler(LinkHandler())
            MessageHandlerManager.registerHandler(CashuHandler())
            
            handlersInitialized = true
            Log.d(TAG, "Message handlers initialized")
        }
    }
    
    /**
     * Process a received message
     * @param context The context
     * @param messageData The message data
     * @param dbHelper The database helper
     * @return The processed message content
     */
    fun processReceivedMessage(
        context: Context, 
        messageData: MessageData,
        dbHelper: MessageDbHelper
    ): String {
        val messageContent = messageData.content
        
        // Initialize handlers if needed
        initializeHandlers()
        
        // Process the message through all registered handlers
        MessageHandlerManager.processMessage(context, messageContent, dbHelper)
        
        return messageContent
    }
    
    /**
     * Find and open links in a message (legacy method, kept for backward compatibility)
     * @param context The context
     * @param message The message to process
     * @param dbHelper The database helper
     */
    fun openLinksInMessage(
        context: Context, 
        message: String,
        dbHelper: MessageDbHelper
    ) {
        // Initialize handlers if needed
        initializeHandlers()
        
        // Use the LinkHandler directly
        val linkHandler = LinkHandler()
        if (linkHandler.isEnabled(dbHelper)) {
            linkHandler.processMessage(context, message, dbHelper)
        }
    }
    
    /**
     * Open a URL based on app settings (legacy method, kept for backward compatibility)
     * @param context The context
     * @param url The URL to open
     * @param dbHelper The database helper for accessing settings
     */
    fun openUrl(context: Context, url: String, dbHelper: MessageDbHelper) {
        // Use the LinkHandler to open the URL
        val linkHandler = LinkHandler()
        linkHandler.openUrl(context, url, dbHelper)
    }
} 