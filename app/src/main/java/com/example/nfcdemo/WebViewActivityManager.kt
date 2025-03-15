package com.example.nfcdemo

import java.lang.ref.WeakReference

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
} 