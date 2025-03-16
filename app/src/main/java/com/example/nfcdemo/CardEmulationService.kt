package com.example.nfcdemo

import android.app.Service
import android.content.Intent
import android.nfc.cardemulation.HostApduService
import android.os.Binder
import android.os.Bundle
import android.os.IBinder
import android.util.Log
import com.example.nfcdemo.data.AppConstants
import com.example.nfcdemo.nfc.MessageData
import com.example.nfcdemo.nfc.NfcProtocol
import java.nio.charset.Charset
import org.json.JSONObject

class CardEmulationService : HostApduService() {
    
    companion object {
        private const val TAG = "CardEmulationService"
        
        // AID for our service
        private val AID = NfcProtocol.hexStringToByteArray(NfcProtocol.DEFAULT_AID)
        
        // Static instance of the service for communication
        var instance: CardEmulationService? = null
    }
    
    // Message to be shared when requested
    var messageToShare: String = ""
    
    // Callback to notify MainActivity when data is received
    var onDataReceivedListener: ((MessageData) -> Unit)? = null
    
    // Chunked message transfer state
    private var isReceivingChunkedMessage = false
    private var totalChunks = 0
    private var receivedChunks = 0
    private var chunkedMessageBuilder = StringBuilder()
    private var chunkSize = 0
    
    // Callback to notify MainActivity about chunked transfer progress
    var onChunkProgressListener: ((Int, Int) -> Unit)? = null
    
    // Callback to notify MainActivity about chunked transfer errors
    var onChunkErrorListener: ((String) -> Unit)? = null
    
    /**
     * Check if currently receiving a chunked message
     */
    fun isReceivingChunkedMessage(): Boolean {
        return isReceivingChunkedMessage
    }
    
    /**
     * Reset the chunked message state
     */
    fun resetChunkedMessageState() {
        if (isReceivingChunkedMessage) {
            Log.d(TAG, "Resetting chunked message state")
            isReceivingChunkedMessage = false
            receivedChunks = 0
            totalChunks = 0
            chunkedMessageBuilder = StringBuilder()
            chunkSize = 0
        }
    }
    
