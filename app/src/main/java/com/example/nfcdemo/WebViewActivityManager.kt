package com.example.nfcdemo

import android.content.Context
import android.content.Intent
import java.lang.ref.WeakReference
import android.util.Log
/**
 * Singleton manager to keep track of the current WebViewActivity instance
 * This allows us to reuse an existing WebViewActivity instead of creating a new one
 */
object WebViewActivityManager {
    private var currentWebViewActivity: WeakReference<WebViewActivity>? = null
    
    /**
     * Set the current WebViewActivity instance
     */
    fun setCurrentWebViewActivity(activity: WebViewActivity) {
        currentWebViewActivity = WeakReference(activity)
    }
    
    /**
     * Get the current WebViewActivity instance, or null if none exists or it has been garbage collected
     */
    fun getCurrentWebViewActivity(): WebViewActivity? {
        return currentWebViewActivity?.get()
    }
    
    /**
     * Clear the current WebViewActivity reference
     */
    fun clearCurrentWebViewActivity() {
        currentWebViewActivity = null
    }
    
    /**
     * Load a URL in the WebView, reusing an existing WebViewActivity if available
     * @param context The context to use for starting a new activity if needed
     * @param url The URL to load
     */
    fun loadUrl(context: Context, url: String) {
        val currentActivity = getCurrentWebViewActivity()
        
        if (currentActivity != null) {
            // Reuse existing WebViewActivity
            Log.d("WebViewActivityManager", "Reusing existing WebViewActivity")
            currentActivity.loadNewUrl(url)
        } else {
            // Create a new WebViewActivity
            Log.d("WebViewActivityManager", "Creating new WebViewActivity")
            val intent = Intent(context, WebViewActivity::class.java)
            intent.putExtra(WebViewActivity.EXTRA_URL, url)
            context.startActivity(intent)
        }
    }
} 