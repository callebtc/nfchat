package com.example.nfcdemo

import android.content.Context
import com.example.nfcdemo.data.AppConstants
import com.example.nfcdemo.data.MessageDbHelper
import com.example.nfcdemo.data.SettingsContract
import com.example.nfcdemo.handlers.CashuHandler
import com.example.nfcdemo.handlers.LinkHandler
import com.example.nfcdemo.handlers.MessageHandler
import com.example.nfcdemo.handlers.MessageHandlerManager
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class MessageHandlerTest {
    
    private lateinit var mockContext: Context
    private lateinit var mockDbHelper: MessageDbHelper
    
    @Before
    fun setUp() {
        // Create mock objects
        mockContext = Mockito.mock(Context::class.java)
        mockDbHelper = Mockito.mock(MessageDbHelper::class.java)
        
        // Clear all handlers before each test
        MessageHandlerManager.clearHandlers()
    }
    
    @After
    fun tearDown() {
        // Clear all handlers after each test
        MessageHandlerManager.clearHandlers()
    }
    
    @Test
    fun testLinkHandlerEnabled() {
        // Set up the mock to return true for auto open links
        Mockito.`when`(mockDbHelper.getBooleanSetting(
            SettingsContract.SettingsEntry.KEY_AUTO_OPEN_LINKS,
            AppConstants.DefaultSettings.AUTO_OPEN_LINKS
        )).thenReturn(true)
        
        val linkHandler = LinkHandler()
        assertTrue(linkHandler.isEnabled(mockDbHelper))
    }
    
    @Test
    fun testLinkHandlerDisabled() {
        // Set up the mock to return false for auto open links
        Mockito.`when`(mockDbHelper.getBooleanSetting(
            SettingsContract.SettingsEntry.KEY_AUTO_OPEN_LINKS,
            AppConstants.DefaultSettings.AUTO_OPEN_LINKS
        )).thenReturn(false)
        
        val linkHandler = LinkHandler()
        assertFalse(linkHandler.isEnabled(mockDbHelper))
    }
    
    @Test
    fun testCashuHandlerEnabled() {
        // Set up the mock to return true for enable cashu handler
        Mockito.`when`(mockDbHelper.getBooleanSetting(
            SettingsContract.SettingsEntry.KEY_ENABLE_CASHU_HANDLER,
            AppConstants.DefaultSettings.ENABLE_CASHU_HANDLER
        )).thenReturn(true)
        
        val cashuHandler = CashuHandler()
        assertTrue(cashuHandler.isEnabled(mockDbHelper))
    }
    
    @Test
    fun testCashuHandlerDisabled() {
        // Set up the mock to return false for enable cashu handler
        Mockito.`when`(mockDbHelper.getBooleanSetting(
            SettingsContract.SettingsEntry.KEY_ENABLE_CASHU_HANDLER,
            AppConstants.DefaultSettings.ENABLE_CASHU_HANDLER
        )).thenReturn(false)
        
        val cashuHandler = CashuHandler()
        assertFalse(cashuHandler.isEnabled(mockDbHelper))
    }
    
    @Test
    fun testMessageHandlerManagerProcessMessageAllDisabled() {
        // Create mock handlers
        val mockHandler1 = Mockito.mock(MessageHandler::class.java)
        val mockHandler2 = Mockito.mock(MessageHandler::class.java)
        
        // Set up the mocks to return false for isEnabled
        Mockito.`when`(mockHandler1.isEnabled(mockDbHelper)).thenReturn(false)
        Mockito.`when`(mockHandler2.isEnabled(mockDbHelper)).thenReturn(false)
        
        // Register the mock handlers
        MessageHandlerManager.registerHandler(mockHandler1)
        MessageHandlerManager.registerHandler(mockHandler2)
        
        // Process a message
        val result = MessageHandlerManager.processMessage(mockContext, "test message", mockDbHelper)
        
        // Verify the result is false (no handler processed the message)
        assertFalse(result)
    }
} 