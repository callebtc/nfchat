package com.example.nfcdemo

import android.util.Log
import org.json.JSONObject
import java.util.UUID

/**
 * Data class to encapsulate message content and ID for better duplicate detection
 */
data class MessageData(
    val content: String,
    val id: String = UUID.randomUUID().toString()
) {
    /**
     * Convert the message data to a JSON string
     */
    fun toJson(): String {
        val json = JSONObject()
        json.put("content", content)
        json.put("id", id)
        return json.toString()
    }
    
    companion object {
        /**
         * Create a MessageData object from a JSON string
         * @return MessageData object or null if parsing fails
         */
        fun fromJson(jsonString: String): MessageData? {
            return try {
                val json = JSONObject(jsonString)
                val content = json.getString("content")
                val id = json.getString("id")
                MessageData(content, id)
            } catch (e: Exception) {
                Log.e("MessageData", "Error parsing JSON: $e")
                null
            }
        }
        
        /**
         * Check if a string is a valid JSON message
         */
        fun isValidJson(jsonString: String): Boolean {
            return try {
                val json = JSONObject(jsonString)
                json.has("content") && json.has("id")
            } catch (e: Exception) {
                false
            }
        }
    }
} 