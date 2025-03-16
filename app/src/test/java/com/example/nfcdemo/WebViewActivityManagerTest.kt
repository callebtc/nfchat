package com.example.nfcdemo

import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mock
import org.mockito.Mockito.*
import org.mockito.junit.MockitoJUnitRunner

@RunWith(MockitoJUnitRunner::class)
class WebViewActivityManagerTest {

    @Mock
    private lateinit var mockActivity1: WebViewActivity
    
    @Mock
    private lateinit var mockActivity2: WebViewActivity

    @Before
    fun setUp() {
        // Reset the WebViewActivityManager before each test
        WebViewActivityManager.clearCurrentWebViewActivity()
    }

    @Test
    fun testSetAndGetCurrentWebViewActivity() {
        // Initially, there should be no current WebViewActivity
        assertNull(WebViewActivityManager.getCurrentWebViewActivity())
        
        // Set a WebViewActivity
        WebViewActivityManager.setCurrentWebViewActivity(mockActivity1)
        
        // Verify it was set
        assertEquals(mockActivity1, WebViewActivityManager.getCurrentWebViewActivity())
    }
    
    @Test
    fun testReplaceCurrentWebViewActivity() {
        // Set the first WebViewActivity
        WebViewActivityManager.setCurrentWebViewActivity(mockActivity1)
        assertEquals(mockActivity1, WebViewActivityManager.getCurrentWebViewActivity())
        
        // Replace it with a second WebViewActivity
        WebViewActivityManager.setCurrentWebViewActivity(mockActivity2)
        assertEquals(mockActivity2, WebViewActivityManager.getCurrentWebViewActivity())
    }
    
    @Test
    fun testClearCurrentWebViewActivity() {
        // Set a WebViewActivity
        WebViewActivityManager.setCurrentWebViewActivity(mockActivity1)
        assertEquals(mockActivity1, WebViewActivityManager.getCurrentWebViewActivity())
        
        // Clear it
        WebViewActivityManager.clearCurrentWebViewActivity()
        assertNull(WebViewActivityManager.getCurrentWebViewActivity())
    }
} 