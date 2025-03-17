package com.example.nfcdemo.handlers

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import com.example.nfcdemo.data.AppConstants
import com.example.nfcdemo.data.MessageDbHelper
import com.example.nfcdemo.data.SettingsContract
import java.util.regex.Pattern

/**
 * Handler for processing and opening Cashu tokens in messages
 */
class CashuHandler : MessageHandler {
    companion object {
        private const val TAG = "CashuHandler"
        
        // Pattern to match Cashu tokens (cashuA... and cashuB...)
        private val CASHU_TOKEN_PATTERN = Pattern.compile("\\b(cashuA\\w+|cashuB\\w+)\\b")
    }
    
    /**
     * Process a message to find and open Cashu tokens
     * @param context The context
     * @param message The message to process
     * @param dbHelper The database helper for accessing settings
     * @return true if Cashu tokens were found and processed, false otherwise
     */
    override fun processMessage(context: Context, message: String, dbHelper: MessageDbHelper): Boolean {
        // Find Cashu tokens in the message
        val matcher = CASHU_TOKEN_PATTERN.matcher(message)
        var found = false
        
        while (matcher.find()) {
            val token = matcher.group()
            if (token != null) {
                Log.d(TAG, "Found Cashu token: $token")
                openCashuToken(context, token, dbHelper)
                found = true
            }
        }
        
        return found
    }
    
    /**
     * Check if this handler is enabled based on settings
     * @param dbHelper The database helper for accessing settings
     * @return true if the handler is enabled, false otherwise
     */
    override fun isEnabled(dbHelper: MessageDbHelper): Boolean {
        return dbHelper.getBooleanSetting(
            SettingsContract.SettingsEntry.KEY_ENABLE_CASHU_HANDLER, 
            AppConstants.DefaultSettings.ENABLE_CASHU_HANDLER
        )
    }
    
    /**
     * Open a Cashu token based on app settings
     * @param context The context
     * @param token The Cashu token to open
     * @param dbHelper The database helper for accessing settings
     */
    private fun openCashuToken(context: Context, token: String, dbHelper: MessageDbHelper) {
        // Check if we should use a dedicated app
        val useApp = dbHelper.getBooleanSetting(
            SettingsContract.SettingsEntry.KEY_CASHU_USE_APP, 
            AppConstants.DefaultSettings.CASHU_USE_APP
        )
        
        if (useApp) {
            // Open with a dedicated app using the cashu: scheme
            openWithApp(context, token)
        } else {
            // Open with a web URL
            openWithWebUrl(context, token, dbHelper)
        }
    }
    
    /**
     * Open a Cashu token with a dedicated app
     * @param context The context
     * @param token The Cashu token to open
     */
    private fun openWithApp(context: Context, token: String) {
        try {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse("cashu:$token"))
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
        } catch (e: Exception) {
            Log.e(TAG, "Error opening Cashu token with app: ${e.message}")
            // Fallback to web URL if app opening fails
            openWithWebUrl(context, token, MessageDbHelper(context))
        }
    }
    
    /**
     * Open a Cashu token with a web URL
     * @param context The context
     * @param token The Cashu token to open
     * @param dbHelper The database helper for accessing settings
     */
    private fun openWithWebUrl(context: Context, token: String, dbHelper: MessageDbHelper) {
        // Get the URL pattern from settings
        val urlPattern = dbHelper.getSetting(
            SettingsContract.SettingsEntry.KEY_CASHU_URL_PATTERN,
            AppConstants.DefaultSettingsStrings.CASHU_URL_PATTERN
        )
        
        // Replace {token} placeholder with the actual token
        val url = urlPattern.replace("{token}", token)

        // Check if we should use the internal browser
        val useInternalBrowser = dbHelper.getBooleanSetting(
            SettingsContract.SettingsEntry.KEY_USE_INTERNAL_BROWSER, 
            AppConstants.DefaultSettings.USE_INTERNAL_BROWSER
        )
        
        // Use the LinkHandler to open the URL (reusing the same browser settings)
        val linkHandler = LinkHandler()
        linkHandler.openUrl(context, url, dbHelper, useInternalBrowser)
    }
} 