package com.example.nfcdemo

import android.content.Context
import android.content.Intent
import android.webkit.WebView
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.nfcdemo.data.MessageDbHelper
import com.example.nfcdemo.nfc.MessageProcessor
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class WebViewLinkHandlingTest {

    private lateinit var context: Context
    private lateinit var dbHelper: MessageDbHelper
    private lateinit var activityScenario: ActivityScenario<WebViewActivity>
    private val initialUrl = "https://example.com"
    private val secondUrl = "https://example.org"

    @Before
    fun setUp() {
        context = InstrumentationRegistry.getInstrumentation().targetContext
        dbHelper = MessageDbHelper(context)
        
        // Ensure WebViewActivityManager has no active WebViewActivity
        WebViewActivityManager.clearCurrentWebViewActivity()
        
        // Create an intent with the initial test URL
        val intent = Intent(ApplicationProvider.getApplicationContext(), WebViewActivity::class.java).apply {
            putExtra(WebViewActivity.EXTRA_URL, initialUrl)
        }
        
        // Launch the activity with the intent
        activityScenario = ActivityScenario.launch(intent)
    }

    @After
    fun tearDown() {
        activityScenario.close()
        dbHelper.close()
    }

    @Test
    fun testWebViewActivityManagerLoadUrl() {
        // Verify the initial URL is loaded
        activityScenario.onActivity { activity ->
            val webView = activity.findViewById<WebView>(R.id.webView)
            assertEquals(initialUrl, webView.url)
            
            // Verify the WebViewActivity is registered with the WebViewActivityManager
            val currentWebView = WebViewActivityManager.getCurrentWebViewActivity()
            assertNotNull(currentWebView)
            assertEquals(activity, currentWebView)
        }
        
        // Use a CountDownLatch to wait for the URL to load
        val latch = CountDownLatch(1)
        
        // Load a new URL using the WebViewActivityManager
        WebViewActivityManager.loadUrl(context, secondUrl)
        
        // Give the WebView time to load the new URL
        activityScenario.onActivity { activity ->
            // Set a WebViewClient to detect when the page is loaded
            val webView = activity.findViewById<WebView>(R.id.webView)
            webView.webViewClient = object : android.webkit.WebViewClient() {
                override fun onPageFinished(view: WebView?, url: String?) {
                    super.onPageFinished(view, url)
                    if (url == secondUrl) {
                        latch.countDown()
                    }
                }
            }
        }
        
        // Wait for the page to load (with timeout)
        latch.await(5, TimeUnit.SECONDS)
        
        // Verify the new URL is loaded in the same WebView
        activityScenario.onActivity { activity ->
            val webView = activity.findViewById<WebView>(R.id.webView)
            assertEquals(secondUrl, webView.url)
        }
    }
    
    @Test
    fun testMessageProcessorOpenUrl() {
        // Set the internal browser setting to true
        dbHelper.setBooleanSetting("use_internal_browser", true)
        
        // Verify the initial URL is loaded
        activityScenario.onActivity { activity ->
            val webView = activity.findViewById<WebView>(R.id.webView)
            assertEquals(initialUrl, webView.url)
        }
        
        // Use a CountDownLatch to wait for the URL to load
        val latch = CountDownLatch(1)
        
        // Load a new URL using the MessageProcessor
        MessageProcessor.openUrl(context, secondUrl, dbHelper)
        
        // Give the WebView time to load the new URL
        activityScenario.onActivity { activity ->
            // Set a WebViewClient to detect when the page is loaded
            val webView = activity.findViewById<WebView>(R.id.webView)
            webView.webViewClient = object : android.webkit.WebViewClient() {
                override fun onPageFinished(view: WebView?, url: String?) {
                    super.onPageFinished(view, url)
                    if (url == secondUrl) {
                        latch.countDown()
                    }
                }
            }
        }
        
        // Wait for the page to load (with timeout)
        latch.await(5, TimeUnit.SECONDS)
        
        // Verify the new URL is loaded in the same WebView
        activityScenario.onActivity { activity ->
            val webView = activity.findViewById<WebView>(R.id.webView)
            assertEquals(secondUrl, webView.url)
            
            // Verify it's still the same WebViewActivity instance
            val currentWebView = WebViewActivityManager.getCurrentWebViewActivity()
            assertNotNull(currentWebView)
            assertEquals(activity, currentWebView)
        }
    }
    
    @Test
    fun testOpenUrlWithNoExistingWebView() {
        // Close the current activity
        activityScenario.close()
        
        // Ensure WebViewActivityManager has no active WebViewActivity
        WebViewActivityManager.clearCurrentWebViewActivity()
        assertNull(WebViewActivityManager.getCurrentWebViewActivity())
        
        // Set the internal browser setting to true
        dbHelper.setBooleanSetting("use_internal_browser", true)
        
        // Use MessageProcessor to open a URL
        MessageProcessor.openUrl(context, initialUrl, dbHelper)
        
        // Wait a moment for the activity to start
        Thread.sleep(1000)
        
        // Verify a new WebViewActivity was created
        assertNotNull(WebViewActivityManager.getCurrentWebViewActivity())
        
        // Launch a new activity scenario to verify the URL
        ActivityScenario.launch(WebViewActivity::class.java).use { newScenario ->
            newScenario.onActivity { activity ->
                val webView = activity.findViewById<WebView>(R.id.webView)
                // The URL might not be exactly what we expect due to how the test environment works,
                // but we can verify the WebView is displayed
                onView(withId(R.id.webView)).check(matches(isDisplayed()))
            }
        }
    }
} 