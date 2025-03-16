package com.example.nfcdemo.handlers

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import android.util.Patterns
import com.example.nfcdemo.WebViewActivityManager
import com.example.nfcdemo.data.AppConstants
import com.example.nfcdemo.data.MessageDbHelper
import com.example.nfcdemo.data.SettingsContract

/**
 * Handler for processing and opening links in messages
 */
class LinkHandler : MessageHandler {
    companion object {
        private const val TAG = "LinkHandler"
    }
    
    /**
     * Process a message to find and open links
     * @param context The context
     * @param message The message to process
     * @param dbHelper The database helper for accessing settings
     * @return true if links were found and processed, false otherwise
     */
    override fun processMessage(context: Context, message: String, dbHelper: MessageDbHelper): Boolean {
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
                return true
            }
        }
        return false
    }
    
    /**
     * Check if this handler is enabled based on settings
     * @param dbHelper The database helper for accessing settings
     * @return true if the handler is enabled, false otherwise
     */
    override fun isEnabled(dbHelper: MessageDbHelper): Boolean {
        return dbHelper.getBooleanSetting(
            SettingsContract.SettingsEntry.KEY_AUTO_OPEN_LINKS, 
            AppConstants.DefaultSettings.AUTO_OPEN_LINKS
        )
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