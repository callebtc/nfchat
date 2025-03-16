package com.example.nfcdemo

import android.content.Context
import android.content.Intent
import android.net.Uri
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
import org.mockito.ArgumentMatchers.anyString
import org.mockito.ArgumentMatchers.eq
import org.mockito.Mockito.*
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
        mockContext = mock(Context::class.java)
        mockDbHelper = mock(MessageDbHelper::class.java)
        
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
        `when`(mockDbHelper.getBooleanSetting(
            eq(SettingsContract.SettingsEntry.KEY_AUTO_OPEN_LINKS),
            eq(AppConstants.DefaultSettings.AUTO_OPEN_LINKS)
        )).thenReturn(true)
        
        val linkHandler = LinkHandler()
        assertTrue(linkHandler.isEnabled(mockDbHelper))
    }
    
    @Test
    fun testLinkHandlerDisabled() {
        // Set up the mock to return false for auto open links
        `when`(mockDbHelper.getBooleanSetting(
            eq(SettingsContract.SettingsEntry.KEY_AUTO_OPEN_LINKS),
            eq(AppConstants.DefaultSettings.AUTO_OPEN_LINKS)
        )).thenReturn(false)
        
        val linkHandler = LinkHandler()
        assertFalse(linkHandler.isEnabled(mockDbHelper))
    }
    
    @Test
    fun testLinkHandlerProcessMessage() {
        // Set up the mock to return true for auto open links
        `when`(mockDbHelper.getBooleanSetting(
            eq(SettingsContract.SettingsEntry.KEY_AUTO_OPEN_LINKS),
            eq(AppConstants.DefaultSettings.AUTO_OPEN_LINKS)
        )).thenReturn(true)
        
        // Set up the mock to return false for use internal browser
        `when`(mockDbHelper.getBooleanSetting(
            eq(SettingsContract.SettingsEntry.KEY_USE_INTERNAL_BROWSER),
            eq(AppConstants.DefaultSettings.USE_INTERNAL_BROWSER)
        )).thenReturn(false)
        
        val linkHandler = spy(LinkHandler())
        
        // Mock the openUrl method to avoid actual URL opening
        doNothing().`when`(linkHandler).openUrl(any(), anyString(), any())
        
        // Test with a message containing a URL
        val messageWithUrl = "Check out this website: example.com"
        val result = linkHandler.processMessage(mockContext, messageWithUrl, mockDbHelper)
        
        // Verify the result is true (URL was found and processed)
        assertTrue(result)
        
        // Verify openUrl was called with the correct URL
        verify(linkHandler).openUrl(eq(mockContext), eq("https://example.com"), eq(mockDbHelper))
    }
    
    @Test
    fun testLinkHandlerProcessMessageNoUrl() {
        val linkHandler = spy(LinkHandler())
        
        // Mock the openUrl method to avoid actual URL opening
        doNothing().`when`(linkHandler).openUrl(any(), anyString(), any())
        
        // Test with a message not containing a URL
        val messageWithoutUrl = "This is a message without a URL"
        val result = linkHandler.processMessage(mockContext, messageWithoutUrl, mockDbHelper)
        
        // Verify the result is false (no URL was found)
        assertFalse(result)
        
        // Verify openUrl was not called
        verify(linkHandler, never()).openUrl(any(), anyString(), any())
    }
    
    @Test
    fun testCashuHandlerEnabled() {
        // Set up the mock to return true for enable cashu handler
        `when`(mockDbHelper.getBooleanSetting(
            eq(SettingsContract.SettingsEntry.KEY_ENABLE_CASHU_HANDLER),
            eq(AppConstants.DefaultSettings.ENABLE_CASHU_HANDLER)
        )).thenReturn(true)
        
        val cashuHandler = CashuHandler()
        assertTrue(cashuHandler.isEnabled(mockDbHelper))
    }
    
    @Test
    fun testCashuHandlerDisabled() {
        // Set up the mock to return false for enable cashu handler
        `when`(mockDbHelper.getBooleanSetting(
            eq(SettingsContract.SettingsEntry.KEY_ENABLE_CASHU_HANDLER),
            eq(AppConstants.DefaultSettings.ENABLE_CASHU_HANDLER)
        )).thenReturn(false)
        
        val cashuHandler = CashuHandler()
        assertFalse(cashuHandler.isEnabled(mockDbHelper))
    }
    
    @Test
    fun testCashuHandlerProcessMessage() {
        // Set up the mock to return true for enable cashu handler
        `when`(mockDbHelper.getBooleanSetting(
            eq(SettingsContract.SettingsEntry.KEY_ENABLE_CASHU_HANDLER),
            eq(AppConstants.DefaultSettings.ENABLE_CASHU_HANDLER)
        )).thenReturn(true)
        
        // Set up the mock to return false for use app
        `when`(mockDbHelper.getBooleanSetting(
            eq(SettingsContract.SettingsEntry.KEY_CASHU_USE_APP),
            eq(AppConstants.DefaultSettings.CASHU_USE_APP)
        )).thenReturn(false)
        
        // Set up the mock to return the URL pattern
        `when`(mockDbHelper.getSetting(
            eq(SettingsContract.SettingsEntry.KEY_CASHU_URL_PATTERN),
            eq(AppConstants.DefaultSettingsStrings.CASHU_URL_PATTERN)
        )).thenReturn(AppConstants.DefaultSettingsStrings.CASHU_URL_PATTERN)
        
        val cashuHandler = spy(CashuHandler())
        
        // Create a mock LinkHandler to avoid actual URL opening
        val mockLinkHandler = mock(LinkHandler::class.java)
        doReturn(mockLinkHandler).`when`(cashuHandler).javaClass.newInstance()
        
        // Test with a message containing a Cashu token
        val messageWithToken = "Here's a Cashu token: cashuABC123"
        val result = cashuHandler.processMessage(mockContext, messageWithToken, mockDbHelper)
        
        // Verify the result is true (token was found and processed)
        assertTrue(result)
    }
    
    @Test
    fun testCashuHandlerProcessMessageNoToken() {
        val cashuHandler = CashuHandler()
        
        // Test with a message not containing a Cashu token
        val messageWithoutToken = "This is a message without a Cashu token"
        val result = cashuHandler.processMessage(mockContext, messageWithoutToken, mockDbHelper)
        
        // Verify the result is false (no token was found)
        assertFalse(result)
    }
    
    @Test
    fun testMessageHandlerManagerProcessMessage() {
        // Create mock handlers
        val mockHandler1 = mock(MessageHandler::class.java)
        val mockHandler2 = mock(MessageHandler::class.java)
        
        // Set up the mocks to return true for isEnabled
        `when`(mockHandler1.isEnabled(mockDbHelper)).thenReturn(true)
        `when`(mockHandler2.isEnabled(mockDbHelper)).thenReturn(true)
        
        // Set up the mocks to return true and false for processMessage
        `when`(mockHandler1.processMessage(mockContext, "test message", mockDbHelper)).thenReturn(true)
        `when`(mockHandler2.processMessage(mockContext, "test message", mockDbHelper)).thenReturn(false)
        
        // Register the mock handlers
        MessageHandlerManager.registerHandler(mockHandler1)
        MessageHandlerManager.registerHandler(mockHandler2)
        
        // Process a message
        val result = MessageHandlerManager.processMessage(mockContext, "test message", mockDbHelper)
        
        // Verify the result is true (at least one handler processed the message)
        assertTrue(result)
        
        // Verify both handlers were called
        verify(mockHandler1).processMessage(mockContext, "test message", mockDbHelper)
        verify(mockHandler2).processMessage(mockContext, "test message", mockDbHelper)
    }
    
    @Test
    fun testMessageHandlerManagerProcessMessageAllDisabled() {
        // Create mock handlers
        val mockHandler1 = mock(MessageHandler::class.java)
        val mockHandler2 = mock(MessageHandler::class.java)
        
        // Set up the mocks to return false for isEnabled
        `when`(mockHandler1.isEnabled(mockDbHelper)).thenReturn(false)
        `when`(mockHandler2.isEnabled(mockDbHelper)).thenReturn(false)
        
        // Register the mock handlers
        MessageHandlerManager.registerHandler(mockHandler1)
        MessageHandlerManager.registerHandler(mockHandler2)
        
        // Process a message
        val result = MessageHandlerManager.processMessage(mockContext, "test message", mockDbHelper)
        
        // Verify the result is false (no handler processed the message)
        assertFalse(result)
        
        // Verify neither handler's processMessage was called
        verify(mockHandler1, never()).processMessage(any(), anyString(), any())
        verify(mockHandler2, never()).processMessage(any(), anyString(), any())
    }
    
    @Test
    fun testMessageHandlerManagerProcessMessageException() {
        // Create mock handlers
        val mockHandler1 = mock(MessageHandler::class.java)
        val mockHandler2 = mock(MessageHandler::class.java)
        
        // Set up the mocks to return true for isEnabled
        `when`(mockHandler1.isEnabled(mockDbHelper)).thenReturn(true)
        `when`(mockHandler2.isEnabled(mockDbHelper)).thenReturn(true)
        
        // Set up the first mock to throw an exception
        `when`(mockHandler1.processMessage(mockContext, "test message", mockDbHelper))
            .thenThrow(RuntimeException("Test exception"))
        
        // Set up the second mock to return true for processMessage
        `when`(mockHandler2.processMessage(mockContext, "test message", mockDbHelper)).thenReturn(true)
        
        // Register the mock handlers
        MessageHandlerManager.registerHandler(mockHandler1)
        MessageHandlerManager.registerHandler(mockHandler2)
        
        // Process a message
        val result = MessageHandlerManager.processMessage(mockContext, "test message", mockDbHelper)
        
        // Verify the result is true (the second handler processed the message)
        assertTrue(result)
        
        // Verify both handlers were called
        verify(mockHandler1).processMessage(mockContext, "test message", mockDbHelper)
        verify(mockHandler2).processMessage(mockContext, "test message", mockDbHelper)
    }
} 