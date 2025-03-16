package com.example.nfcdemo.handlers

import android.content.Context
import android.util.Log
import com.example.nfcdemo.data.MessageDbHelper

/**
 * Manager class for handling message processing through registered handlers
 */
object MessageHandlerManager {
    private const val TAG = "MessageHandlerManager"
    
    // List of registered message handlers
    private val handlers = mutableListOf<MessageHandler>()
    
    /**
     * Register a message handler
     * @param handler The handler to register
     */
    fun registerHandler(handler: MessageHandler) {
        handlers.add(handler)
        Log.d(TAG, "Registered handler: ${handler.javaClass.simpleName}")
    }
    
    /**
     * Unregister a message handler
     * @param handler The handler to unregister
     */
    fun unregisterHandler(handler: MessageHandler) {
        handlers.remove(handler)
        Log.d(TAG, "Unregistered handler: ${handler.javaClass.simpleName}")
    }
    
    /**
     * Process a message through all registered handlers
     * @param context The context
     * @param message The message to process
     * @param dbHelper The database helper for accessing settings
     * @return true if at least one handler processed the message, false otherwise
     */
    fun processMessage(context: Context, message: String, dbHelper: MessageDbHelper): Boolean {
        var handled = false
        
        // Process the message through all enabled handlers
        for (handler in handlers) {
            if (handler.isEnabled(dbHelper)) {
                try {
                    val result = handler.processMessage(context, message, dbHelper)
                    if (result) {
                        Log.d(TAG, "Message handled by: ${handler.javaClass.simpleName}")
                        handled = true
                        // return early:
                        return true
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error processing message with handler ${handler.javaClass.simpleName}: ${e.message}")
                }
            }
        }
        
        return handled
    }
    
    /**
     * Get all registered handlers
     * @return List of registered handlers
     */
    fun getHandlers(): List<MessageHandler> {
        return handlers.toList()
    }
    
    /**
     * Clear all registered handlers
     */
    fun clearHandlers() {
        handlers.clear()
        Log.d(TAG, "Cleared all handlers")
    }
} 