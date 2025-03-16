package com.example.nfcdemo.nfc

import android.util.Log
import org.json.JSONObject
import java.nio.charset.Charset
import java.util.UUID

/**
 * Constants and utilities for the NFC protocol
 */
object NfcProtocol {
    private const val TAG = "NfcProtocol"
    
    // ISO-DEP command HEADER for selecting an AID
    val SELECT_APDU_HEADER = byteArrayOf(
        0x00.toByte(), // CLA (class of instruction)
        0xA4.toByte(), // INS (instruction code)
        0x04.toByte(), // P1  (parameter 1)
        0x00.toByte()  // P2  (parameter 2)
    )
    
    // "OK" status word sent in response to SELECT AID command (0x9000)
    val SELECT_OK_SW = byteArrayOf(0x90.toByte(), 0x00.toByte())
    
    // "UNKNOWN" status word sent in response to invalid APDU command (0x0000)
    val UNKNOWN_CMD_SW = byteArrayOf(0x00.toByte(), 0x00.toByte())
    
    // Default AID for our service
    const val DEFAULT_AID = "F0010203040506"
    
    // Commands
    const val CMD_GET_DATA = "GET_DATA"
    const val CMD_SEND_DATA = "SEND_DATA:"
    
    // Chunked transfer commands
    const val CMD_CHUNK_INIT = "CHUNK_INIT:"
    const val CMD_CHUNK_DATA = "CHUNK_DATA:"
    const val CMD_CHUNK_ACK = "CHUNK_ACK:"
    const val CMD_CHUNK_COMPLETE = "CHUNK_COMPLETE"
    
    /**
     * Convert a hex string to a byte array
     */
    fun hexStringToByteArray(hexString: String): ByteArray {
        val len = hexString.length
        val data = ByteArray(len / 2)
        for (i in 0 until len step 2) {
            data[i / 2] = ((Character.digit(hexString[i], 16) shl 4) + Character.digit(hexString[i + 1], 16)).toByte()
        }
        return data
    }
    
    /**
     * Convert a byte array to a hex string
     */
    fun byteArrayToHex(bytes: ByteArray): String {
        return bytes.joinToString("") { "%02X".format(it) }
    }
    
    /**
     * Build a SELECT APDU command
     */
    fun buildSelectApdu(aid: String): ByteArray {
        val aidBytes = hexStringToByteArray(aid)
        val result = ByteArray(6 + aidBytes.size)
        
        result[0] = 0x00.toByte() // CLA
        result[1] = 0xA4.toByte() // INS
        result[2] = 0x04.toByte() // P1
        result[3] = 0x00.toByte() // P2
        result[4] = aidBytes.size.toByte() // Lc
        System.arraycopy(aidBytes, 0, result, 5, aidBytes.size)
        result[5 + aidBytes.size] = 0x00.toByte() // Le
        
        return result
    }
    
    /**
     * Check if a response indicates success (ends with 9000)
     */
    fun isSuccess(response: ByteArray): Boolean {
        return response.size >= 2 && 
               response[response.size - 2] == 0x90.toByte() && 
               response[response.size - 1] == 0x00.toByte()
    }
    
    /**
     * Create a command to send data
     */
    fun createSendDataCommand(message: String): ByteArray {
        return "$CMD_SEND_DATA$message".toByteArray(Charset.forName("UTF-8"))
    }
    
    /**
     * Create a command to get data
     */
    fun createGetDataCommand(): ByteArray {
        return CMD_GET_DATA.toByteArray(Charset.forName("UTF-8"))
    }
    
    /**
     * Create a command to initialize chunked transfer
     */
    fun createChunkInitCommand(totalLength: Int, chunkSize: Int, totalChunks: Int): ByteArray {
        return "$CMD_CHUNK_INIT$totalLength:$chunkSize:$totalChunks".toByteArray(Charset.forName("UTF-8"))
    }
    
    /**
     * Create a command to send a chunk
     */
    fun createChunkDataCommand(chunkIndex: Int, chunkData: String): ByteArray {
        return "$CMD_CHUNK_DATA$chunkIndex:$chunkData".toByteArray(Charset.forName("UTF-8"))
    }
    
    /**
     * Create a command to acknowledge a chunk
     */
    fun createChunkAckCommand(chunkIndex: Int): ByteArray {
        return "$CMD_CHUNK_ACK$chunkIndex".toByteArray(Charset.forName("UTF-8"))
    }
    
    /**
     * Create a command to complete chunked transfer
     */
    fun createChunkCompleteCommand(): ByteArray {
        return CMD_CHUNK_COMPLETE.toByteArray(Charset.forName("UTF-8"))
    }
    
    /**
     * Parse a chunk acknowledgment response
     * @return The acknowledged chunk index, or -1 if parsing fails
     */
    fun parseChunkAck(response: ByteArray): Int {
        try {
            val responseData = response.copyOfRange(0, response.size - 2)
            val responseStr = String(responseData, Charset.forName("UTF-8"))
            
            if (responseStr.startsWith(CMD_CHUNK_ACK)) {
                return responseStr.substring(CMD_CHUNK_ACK.length).toInt()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing chunk ack: ${e.message}")
        }
        return -1
    }
    
    /**
     * Parse a chunk init command
     * @return Triple of (totalLength, chunkSize, totalChunks), or null if parsing fails
     */
    fun parseChunkInit(command: String): Triple<Int, Int, Int>? {
        try {
            if (command.startsWith(CMD_CHUNK_INIT)) {
                val parts = command.substring(CMD_CHUNK_INIT.length).split(":")
                if (parts.size == 3) {
                    val totalLength = parts[0].toInt()
                    val chunkSize = parts[1].toInt()
                    val totalChunks = parts[2].toInt()
                    return Triple(totalLength, chunkSize, totalChunks)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing chunk init: ${e.message}")
        }
        return null
    }
    
    /**
     * Parse a chunk data command
     * @return Pair of (chunkIndex, chunkData), or null if parsing fails
     */
    fun parseChunkData(command: String): Pair<Int, String>? {
        try {
            if (command.startsWith(CMD_CHUNK_DATA)) {
                val firstColonIndex = command.indexOf(':', CMD_CHUNK_DATA.length)
                if (firstColonIndex != -1) {
                    val chunkIndex = command.substring(CMD_CHUNK_DATA.length, firstColonIndex).toInt()
                    val chunkData = command.substring(firstColonIndex + 1)
                    return Pair(chunkIndex, chunkData)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing chunk data: ${e.message}")
        }
        return null
    }
    
    /**
     * Parse a send data command
     * @return The data to send, or null if parsing fails
     */
    fun parseSendData(command: String): String? {
        try {
            if (command.startsWith(CMD_SEND_DATA)) {
                return command.substring(CMD_SEND_DATA.length)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing send data: ${e.message}")
        }
        return null
    }
}

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