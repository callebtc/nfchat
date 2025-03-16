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
                // Prepend http:// if the URL doesn't have a scheme
                val fullUrl = if (!url.startsWith("http://") && !url.startsWith("https://")) {
                    "http://$url"
                } else {
                    url
                }
                
                // Check if we should use the internal browser
                val useInternalBrowser = dbHelper.getBooleanSetting(
                    SettingsContract.SettingsEntry.KEY_USE_INTERNAL_BROWSER, 
                    AppConstants.DefaultSettings.USE_INTERNAL_BROWSER
                )
                
                if (useInternalBrowser) {
                    openUrlInInternalBrowser(context, fullUrl)
                } else {
                    openUrlInExternalBrowser(context, fullUrl)
                }
            }
        }
    }
    
    /**
     * Open a URL in the internal WebView
     */
    private fun openUrlInInternalBrowser(context: Context, url: String) {
        // Check if there's already a WebViewActivity open
        val currentWebView = WebViewActivityManager.getCurrentWebViewActivity()
        if (currentWebView != null) {
            // Close the existing WebView first
            currentWebView.finish()
            
            // Small delay to ensure the previous activity is properly closed
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                // Open the URL in a new WebView
                val intent = Intent(context, WebViewActivity::class.java)
                intent.putExtra(WebViewActivity.EXTRA_URL, url)
                context.startActivity(intent)
            }, 100)
        } else {
            // Open the URL in an internal WebView
            val intent = Intent(context, WebViewActivity::class.java)
            intent.putExtra(WebViewActivity.EXTRA_URL, url)
            context.startActivity(intent)
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