    override fun onCreate() {
        super.onCreate()
        instance = this
        Log.d(TAG, "CardEmulationService created")
    }
    
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "CardEmulationService started with startId: $startId")
        // If the service is killed, restart it
        return Service.START_STICKY
    }
    
    override fun onDestroy() {
        super.onDestroy()
        instance = null
        Log.d(TAG, "CardEmulationService destroyed")
    }
    
    override fun onDeactivated(reason: Int) {
        Log.d(TAG, "Deactivated: $reason")
        
        // If we were in the middle of a chunked transfer, don't reset the state immediately
        // This allows the transfer to continue if the connection is restored
        if (isReceivingChunkedMessage) {
            Log.d(TAG, "Chunked transfer interrupted, but keeping state for possible reconnection")
            
            // Notify MainActivity about the interruption, but don't reset state
            // The MainActivity will handle the timeout and reset if needed
            onChunkProgressListener?.invoke(receivedChunks, totalChunks)
        }
    }
    
    override fun processCommandApdu(commandApdu: ByteArray, extras: Bundle?): ByteArray {
        Log.d(TAG, "Received APDU: ${commandApdu.toHex()}")
        
        // Check if this is a SELECT AID command
        if (isSelectAidCommand(commandApdu)) {
            Log.d(TAG, "Received SELECT AID command")
            return NfcProtocol.SELECT_OK_SW
        }
        
        // Convert the command to a string
        val commandString = String(commandApdu, Charset.forName("UTF-8"))
        Log.d(TAG, "Command string: $commandString")
        
        // Handle different commands
        return when {
            // GET_DATA command - send the current message
            commandString == NfcProtocol.CMD_GET_DATA -> {
                handleGetDataCommand()
            }
            
            // SEND_DATA command - receive a message
            commandString.startsWith(NfcProtocol.CMD_SEND_DATA) -> {
                handleSendDataCommand(commandString)
            }
            
            // CHUNK_INIT command - initialize chunked message transfer
            commandString.startsWith(NfcProtocol.CMD_CHUNK_INIT) -> {
                handleChunkInitCommand(commandString)
            }
            
            // CHUNK_DATA command - receive a chunk of data
            commandString.startsWith(NfcProtocol.CMD_CHUNK_DATA) -> {
                handleChunkDataCommand(commandString)
            }
            
            // CHUNK_COMPLETE command - complete chunked message transfer
            commandString == NfcProtocol.CMD_CHUNK_COMPLETE -> {
                handleChunkCompleteCommand()
            }
            
            // Unknown command
            else -> {
                Log.d(TAG, "Unknown command: $commandString")
                NfcProtocol.UNKNOWN_CMD_SW
            }
        }
    }
    
    /**
     * Handle the GET_DATA command
     */
    private fun handleGetDataCommand(): ByteArray {
        Log.d(TAG, "Handling GET_DATA command")
        
        // Create a MessageData object with the message content
        val messageData = MessageData(messageToShare)
        val jsonMessage = messageData.toJson()
        
        // Combine the message with the status word
        val messageBytes = jsonMessage.toByteArray(Charset.forName("UTF-8"))
        val response = ByteArray(messageBytes.size + 2)
        System.arraycopy(messageBytes, 0, response, 0, messageBytes.size)
        System.arraycopy(NfcProtocol.SELECT_OK_SW, 0, response, messageBytes.size, 2)
        
        return response
    }
    
    /**
     * Handle the SEND_DATA command
     */
    private fun handleSendDataCommand(commandString: String): ByteArray {
        Log.d(TAG, "Handling SEND_DATA command")
        
        // Extract the data from the command
        val data = NfcProtocol.parseSendData(commandString)
        
        if (data != null) {
            // Parse the JSON message
            val messageData = MessageData.fromJson(data)
            
            if (messageData != null) {
                // Notify the listener
                onDataReceivedListener?.invoke(messageData)
            } else {
                Log.e(TAG, "Failed to parse message data: $data")
            }
        } else {
            Log.e(TAG, "Failed to parse SEND_DATA command: $commandString")
        }
        
        return NfcProtocol.SELECT_OK_SW
    }
    
    /**
     * Handle the CHUNK_INIT command
     */
    private fun handleChunkInitCommand(commandString: String): ByteArray {
        Log.d(TAG, "Handling CHUNK_INIT command")
        
        // Parse the chunk init command
        val chunkInit = NfcProtocol.parseChunkInit(commandString)
        
        if (chunkInit != null) {
            val (totalLength, chunkSize, totalChunks) = chunkInit
            
            // Initialize chunked message state
            isReceivingChunkedMessage = true
            this.totalChunks = totalChunks
            this.chunkSize = chunkSize
            receivedChunks = 0
            chunkedMessageBuilder = StringBuilder(totalLength)
            
            Log.d(TAG, "Initialized chunked message: totalLength=$totalLength, chunkSize=$chunkSize, totalChunks=$totalChunks")
            
            // Notify the listener
            onChunkProgressListener?.invoke(receivedChunks, totalChunks)
        } else {
            Log.e(TAG, "Failed to parse CHUNK_INIT command: $commandString")
            
            // Notify the listener about the error
            onChunkErrorListener?.invoke("Failed to parse CHUNK_INIT command")
        }
        
        return NfcProtocol.SELECT_OK_SW
    }
    
    /**
     * Handle the CHUNK_DATA command
     */
    private fun handleChunkDataCommand(commandString: String): ByteArray {
        Log.d(TAG, "Handling CHUNK_DATA command")
        
        if (!isReceivingChunkedMessage) {
            Log.e(TAG, "Received CHUNK_DATA but not in chunked mode")
            
            // Notify the listener about the error
            onChunkErrorListener?.invoke("Received CHUNK_DATA but not in chunked mode")
            
            return NfcProtocol.UNKNOWN_CMD_SW
        }
        
        // Parse the chunk data command
        val chunkData = NfcProtocol.parseChunkData(commandString)
        
        if (chunkData != null) {
            val (chunkIndex, data) = chunkData
            
            // Validate the chunk index
            if (chunkIndex < 0 || chunkIndex >= totalChunks) {
                Log.e(TAG, "Invalid chunk index: $chunkIndex")
                
                // Notify the listener about the error
                onChunkErrorListener?.invoke("Invalid chunk index: $chunkIndex")
                
                return NfcProtocol.UNKNOWN_CMD_SW
            }
            
            // Store the chunk data
            if (chunkedMessageBuilder.length < (chunkIndex + 1) * chunkSize) {
                // Ensure the StringBuilder has enough capacity
                while (chunkedMessageBuilder.length < chunkIndex * chunkSize) {
                    chunkedMessageBuilder.append(" ")
                }
                
                // Append the chunk data
                chunkedMessageBuilder.append(data)
                receivedChunks++
                
                Log.d(TAG, "Received chunk $chunkIndex: ${data.take(20)}... (${receivedChunks}/$totalChunks)")
                
                // Notify the listener
                onChunkProgressListener?.invoke(receivedChunks, totalChunks)
            } else {
                Log.d(TAG, "Chunk $chunkIndex already received")
            }
            
            // Send acknowledgment
            val ackBytes = NfcProtocol.createChunkAckCommand(chunkIndex)
            val response = ByteArray(ackBytes.size + 2)
            System.arraycopy(ackBytes, 0, response, 0, ackBytes.size)
            System.arraycopy(NfcProtocol.SELECT_OK_SW, 0, response, ackBytes.size, 2)
            
            return response
        } else {
            Log.e(TAG, "Failed to parse CHUNK_DATA command: $commandString")
            
            // Notify the listener about the error
            onChunkErrorListener?.invoke("Failed to parse CHUNK_DATA command")
            
            return NfcProtocol.UNKNOWN_CMD_SW
        }
    }
    
    /**
     * Handle the CHUNK_COMPLETE command
     */
    private fun handleChunkCompleteCommand(): ByteArray {
        Log.d(TAG, "Handling CHUNK_COMPLETE command")
        
        if (!isReceivingChunkedMessage) {
            Log.e(TAG, "Received CHUNK_COMPLETE but not in chunked mode")
            
            // Notify the listener about the error
            onChunkErrorListener?.invoke("Received CHUNK_COMPLETE but not in chunked mode")
            
            return NfcProtocol.UNKNOWN_CMD_SW
        }
        
        // Check if all chunks were received
        if (receivedChunks < totalChunks) {
            Log.e(TAG, "Not all chunks received: $receivedChunks/$totalChunks")
            
            // Notify the listener about the error
            onChunkErrorListener?.invoke("Not all chunks received: $receivedChunks/$totalChunks")
            
            return NfcProtocol.UNKNOWN_CMD_SW
        }
        
        // Process the complete message
        val completeMessage = chunkedMessageBuilder.toString()
        Log.d(TAG, "Completed chunked message: ${completeMessage.take(50)}...")
        
        // Parse the JSON message
        val messageData = MessageData.fromJson(completeMessage)
        
        if (messageData != null) {
            // Notify the listener
            onDataReceivedListener?.invoke(messageData)
        } else {
            Log.e(TAG, "Failed to parse message data: $completeMessage")
            
            // Notify the listener about the error
            onChunkErrorListener?.invoke("Failed to parse message data")
        }
        
        // Reset chunked message state
        resetChunkedMessageState()
        
        return NfcProtocol.SELECT_OK_SW
    }
    
    /**
     * Check if a command is a SELECT AID command
     */
    private fun isSelectAidCommand(command: ByteArray): Boolean {
        return command.size >= 6 &&
               command[0] == NfcProtocol.SELECT_APDU_HEADER[0] &&
               command[1] == NfcProtocol.SELECT_APDU_HEADER[1] &&
               command[2] == NfcProtocol.SELECT_APDU_HEADER[2] &&
               command[3] == NfcProtocol.SELECT_APDU_HEADER[3] &&
               command[4] == AID.size.toByte() &&
               command.sliceArray(5 until 5 + AID.size).contentEquals(AID)
    }
    
    /**
     * Convert a byte array to a hex string
     */
    private fun ByteArray.toHex(): String {
        return NfcProtocol.byteArrayToHex(this)
    }
} 