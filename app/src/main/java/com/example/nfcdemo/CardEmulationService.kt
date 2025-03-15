package com.example.nfcdemo

import android.nfc.cardemulation.HostApduService
import android.os.Bundle
import android.util.Log
import java.nio.charset.Charset

class CardEmulationService : HostApduService() {
    
    companion object {
        private const val TAG = "CardEmulationService"
        
        // ISO-DEP command HEADER for selecting an AID
        private val SELECT_APDU_HEADER = byteArrayOf(
            0x00.toByte(), // CLA (class of instruction)
            0xA4.toByte(), // INS (instruction code)
            0x04.toByte(), // P1  (parameter 1)
            0x00.toByte()  // P2  (parameter 2)
        )
        
        // "OK" status word sent in response to SELECT AID command (0x9000)
        private val SELECT_OK_SW = byteArrayOf(0x90.toByte(), 0x00.toByte())
        
        // "UNKNOWN" status word sent in response to invalid APDU command (0x0000)
        private val UNKNOWN_CMD_SW = byteArrayOf(0x00.toByte(), 0x00.toByte())
        
        // AID for our service
        private val AID = "F0010203040506".hexStringToByteArray()
        
        // Commands
        private const val CMD_GET_DATA = "GET_DATA"
        private const val CMD_SEND_DATA = "SEND_DATA:"
        
        // Chunked transfer commands
        private const val CMD_CHUNK_INIT = "CHUNK_INIT:"
        private const val CMD_CHUNK_DATA = "CHUNK_DATA:"
        private const val CMD_CHUNK_ACK = "CHUNK_ACK:"
        private const val CMD_CHUNK_COMPLETE = "CHUNK_COMPLETE"
        
        // Convert a hex string to a byte array
        private fun String.hexStringToByteArray(): ByteArray {
            val len = this.length
            val data = ByteArray(len / 2)
            for (i in 0 until len step 2) {
                data[i / 2] = ((Character.digit(this[i], 16) shl 4) + Character.digit(this[i + 1], 16)).toByte()
            }
            return data
        }
        
        // Static instance of the service for communication
        var instance: CardEmulationService? = null
    }
    
    // Message to be shared when requested
    var messageToShare: String = ""
    
    // Callback to notify MainActivity when data is received
    var onDataReceivedListener: ((String) -> Unit)? = null
    
    // Chunked message transfer state
    private var isReceivingChunkedMessage = false
    private var totalChunks = 0
    private var receivedChunks = 0
    private var chunkedMessageBuilder = StringBuilder()
    private var chunkSize = 0
    
    // Callback to notify MainActivity about chunked transfer progress
    var onChunkProgressListener: ((Int, Int) -> Unit)? = null
    
    override fun onCreate() {
        super.onCreate()
        instance = this
        Log.d(TAG, "CardEmulationService created")
    }
    
    override fun onDestroy() {
        super.onDestroy()
        instance = null
        Log.d(TAG, "CardEmulationService destroyed")
    }
    
    override fun processCommandApdu(commandApdu: ByteArray, extras: Bundle?): ByteArray {
        Log.d(TAG, "Received APDU: ${commandApdu.toHex()}")
        
        // Check if this is a SELECT AID command
        if (isSelectAidCommand(commandApdu)) {
            Log.d(TAG, "SELECT AID command received")
            return SELECT_OK_SW
        }
        
        // Convert command to string
        val commandString = String(commandApdu, Charset.forName("UTF-8"))
        Log.d(TAG, "Command string: $commandString")
        
        // Handle GET_DATA command
        if (commandString == CMD_GET_DATA) {
            Log.d(TAG, "GET_DATA command received, sending: $messageToShare")
            val dataBytes = messageToShare.toByteArray(Charset.forName("UTF-8"))
            return concatArrays(dataBytes, SELECT_OK_SW)
        }
        
        // Handle SEND_DATA command
        if (commandString.startsWith(CMD_SEND_DATA)) {
            val receivedData = commandString.substring(CMD_SEND_DATA.length)
            Log.d(TAG, "SEND_DATA command received with data: $receivedData")
            
            // Notify MainActivity about received data
            onDataReceivedListener?.invoke(receivedData)
            
            return SELECT_OK_SW
        }
        
        // Handle CHUNK_INIT command
        if (commandString.startsWith(CMD_CHUNK_INIT)) {
            return handleChunkInit(commandString)
        }
        
        // Handle CHUNK_DATA command
        if (commandString.startsWith(CMD_CHUNK_DATA)) {
            return handleChunkData(commandString)
        }
        
        // Handle CHUNK_COMPLETE command
        if (commandString == CMD_CHUNK_COMPLETE) {
            return handleChunkComplete()
        }
        
        // Unknown command
        return UNKNOWN_CMD_SW
    }
    
