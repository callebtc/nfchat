package com.example.nfcdemo

import org.json.JSONException
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.UUID

// We need to use Robolectric to handle Android dependencies like Log
@RunWith(RobolectricTestRunner::class)
class MessageDataTest {
    
    @Test
    fun testMessageDataCreation() {
        val content = "Test message"
        val messageData = MessageData(content)
        
        assertEquals(content, messageData.content)
        assertNotNull(messageData.id)
    }
    
    @Test
    fun testToJson() {
        val content = "Test message"
        val id = "test-id-123"
        val messageData = MessageData(content, id)
        
        val json = messageData.toJson()
        val jsonObject = JSONObject(json)
        
        assertEquals(content, jsonObject.getString("content"))
        assertEquals(id, jsonObject.getString("id"))
    }
    
    @Test
    fun testFromJson() {
        val content = "Test message"
        val id = "test-id-123"
        val jsonString = "{\"content\":\"$content\",\"id\":\"$id\"}"
        
        val messageData = MessageData.fromJson(jsonString)
        assertNotNull(messageData)
        assertEquals(content, messageData?.content)
        assertEquals(id, messageData?.id)
    }
    
    @Test
    fun testFromInvalidJson() {
        val invalidJson = "{\"wrong_field\":\"value\"}"
        val messageData = MessageData.fromJson(invalidJson)
        assertNull(messageData)
    }
    
    @Test
    fun testIsValidJson() {
        val validJson = "{\"content\":\"Test message\",\"id\":\"test-id-123\"}"
        val invalidJson1 = "{\"wrong_field\":\"value\"}"
        val invalidJson2 = "Not a JSON string"
        
        assertTrue(MessageData.isValidJson(validJson))
        assertFalse(MessageData.isValidJson(invalidJson1))
        assertFalse(MessageData.isValidJson(invalidJson2))
    }
} 