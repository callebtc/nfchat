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

/**
 * Utility class for processing messages
 */
object MessageProcessor {
    private const val TAG = "MessageProcessor"
    
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
        
        // Check if auto-open links is enabled
        if (dbHelper.getBooleanSetting(SettingsContract.SettingsEntry.KEY_AUTO_OPEN_LINKS, AppConstants.DefaultSettings.AUTO_OPEN_LINKS)) {
            openLinksInMessage(context, messageContent, dbHelper)
        }
        
        return messageContent
    }
    
    /**
     * Find and open links in a message
     * @param context The context
     * @param message The message to process
     * @param dbHelper The database helper
     */
    fun openLinksInMessage(
        context: Context, 
        message: String,
        dbHelper: MessageDbHelper
    ) {
        // Find URLs in the message
        val matcher = Patterns.WEB_URL.matcher(message)
        if (matcher.find()) {
            val url = matcher.group()
            if (url != null) {
                // Prepend https:// if the URL doesn't have a scheme
                val fullUrl = if (!url.startsWith("http://") && !url.startsWith("https://")) {
                    "https://$url"
                } else {
                    url
                }
                
                // Open the URL based on settings
                openUrl(context, fullUrl, dbHelper)
            }
        }
    }
    
    /**
     * Open a URL based on app settings (internal or external browser)
     * @param context The context
     * @param url The URL to open
     * @param dbHelper The database helper for accessing settings
     */
    fun openUrl(context: Context, url: String, dbHelper: MessageDbHelper) {
        // Check if we should use the internal browser
        val useInternalBrowser = dbHelper.getBooleanSetting(
            SettingsContract.SettingsEntry.KEY_USE_INTERNAL_BROWSER, 
            AppConstants.DefaultSettings.USE_INTERNAL_BROWSER
        )
        
        if (useInternalBrowser) {
            // Use the WebViewActivityManager to load the URL
            WebViewActivityManager.loadUrl(context, url)
        } else {
            // Open in external browser
            openUrlInExternalBrowser(context, url)
        }
    }
    
    /**
     * Open a URL in the external browser
     */
    private fun openUrlInExternalBrowser(context: Context, url: String) {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
        context.startActivity(intent)
    }
} 