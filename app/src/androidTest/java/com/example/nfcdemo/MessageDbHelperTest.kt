package com.example.nfcdemo

import android.content.Context
import android.provider.BaseColumns
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.nfcdemo.data.MessageContract
import com.example.nfcdemo.data.MessageDbHelper
import com.example.nfcdemo.data.SettingsContract
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MessageDbHelperTest {

    private lateinit var dbHelper: MessageDbHelper
    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        // Use an in-memory database for testing
        dbHelper = MessageDbHelper(context, null)
    }

    @After
    fun tearDown() {
        dbHelper.close()
    }

    @Test
    fun testAddAndGetMessage() {
        // Add a test message
        val content = "Test message"
        val isSent = true
        val isDelivered = false
        
        val id = dbHelper.addMessage(content, isSent, isDelivered)
        assertTrue(id > 0) // Verify insertion was successful
        
        // Retrieve the message
        val cursor = dbHelper.getMessages(1)
        assertTrue(cursor.moveToFirst())
        
        // Verify the message content
        val contentIndex = cursor.getColumnIndex(MessageContract.MessageEntry.COLUMN_CONTENT)
        val isSentIndex = cursor.getColumnIndex(MessageContract.MessageEntry.COLUMN_IS_SENT)
        val isDeliveredIndex = cursor.getColumnIndex(MessageContract.MessageEntry.COLUMN_IS_DELIVERED)
        
        assertEquals(content, cursor.getString(contentIndex))
        assertEquals(if (isSent) 1 else 0, cursor.getInt(isSentIndex))
        assertEquals(if (isDelivered) 1 else 0, cursor.getInt(isDeliveredIndex))
        
        cursor.close()
    }
    
    @Test
    fun testUpdateMessageDeliveryStatus() {
        // Add a test message
        val content = "Test message for delivery update"
        val isSent = true
        val isDelivered = false
        
        val id = dbHelper.addMessage(content, isSent, isDelivered)
        assertTrue(id > 0)
        
        // Update delivery status
        val rowsUpdated = dbHelper.updateMessageDeliveryStatus(id, true)
        assertEquals(1, rowsUpdated)
        
        // Verify the update
        val cursor = dbHelper.getMessages(1)
        assertTrue(cursor.moveToFirst())
        
        val isDeliveredIndex = cursor.getColumnIndex(MessageContract.MessageEntry.COLUMN_IS_DELIVERED)
        assertEquals(1, cursor.getInt(isDeliveredIndex))
        
        cursor.close()
    }
    
    @Test
    fun testDeleteMessage() {
        // Add a test message
        val content = "Test message to delete"
        val id = dbHelper.addMessage(content, true, false)
        assertTrue(id > 0)
        
        // Delete the message
        val rowsDeleted = dbHelper.deleteMessage(id)
        assertEquals(1, rowsDeleted)
        
        // Verify the message is deleted
        val cursor = dbHelper.getMessages(10)
        var found = false
        while (cursor.moveToNext()) {
            val idIndex = cursor.getColumnIndex(BaseColumns._ID)
            if (cursor.getLong(idIndex) == id) {
                found = true
                break
            }
        }
        assertFalse(found)
        cursor.close()
    }
    
    @Test
    fun testClearAllMessages() {
        // Add multiple test messages
        dbHelper.addMessage("Message 1", true, false)
        dbHelper.addMessage("Message 2", false, false)
        dbHelper.addMessage("Message 3", true, true)
        
        // Clear all messages
        val rowsDeleted = dbHelper.clearAllMessages()
        assertTrue(rowsDeleted > 0)
        
        // Verify all messages are deleted
        val cursor = dbHelper.getMessages(10)
        assertEquals(0, cursor.count)
        cursor.close()
    }
    
    @Test
    fun testSaveAndGetSetting() {
        // Save a setting
        val key = "test_key"
        val value = "test_value"
        
        dbHelper.saveSetting(key, value)
        
        // Get the setting
        val retrievedValue = dbHelper.getSetting(key, "default")
        assertEquals(value, retrievedValue)
    }
    
    @Test
    fun testGetDefaultSetting() {
        // Get a non-existent setting
        val key = "non_existent_key"
        val defaultValue = "default_value"
        
        val retrievedValue = dbHelper.getSetting(key, defaultValue)
        assertEquals(defaultValue, retrievedValue)
    }
    
    @Test
    fun testSaveAndGetBooleanSetting() {
        // Save a boolean setting
        val key = SettingsContract.SettingsEntry.KEY_AUTO_OPEN_LINKS
        val value = true
        
        dbHelper.saveSetting(key, value.toString())
        
        // Get the boolean setting
        val retrievedValue = dbHelper.getBooleanSetting(key, false)
        assertTrue(retrievedValue)
    }
    
    @Test
    fun testGetDefaultBooleanSetting() {
        // Get a non-existent boolean setting
        val key = "non_existent_boolean_key"
        val defaultValue = true
        
        val retrievedValue = dbHelper.getBooleanSetting(key, defaultValue)
        assertTrue(retrievedValue)
    }
} 