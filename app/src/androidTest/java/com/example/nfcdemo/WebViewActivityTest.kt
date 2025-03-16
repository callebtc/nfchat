package com.example.nfcdemo

import android.content.Intent
import android.net.Uri
import android.webkit.WebView
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.*
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class WebViewActivityTest {

    private lateinit var activityScenario: ActivityScenario<WebViewActivity>
    private val testUrl = "https://example.com"

    @Before
    fun setUp() {
        // Create an intent with a test URL
        val intent = Intent(ApplicationProvider.getApplicationContext(), WebViewActivity::class.java).apply {
            putExtra(WebViewActivity.EXTRA_URL, testUrl)
        }
        
        // Launch the activity with the intent
        activityScenario = ActivityScenario.launch(intent)
    }

    @After
    fun tearDown() {
        activityScenario.close()
    }

    @Test
    fun testInitialState() {
        // Verify the WebView is displayed
        onView(withId(R.id.webView)).check(matches(isDisplayed()))
        
        // Verify the toolbar is displayed
        onView(withId(R.id.toolbar)).check(matches(isDisplayed()))
        
        // Verify the URL is loaded in the WebView
        activityScenario.onActivity { activity ->
            val webView = activity.findViewById<WebView>(R.id.webView)
            assertEquals(testUrl, webView.url)
        }
    }
    
    @Test
    fun testBackButton() {
        // First, verify the back button is displayed
        onView(withId(R.id.btnBack)).check(matches(isDisplayed()))
        
        // Initially, the back button should be disabled since we haven't navigated anywhere
        activityScenario.onActivity { activity ->
            val webView = activity.findViewById<WebView>(R.id.webView)
            assertFalse(webView.canGoBack())
        }
        
        // We can't easily test navigation in the WebView without a real web page,
        // but we can verify that clicking the back button doesn't crash the app
        onView(withId(R.id.btnBack)).perform(click())
        
        // Verify the activity is still running
        activityScenario.onActivity { activity ->
            assertNotNull(activity)
        }
    }
    
    @Test
    fun testForwardButton() {
        // First, verify the forward button is displayed
        onView(withId(R.id.btnForward)).check(matches(isDisplayed()))
        
        // Initially, the forward button should be disabled since we haven't navigated anywhere
        activityScenario.onActivity { activity ->
            val webView = activity.findViewById<WebView>(R.id.webView)
            assertFalse(webView.canGoForward())
        }
        
        // We can't easily test navigation in the WebView without a real web page,
        // but we can verify that clicking the forward button doesn't crash the app
        onView(withId(R.id.btnForward)).perform(click())
        
        // Verify the activity is still running
        activityScenario.onActivity { activity ->
            assertNotNull(activity)
        }
    }
    
    @Test
    fun testRefreshButton() {
        // Verify the refresh button is displayed
        onView(withId(R.id.btnRefresh)).check(matches(isDisplayed()))
        
        // Click the refresh button
        onView(withId(R.id.btnRefresh)).perform(click())
        
        // Verify the activity is still running
        activityScenario.onActivity { activity ->
            assertNotNull(activity)
            
            // Verify the WebView is still showing the same URL
            val webView = activity.findViewById<WebView>(R.id.webView)
            assertEquals(testUrl, webView.url)
        }
    }
    
    @Test
    fun testShareButton() {
        // Verify the share button is displayed
        onView(withId(R.id.btnShare)).check(matches(isDisplayed()))
        
        // We can't easily test the share functionality since it launches a system dialog,
        // but we can verify that clicking the share button doesn't crash the app
        onView(withId(R.id.btnShare)).perform(click())
        
        // Verify the activity is still running
        activityScenario.onActivity { activity ->
            assertNotNull(activity)
        }
    }
    
    @Test
    fun testOpenInBrowserButton() {
        // Verify the open in browser button is displayed
        onView(withId(R.id.btnOpenInBrowser)).check(matches(isDisplayed()))
        
        // We can't easily test the external browser launch,
        // but we can verify that clicking the button doesn't crash the app
        onView(withId(R.id.btnOpenInBrowser)).perform(click())
        
        // Verify the activity is still running (it should be, even after launching an external intent)
        activityScenario.onActivity { activity ->
            assertNotNull(activity)
        }
    }
    
    @Test
    fun testWebViewActivityManager() {
        // Verify that the WebViewActivity is registered with the WebViewActivityManager
        activityScenario.onActivity { activity ->
            val currentWebView = WebViewActivityManager.getCurrentWebViewActivity()
            assertNotNull(currentWebView)
            assertEquals(activity, currentWebView)
        }
        
        // Close the activity
        activityScenario.close()
        
        // Verify that the WebViewActivity is removed from the WebViewActivityManager
        assertNull(WebViewActivityManager.getCurrentWebViewActivity())
    }
    
    @Test
    fun testLoadNewUrl() {
        val initialUrl = testUrl
        val newUrl = "https://example.org"
        
        // Verify the initial URL is loaded
        activityScenario.onActivity { activity ->
            val webView = activity.findViewById<WebView>(R.id.webView)
            assertEquals(initialUrl, webView.url)
        }
        
        // Use a CountDownLatch to wait for the URL to load
        val latch = CountDownLatch(1)
        
        // Load a new URL using the loadNewUrl method
        activityScenario.onActivity { activity ->
            // Set a WebViewClient to detect when the page is loaded
            val webView = activity.findViewById<WebView>(R.id.webView)
            webView.webViewClient = object : android.webkit.WebViewClient() {
                override fun onPageFinished(view: WebView?, url: String?) {
                    super.onPageFinished(view, url)
                    if (url == newUrl) {
                        latch.countDown()
                    }
                }
            }
            
            // Call the loadNewUrl method
            activity.loadNewUrl(newUrl)
        }
        
        // Wait for the page to load (with timeout)
        latch.await(5, TimeUnit.SECONDS)
        
        // Verify the new URL is loaded
        activityScenario.onActivity { activity ->
            val webView = activity.findViewById<WebView>(R.id.webView)
            assertEquals(newUrl, webView.url)
        }
    }
} 