    private fun handleChunkInit(commandString: String): ByteArray {
        try {
            // Parse the chunk initialization parameters
            // Format: CHUNK_INIT:totalLength:chunkSize:totalChunks
            val params = commandString.substring(CMD_CHUNK_INIT.length).split(":")
            if (params.size != 3) {
                Log.e(TAG, "Invalid chunk init parameters: $commandString")
                return UNKNOWN_CMD_SW
            }
            
            val totalLength = params[0].toInt()
            chunkSize = params[1].toInt()
            totalChunks = params[2].toInt()
            
            Log.d(TAG, "Starting chunked transfer: totalLength=$totalLength, chunkSize=$chunkSize, totalChunks=$totalChunks")
            
            // Reset chunked message state
            isReceivingChunkedMessage = true
            receivedChunks = 0
            chunkedMessageBuilder = StringBuilder(totalLength)
            
            // Notify MainActivity about chunked transfer start
            onChunkProgressListener?.invoke(0, totalChunks)
            
            return SELECT_OK_SW
        } catch (e: Exception) {
            Log.e(TAG, "Error processing chunk init: ${e.message}")
            isReceivingChunkedMessage = false
            return UNKNOWN_CMD_SW
        }
    }
    
    private fun handleChunkData(commandString: String): ByteArray {
        if (!isReceivingChunkedMessage) {
            Log.e(TAG, "Received chunk data but not in chunked transfer mode")
            return UNKNOWN_CMD_SW
        }
        
        try {
            // Parse the chunk data
            // Format: CHUNK_DATA:chunkIndex:chunkData
            val firstColonIndex = commandString.indexOf(':', CMD_CHUNK_DATA.length)
            if (firstColonIndex == -1) {
                Log.e(TAG, "Invalid chunk data format: $commandString")
                return UNKNOWN_CMD_SW
            }
            
            val chunkIndexStr = commandString.substring(CMD_CHUNK_DATA.length, firstColonIndex)
            val chunkIndex = chunkIndexStr.toInt()
            val chunkData = commandString.substring(firstColonIndex + 1)
            
            Log.d(TAG, "Received chunk $chunkIndex: ${chunkData.take(20)}${if (chunkData.length > 20) "..." else ""}")
            
            // Append the chunk data to the message builder
            chunkedMessageBuilder.append(chunkData)
            receivedChunks++
            
            // Notify MainActivity about chunk progress
            onChunkProgressListener?.invoke(receivedChunks, totalChunks)
            
            // Send acknowledgment with the chunk index
            val ackMessage = "$CMD_CHUNK_ACK$chunkIndex".toByteArray(Charset.forName("UTF-8"))
            return concatArrays(ackMessage, SELECT_OK_SW)
        } catch (e: Exception) {
            Log.e(TAG, "Error processing chunk data: ${e.message}")
            return UNKNOWN_CMD_SW
        }
    }
    
    private fun handleChunkComplete(): ByteArray {
        if (!isReceivingChunkedMessage) {
            Log.e(TAG, "Received chunk complete but not in chunked transfer mode")
            return UNKNOWN_CMD_SW
        }
        
        try {
            // Check if we received all chunks
            if (receivedChunks != totalChunks) {
                Log.e(TAG, "Chunked transfer incomplete: received $receivedChunks of $totalChunks chunks")
                isReceivingChunkedMessage = false
                return UNKNOWN_CMD_SW
            }
            
            // Get the complete message
            val completeMessage = chunkedMessageBuilder.toString()
            Log.d(TAG, "Chunked transfer complete, message length: ${completeMessage.length}")
            
            // Reset chunked message state
            isReceivingChunkedMessage = false
            
            // Notify MainActivity about received data
            onDataReceivedListener?.invoke(completeMessage)
            
            return SELECT_OK_SW
        } catch (e: Exception) {
            Log.e(TAG, "Error completing chunked transfer: ${e.message}")
            isReceivingChunkedMessage = false
            return UNKNOWN_CMD_SW
        }
    }
    
    override fun onDeactivated(reason: Int) {
        Log.d(TAG, "Deactivated: $reason")
        
        // If we were in the middle of a chunked transfer, reset the state
        if (isReceivingChunkedMessage) {
            Log.d(TAG, "Chunked transfer interrupted")
            isReceivingChunkedMessage = false
        }
    }
    
    private fun isSelectAidCommand(command: ByteArray): Boolean {
        return command.size >= SELECT_APDU_HEADER.size + 1 + AID.size + 1 && 
               command.sliceArray(0 until SELECT_APDU_HEADER.size).contentEquals(SELECT_APDU_HEADER) &&
               command[SELECT_APDU_HEADER.size].toInt() == AID.size &&
               command.sliceArray(SELECT_APDU_HEADER.size + 1 until SELECT_APDU_HEADER.size + 1 + AID.size).contentEquals(AID)
    }
    
    private fun concatArrays(array1: ByteArray, array2: ByteArray): ByteArray {
        val result = ByteArray(array1.size + array2.size)
        System.arraycopy(array1, 0, result, 0, array1.size)
        System.arraycopy(array2, 0, result, array1.size, array2.size)
        return result
    }
    
    private fun ByteArray.toHex(): String {
        return joinToString("") { "%02X".format(it) }
    }
} 