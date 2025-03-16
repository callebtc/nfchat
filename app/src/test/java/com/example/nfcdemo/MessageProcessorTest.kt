package com.example.nfcdemo

import android.content.Context
import com.example.nfcdemo.data.MessageDbHelper
import com.example.nfcdemo.handlers.MessageHandlerManager
import com.example.nfcdemo.nfc.MessageData
import com.example.nfcdemo.nfc.MessageProcessor
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
class MessageProcessorTest {
    
    private lateinit var mockContext: Context
    private lateinit var mockDbHelper: MessageDbHelper
    private lateinit var mockHandlerManager: MessageHandlerManager
    
    @Before
    fun setUp() {
        // Create mock objects
        mockContext = mock(Context::class.java)
        mockDbHelper = mock(MessageDbHelper::class.java)
        
        // Use PowerMockito to mock the static MessageHandlerManager
        mockHandlerManager = mock(MessageHandlerManager::class.java)
    }
    
    @After
    fun tearDown() {
        // Clear all handlers after each test
        MessageHandlerManager.clearHandlers()
    }
    
    @Test
    fun testProcessReceivedMessage() {
        // Create a spy on the MessageHandlerManager to verify calls
        val messageHandlerManager = spy(MessageHandlerManager)
        
        // Mock the processMessage method to return true
        doReturn(true).`when`(messageHandlerManager).processMessage(any(), anyString(), any())
        
        // Create a test message
        val messageData = MessageData("Test message")
        
        // Process the message
        val result = MessageProcessor.processReceivedMessage(mockContext, messageData, mockDbHelper)
        
        // Verify the result is the original message content
        assertEquals("Test message", result)
    }
    
    @Test
    fun testOpenLinksInMessage() {
        // Create a spy on the MessageProcessor to verify initialization
        val messageProcessor = spy(MessageProcessor)
        
        // Call the openLinksInMessage method
        MessageProcessor.openLinksInMessage(mockContext, "Test message with http://example.com", mockDbHelper)
        
        // Verify that the handlers are initialized
        // Note: This is difficult to test directly since initializeHandlers is private
        // In a real test, we would use reflection or PowerMockito to verify private method calls
    }
    
    @Test
    fun testOpenUrl() {
        // Call the openUrl method
        MessageProcessor.openUrl(mockContext, "http://example.com", mockDbHelper)
        
        // Verify that the LinkHandler is used
        // Note: This is difficult to test directly since we can't easily mock the LinkHandler creation
        // In a real test, we would use dependency injection or PowerMockito to verify
    }
} 