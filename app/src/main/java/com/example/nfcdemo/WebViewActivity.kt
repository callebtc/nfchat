package com.example.nfcdemo

import android.app.Activity
import android.os.Bundle
import android.view.View
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView

/**
 * Activity for displaying web content in an internal browser
 */
class WebViewActivity : Activity() {
    
    companion object {
        const val EXTRA_URL = "extra_url"
    }
    
    private lateinit var webView: WebView
    private lateinit var progressBar: ProgressBar
    private lateinit var btnBack: ImageView
    private lateinit var btnForward: ImageView
    private lateinit var btnClose: ImageView
    private lateinit var tvTitle: TextView
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_web_view)
        
        // Register this activity with the WebViewActivityManager
        WebViewActivityManager.setCurrentWebViewActivity(this)
        
        // Create WebView data directory if needed
        createWebViewDataDirectory()
        
        // Get the URL from the intent
        val url = intent.getStringExtra(EXTRA_URL) ?: "about:blank"
        
        // Initialize views
        webView = findViewById(R.id.webView)
        progressBar = findViewById(R.id.progressBar)
        btnBack = findViewById(R.id.btnBack)
        btnForward = findViewById(R.id.btnForward)
        btnClose = findViewById(R.id.btnClose)
        tvTitle = findViewById(R.id.tvTitle)
        
        // Set up WebView
        setupWebView()
        
        // Set up button listeners
        setupButtonListeners()
        
        // Load the URL
        webView.loadUrl(url)
    }
    
    /**
     * Create WebView data directory if it doesn't exist
     * This can help with storage issues on some devices
     */
    private fun createWebViewDataDirectory() {
        val dataDir = applicationContext.getDir("webview", 0)
        if (!dataDir.exists()) {
            dataDir.mkdirs()
        }
    }
    
    private fun setupWebView() {
        // Enable JavaScript
        webView.settings.apply {
            javaScriptEnabled = true
            
            // Enable DOM storage (localStorage and sessionStorage)
            domStorageEnabled = true
            
            // Enable database storage
            databaseEnabled = true
            
            // Set cache mode
            cacheMode = android.webkit.WebSettings.LOAD_DEFAULT
            
            // Enable mixed content (http content in https pages)
            mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            
            // Enable geolocation
            setGeolocationEnabled(true)
            
            // Allow file access
            allowFileAccess = true
            
            // Set user agent to desktop version if needed
            // userAgentString = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/91.0.4472.124 Safari/537.36"
        }
        
        // Set WebViewClient to handle page navigation
        webView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                // Update navigation buttons
                updateNavigationButtons()
            }
            
            override fun onReceivedError(view: WebView?, request: android.webkit.WebResourceRequest?, error: android.webkit.WebResourceError?) {
                super.onReceivedError(view, request, error)
                // Log errors for debugging
                android.util.Log.e("WebViewActivity", "WebView error: ${error?.description}")
            }
            
            // Handle SSL errors
            override fun onReceivedSslError(view: WebView?, handler: android.webkit.SslErrorHandler?, error: android.net.http.SslError?) {
                android.util.Log.e("WebViewActivity", "SSL Error: ${error?.toString()}")
                // Proceed anyway (only for development/testing)
                handler?.proceed()
                // In production, you might want to show a dialog to the user
            }
        }
        
        // Set WebChromeClient to handle progress and title
        webView.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                super.onProgressChanged(view, newProgress)
                // Update progress bar
                progressBar.progress = newProgress
                progressBar.visibility = if (newProgress < 100) View.VISIBLE else View.GONE
            }
            
            override fun onReceivedTitle(view: WebView?, title: String?) {
                super.onReceivedTitle(view, title)
                // Update title
                tvTitle.text = title ?: getString(R.string.app_name)
            }
            
            // Handle JavaScript alerts
            override fun onJsAlert(view: WebView?, url: String?, message: String?, result: android.webkit.JsResult?): Boolean {
                android.util.Log.d("WebViewActivity", "JS Alert: $message")
                result?.confirm()
                return true
            }
            
            // Handle geolocation permissions
            override fun onGeolocationPermissionsShowPrompt(origin: String?, callback: android.webkit.GeolocationPermissions.Callback?) {
                callback?.invoke(origin, true, false)
            }
            
            // Log console messages
            override fun onConsoleMessage(consoleMessage: android.webkit.ConsoleMessage?): Boolean {
                android.util.Log.d("WebViewConsole", "${consoleMessage?.message()} -- From line ${consoleMessage?.lineNumber()} of ${consoleMessage?.sourceId()}")
                return true
            }
        }
    }
    
    private fun setupButtonListeners() {
        // Back button
        btnBack.setOnClickListener {
            if (webView.canGoBack()) {
                webView.goBack()
            }
        }
        
        // Forward button
        btnForward.setOnClickListener {
            if (webView.canGoForward()) {
                webView.goForward()
            }
        }
        
        // Close button
        btnClose.setOnClickListener {
            finish()
        }
    }
    
    private fun updateNavigationButtons() {
        // Update back button
        btnBack.alpha = if (webView.canGoBack()) 1.0f else 0.5f
        btnBack.isEnabled = webView.canGoBack()
        
        // Update forward button
        btnForward.alpha = if (webView.canGoForward()) 1.0f else 0.5f
        btnForward.isEnabled = webView.canGoForward()
    }
    
    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        // Go back in WebView history if possible
        if (webView.canGoBack()) {
            webView.goBack()
        } else {
            super.onBackPressed()
        }
    }
    
    // For newer Android versions, we can use onKeyDown instead
    override fun onKeyDown(keyCode: Int, event: android.view.KeyEvent?): Boolean {
        // Check if the key event was the Back button and if there's history
        if (keyCode == android.view.KeyEvent.KEYCODE_BACK && webView.canGoBack()) {
            webView.goBack()
            return true
        }
        // If it wasn't the Back key or there's no web page history, bubble up to the default
        // system behavior (probably exit the activity)
        return super.onKeyDown(keyCode, event)
    }
    
    override fun onDestroy() {
        // Clear WebView cache and cookies
        clearWebViewCache()
        
        // Properly destroy the WebView
        destroyWebView()
        
        // Unregister this activity from the WebViewActivityManager
        WebViewActivityManager.clearCurrentWebViewActivity()
        
        super.onDestroy()
    }
    
    /**
     * Clear WebView cache and cookies
     */
    private fun clearWebViewCache() {
        webView.clearCache(true)
        android.webkit.CookieManager.getInstance().removeAllCookies(null)
        android.webkit.CookieManager.getInstance().flush()
    }
    
    /**
     * Properly destroy the WebView to prevent memory leaks and background processes
     */
    private fun destroyWebView() {
        // Remove the WebView from its parent view before destroying
        val parent = webView.parent as? android.view.ViewGroup
        parent?.removeView(webView)
        
        // Stop loading, clear history
        webView.stopLoading()
        webView.clearHistory()
        
        // Clear form data and all other state
        webView.clearFormData()
        webView.clearSslPreferences()
        
        // Destroy the WebView
        webView.destroy()
    }
    
    override fun onPause() {
        super.onPause()
        // Pause JavaScript execution and WebView processing when activity is not visible
        webView.onPause()
    }
    
    override fun onResume() {
        super.onResume()
        // Resume JavaScript execution and WebView processing when activity becomes visible
        webView.onResume()
    }
} 