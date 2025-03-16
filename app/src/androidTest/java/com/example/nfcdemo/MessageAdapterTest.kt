package com.example.nfcdemo

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MessageAdapterTest {

    private lateinit var messageAdapter: MessageAdapter
    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        messageAdapter = MessageAdapter(context)
    }

    @Test
    fun testAddSentMessage() {
        val initialCount = messageAdapter.itemCount
        val message = "Test sent message"
        
        val position = messageAdapter.addSentMessage(message)
        
        // Verify the message was added
        assertEquals(initialCount + 1, messageAdapter.itemCount)
        assertTrue(position >= 0)
        
        // Verify the message content and type
        val item = messageAdapter.getItem(position)
        assertEquals(message, item.content)
        assertTrue(item.isSent)
        assertFalse(item.isDelivered)
    }
    
    @Test
    fun testAddReceivedMessage() {
        val initialCount = messageAdapter.itemCount
        val message = "Test received message"
        
        val position = messageAdapter.addReceivedMessage(message)
        
        // Verify the message was added
        assertEquals(initialCount + 1, messageAdapter.itemCount)
        assertTrue(position >= 0)
        
        // Verify the message content and type
        val item = messageAdapter.getItem(position)
        assertEquals(message, item.content)
        assertFalse(item.isSent)
        assertTrue(item.isDelivered) // Received messages are always marked as delivered
    }
    
    @Test
    fun testMarkMessageAsDelivered() {
        // Add a sent message
        val message = "Test message for delivery"
        val position = messageAdapter.addSentMessage(message)
        
        // Verify it's not delivered initially
        var item = messageAdapter.getItem(position)
        assertFalse(item.isDelivered)
        
        // Mark as delivered
        messageAdapter.markMessageAsDelivered(position)
        
        // Verify it's now marked as delivered
        item = messageAdapter.getItem(position)
        assertTrue(item.isDelivered)
    }
    
    @Test
    fun testClearMessages() {
        // Add some messages
        messageAdapter.addSentMessage("Sent message 1")
        messageAdapter.addReceivedMessage("Received message 1")
        messageAdapter.addSentMessage("Sent message 2")
        
        // Verify messages were added
        assertTrue(messageAdapter.itemCount > 0)
        
        // Clear messages
        messageAdapter.clearMessages()
        
        // Verify all messages were cleared
        assertEquals(0, messageAdapter.itemCount)
    }
    
    @Test
    fun testSetMessageLengthLimit() {
        // Add a long message
        val longMessage = "This is a very long message that should be truncated when the message length limit is set to a small value."
        val position = messageAdapter.addSentMessage(longMessage)
        
        // Set a small message length limit
        val limit = 20
        messageAdapter.setMessageLengthLimit(limit)
        
        // Verify the message is truncated in the display text
        val item = messageAdapter.getItem(position)
        val displayText = messageAdapter.getDisplayText(item)
        
        // The display text should be truncated with "..." at the end
        assertTrue(displayText.length <= limit + 3) // +3 for "..."
        assertTrue(displayText.endsWith("..."))
        
        // But the original content should still be intact
        assertEquals(longMessage, item.content)
    }
    
    @Test
    fun testGetItemViewType() {
        // Add a sent message
        val sentPosition = messageAdapter.addSentMessage("Sent message")
        
        // Add a received message
        val receivedPosition = messageAdapter.addReceivedMessage("Received message")
        
        // Verify the view types
        assertEquals(MessageAdapter.VIEW_TYPE_SENT, messageAdapter.getItemViewType(sentPosition))
        assertEquals(MessageAdapter.VIEW_TYPE_RECEIVED, messageAdapter.getItemViewType(receivedPosition))
    }
} 