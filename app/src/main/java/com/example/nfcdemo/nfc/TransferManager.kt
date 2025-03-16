package com.example.nfcdemo.nfc

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.nfc.TagLostException
import android.nfc.tech.IsoDep
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Toast
import com.example.nfcdemo.AppState
import com.example.nfcdemo.CardEmulationService
import com.example.nfcdemo.R
import java.io.IOException
import java.nio.charset.Charset

/**
 * Manager class for handling NFC data transfer operations
 */
class TransferManager(private val context: Activity) {
    private val TAG = "TransferManager"
    
    // NFC adapter
    private var nfcAdapter: NfcAdapter? = null
    
    // State management
    private var appState = AppState.IDLE
    private var lastSentMessage = ""
    private var lastReceivedMessageId = "" // Track the ID of the last received message
    
    // Chunked transfer manager
    private val chunkwiseTransferManager = ChunkwiseTransferManager(context)
    
    // Handler for main thread operations
    private val mainHandler = Handler(Looper.getMainLooper())
    
    // Callbacks
    var onAppStateChanged: ((AppState) -> Unit)? = null
    var onStatusChanged: ((String) -> Unit)? = null
    var onMessageSent: ((Int) -> Unit)? = null
    var onMessageReceived: ((MessageData, Boolean) -> Unit)? = null
    var onVibrate: ((Long) -> Unit)? = null
    
    // Chunk transfer callbacks
    var onChunkTransferStarted: ((Int) -> Unit)? = null
    var onChunkTransferProgress: ((Int, Int) -> Unit)? = null
    var onChunkTransferCompleted: (() -> Unit)? = null
    
    init {
        // Initialize NFC adapter
        nfcAdapter = NfcAdapter.getDefaultAdapter(context)
        
        // Set up chunked transfer manager callbacks
        setupChunkwiseTransferCallbacks()
    }
    
    /**
     * Set up callbacks for the chunked transfer manager
     */
    private fun setupChunkwiseTransferCallbacks() {
        chunkwiseTransferManager.onTransferStatusChanged = { status ->
            onStatusChanged?.invoke(status)
        }
        
        chunkwiseTransferManager.onTransferCompleted = {
            context.runOnUiThread {
                // Notify that the message was sent
                onMessageSent?.invoke(-1) // -1 indicates the last message
                
                // Vibrate on message sent
                onVibrate?.invoke(200)
                
                // Clear the sent message to prevent re-sending
                lastSentMessage = ""
                
                // Switch to receive mode automatically
                switchToReceiveMode()
                
                // Notify that chunk transfer is completed
                onChunkTransferCompleted?.invoke()
            }
        }
        
        chunkwiseTransferManager.onTransferError = { errorMessage ->
            context.runOnUiThread {
                // Don't switch to receive mode on error - let the retry mechanism handle it
                // Don't hide the progress bar either, since we're waiting for reconnection
                
                // Show error message
                Toast.makeText(context, errorMessage, Toast.LENGTH_SHORT).show()
            }
        }
        
        chunkwiseTransferManager.onTransferRetryStarted = {
            context.runOnUiThread {
                // Update status to show we're waiting for reconnection
                onStatusChanged?.invoke(context.getString(R.string.waiting_for_reconnection))
            }
        }
        
        chunkwiseTransferManager.onTransferRetryTimeout = {
            context.runOnUiThread {
                // When retry timeout occurs, we should finally give up and switch to receive mode
                onStatusChanged?.invoke(context.getString(R.string.retry_timeout_occurred))
                
                // Hide the progress bar
                onChunkTransferCompleted?.invoke()
                
                // Switch to receive mode
                switchToReceiveMode()
            }
        }
        
        // Add callbacks for chunk transfer progress
        chunkwiseTransferManager.onChunkSendStarted = { totalChunks ->
            context.runOnUiThread {
                // Notify that chunk transfer has started
                onChunkTransferStarted?.invoke(totalChunks)
            }
        }
        
        chunkwiseTransferManager.onChunkSendProgress = { currentChunk, totalChunks ->
            context.runOnUiThread {
                // Notify about chunk transfer progress
                onChunkTransferProgress?.invoke(currentChunk, totalChunks)
            }
        }
        
        chunkwiseTransferManager.onChunkReceiveStarted = { totalChunks ->
            context.runOnUiThread {
                // Notify that chunk receive has started
                onChunkTransferStarted?.invoke(totalChunks)
            }
        }
        
        chunkwiseTransferManager.onChunkReceiveProgress = { currentChunk, totalChunks ->
            context.runOnUiThread {
                // Notify about chunk receive progress
                onChunkTransferProgress?.invoke(currentChunk, totalChunks)
            }
        }
    }
    
    /**
     * Get the current app state
     */
    fun getAppState(): AppState {
        return appState
    }
    
    /**
     * Set the last sent message
     */
    fun setLastSentMessage(message: String) {
        lastSentMessage = message
    }
    
    /**
     * Get the last sent message
     */
    fun getLastSentMessage(): String {
        return lastSentMessage
    }
    
    /**
     * Toggle between send and receive modes
     */
    fun toggleMode() {
        when (appState) {
            AppState.SENDING -> {
                // If in send mode, switch to receive mode
                switchToReceiveMode()
            }
            AppState.RECEIVING -> {
                // If in receive mode, try to switch to send mode if there's a pending message
                if (lastSentMessage.isNotEmpty()) {
                    switchToSendMode()
                } else {
                    // No pending message to send
                    Toast.makeText(context, context.getString(R.string.no_pending_message), Toast.LENGTH_SHORT).show()
                }
            }
            AppState.IDLE -> {
                // If in idle mode, switch to receive mode
                switchToReceiveMode()
            }
        }
    }
    
    /**
     * Switch to send mode and attempt to send the pending message
     */
    fun switchToSendMode() {
        // Only proceed if there's a message to send
        if (lastSentMessage.isEmpty()) {
            Log.d(TAG, "No message to send, not switching to send mode")
            return
        }
        
        // First, stop any receive mode operations
        if (appState == AppState.RECEIVING) {
            // Stop the CardEmulationService
            val intent = Intent(context, CardEmulationService::class.java)
            context.stopService(intent)
        }
        
        // Update state
        appState = AppState.SENDING
        onAppStateChanged?.invoke(appState)
        onStatusChanged?.invoke(context.getString(R.string.status_send_mode))
        
        // Enable reader mode for sending data
        enableReaderMode()
        
        Log.d(TAG, "Switched to send mode, ready to send: $lastSentMessage")
    }
    
    /**
     * Switch to receive mode
     */
    fun switchToReceiveMode() {
        // First, disable reader mode if we were in send mode
        if (appState == AppState.SENDING) {
            disableReaderMode()
        }
        
        // Update state
        appState = AppState.RECEIVING
        onAppStateChanged?.invoke(appState)
        onStatusChanged?.invoke(context.getString(R.string.status_receive_mode))
        
        // Start the CardEmulationService
        val intent = Intent(context, CardEmulationService::class.java)
        context.startService(intent)
        
        // Set up the message and listener
        mainHandler.postDelayed({
            setupDataReceiver()
        }, 100)
        
        Log.d(TAG, "Switched to receive mode")
    }
    
    /**
     * Set up data receiver for the CardEmulationService
     */
    fun setupDataReceiver() {
        // This is a critical function to ensure UI updates happen
        CardEmulationService.instance?.onDataReceivedListener = { messageData ->
            Log.d(TAG, "Data received in TransferManager: ${messageData.content}")
            
            // Check if this is a duplicate message based on ID
            if (messageData.id != lastReceivedMessageId) {
                lastReceivedMessageId = messageData.id
                
                mainHandler.post {
                    // Notify that a message was received
                    onMessageReceived?.invoke(messageData, false)
                    
                    // Show "Ready to receive" status instead of "Message received"
                    onStatusChanged?.invoke(context.getString(R.string.status_receive_mode))
                    
                    // Vibrate on message received
                    onVibrate?.invoke(200)
                }
            } else {
                Log.d(TAG, "Duplicate message received (same ID), ignoring: ${messageData.id}")
            }
        }
        
        // Set up chunk progress listener
        CardEmulationService.instance?.onChunkProgressListener = { receivedChunks, totalChunks ->
            // Cancel any existing timeout
            chunkwiseTransferManager.cancelTransferTimeout()
            
            // Start a new timeout
            startTransferTimeout()
            
            mainHandler.post {
                if (receivedChunks == 0) {
                    // This is the initial notification (CHUNK_INIT)
                    Log.d(TAG, "Chunk receive started: $totalChunks chunks total")
                    // Notify that chunk receive has started
                    chunkwiseTransferManager.onChunkReceiveStarted?.invoke(totalChunks)
                    // Update status text
                    onStatusChanged?.invoke(context.getString(R.string.receiving_chunk, receivedChunks, totalChunks))
                } else if (receivedChunks == totalChunks) {
                    // This is the final notification (CHUNK_COMPLETE)
                    Log.d(TAG, "Chunk receive completed: $receivedChunks/$totalChunks")
                    // Update status text
                    onStatusChanged?.invoke(context.getString(R.string.receiving_chunk, receivedChunks, totalChunks))
                    // Notify about progress
                    chunkwiseTransferManager.onChunkReceiveProgress?.invoke(receivedChunks, totalChunks)
                    // Notify that chunk transfer is completed to hide the progress bar
                    onChunkTransferCompleted?.invoke()
                } else {
                    // This is a progress notification (CHUNK_DATA)
                    Log.d(TAG, "Chunk receive progress: $receivedChunks/$totalChunks")
                    // Update status text
                    onStatusChanged?.invoke(context.getString(R.string.receiving_chunk, receivedChunks, totalChunks))
                    // Notify about progress
                    chunkwiseTransferManager.onChunkReceiveProgress?.invoke(receivedChunks, totalChunks)
                }
            }
        }
        
        // Set up chunk error listener
        CardEmulationService.instance?.onChunkErrorListener = { errorMessage ->
            // Cancel any existing timeout
            chunkwiseTransferManager.cancelTransferTimeout()
            
            mainHandler.post {
                Log.e(TAG, "Chunk error: $errorMessage")
                onStatusChanged?.invoke(context.getString(R.string.chunked_transfer_failed))
                Toast.makeText(context, context.getString(R.string.chunked_transfer_error_receiver, errorMessage), Toast.LENGTH_LONG).show()
                
                // Make sure we're in receive mode to recover from the error
                if (appState != AppState.RECEIVING) {
                    switchToReceiveMode()
                }
                
                // Hide the progress bar on error
                onChunkTransferCompleted?.invoke()
            }
        }
    }
    
    /**
     * Start a timeout for chunked transfers
     */
    private fun startTransferTimeout() {
        // Cancel any existing timeout first
        chunkwiseTransferManager.cancelTransferTimeout()
        
        // Create a new timeout handler if needed
        if (chunkwiseTransferManager.transferTimeoutHandler == null) {
            chunkwiseTransferManager.transferTimeoutHandler = Handler(Looper.getMainLooper())
        }
        
        // Create a new timeout runnable
        chunkwiseTransferManager.transferTimeoutRunnable = Runnable {
            Log.e(TAG, "Transfer timeout occurred")
            
            if (chunkwiseTransferManager.chunkedTransferState != ChunkedTransferState.IDLE) {
                // Handle timeout on sender side
                context.runOnUiThread {
                    onStatusChanged?.invoke(context.getString(R.string.chunked_transfer_timeout_waiting))
                    Toast.makeText(context, context.getString(R.string.chunked_transfer_timeout), Toast.LENGTH_LONG).show()
                    
                    // Don't reset chunked send mode or switch to receive mode
                    // Instead, start the retry timeout if not already in retry mode
                    if (!chunkwiseTransferManager.isRetryingTransfer) {
                        chunkwiseTransferManager.startTransferRetryTimeout()
                    }
                }
            } else if (CardEmulationService.instance?.isReceivingChunkedMessage() == true) {
                // Handle timeout on receiver side
                CardEmulationService.instance?.resetChunkedMessageState()
                context.runOnUiThread {
                    onStatusChanged?.invoke(context.getString(R.string.chunked_transfer_failed))
                    Toast.makeText(context, context.getString(R.string.chunked_transfer_timeout), Toast.LENGTH_LONG).show()
                    // Hide the progress bar on timeout
                    onChunkTransferCompleted?.invoke()
                }
            }
        }
        
        // Schedule the timeout
        chunkwiseTransferManager.transferTimeoutHandler?.postDelayed(
            chunkwiseTransferManager.transferTimeoutRunnable!!, 
            chunkwiseTransferManager.transferRetryTimeoutMs
        )
    }
    
    /**
     * Cancel any active transfer timeout
     */
    fun cancelTransferTimeout() {
        chunkwiseTransferManager.cancelTransferTimeout()
    }
    
    /**
     * Enable reader mode for NFC
     */
    fun enableReaderMode() {
        nfcAdapter?.enableReaderMode(context, { tag ->
            handleTagDiscovered(tag)
        },
        NfcAdapter.FLAG_READER_NFC_A or
                NfcAdapter.FLAG_READER_SKIP_NDEF_CHECK,
        null)
    }
    
    /**
     * Disable reader mode for NFC
     */
    fun disableReaderMode() {
        nfcAdapter?.disableReaderMode(context)
    }
    
    /**
     * Handle tag discovered event
     */
    fun handleTagDiscovered(tag: Tag) {
        val isoDep = IsoDep.get(tag) ?: return
        
        try {
            isoDep.connect()
            Log.d(TAG, "Connected to tag")
            
            // If we were in retry mode, cancel the retry timeout
            if (chunkwiseTransferManager.isRetryingTransfer) {
                chunkwiseTransferManager.cancelTransferRetryTimeout()
                chunkwiseTransferManager.isRetryingTransfer = false
                context.runOnUiThread {
                    onStatusChanged?.invoke("Connection restored. Continuing transfer...")
                }
            }
            
            // Select our AID
            val selectApdu = NfcProtocol.buildSelectApdu(NfcProtocol.DEFAULT_AID)
            val result = isoDep.transceive(selectApdu)
            
            if (!NfcProtocol.isSuccess(result)) {
                Log.e(TAG, "Error selecting AID: ${NfcProtocol.byteArrayToHex(result)}")
                return
            }
            
            when (appState) {
                AppState.SENDING -> {
                    if (chunkwiseTransferManager.chunkedTransferState == ChunkedTransferState.SENDING_CHUNKS) {
                        // Handle chunked message sending
                        chunkwiseTransferManager.handleChunkedMessageSending(isoDep)
                    } else {
                        // Check if the message is too long and needs to be chunked
                        val message = lastSentMessage
                        if (chunkwiseTransferManager.needsChunkedTransfer(message)) {
                            // Prepare for chunked sending
                            chunkwiseTransferManager.prepareChunkedMessageSending(message)
                            // Start chunked sending
                            chunkwiseTransferManager.handleChunkedMessageSending(isoDep)
                        } else {
                            // Send data to the HCE device (normal mode)
                            sendRegularMessage(isoDep, message)
                        }
                    }
                }
                else -> {
                    // Request data from the HCE device
                    requestDataFromTag(isoDep)
                }
            }
            
        } catch (e: IOException) {
            Log.e(TAG, "Error communicating with tag: ${e.message}")
            // Don't switch to receive mode immediately, use the retry mechanism
            handleTagCommunicationError(e)
        } catch (e: TagLostException) {
            Log.e(TAG, "Tag lost: ${e.message}")
            // Don't switch to receive mode immediately, use the retry mechanism
            handleTagLostError(e)
        } finally {
            try {
                isoDep.close()
            } catch (e: IOException) {
                Log.e(TAG, "Error closing tag connection: ${e.message}")
            }
        }
    }
    
    /**
     * Request data from an NFC tag
     */
    private fun requestDataFromTag(isoDep: IsoDep) {
        Log.d(TAG, "Requesting data from tag")
        val getCommand = NfcProtocol.createGetDataCommand()
        val getResult = isoDep.transceive(getCommand)
        
        if (NfcProtocol.isSuccess(getResult)) {
            // Extract the data (remove the status word)
            val dataBytes = getResult.copyOfRange(0, getResult.size - 2)
            val receivedMessage = String(dataBytes, Charset.forName("UTF-8"))
            
            // Parse the JSON message
            val messageData = MessageData.fromJson(receivedMessage)
            
            if (messageData != null) {
                // Check if this is a duplicate message based on ID
                if (messageData.id != lastReceivedMessageId) {
                    lastReceivedMessageId = messageData.id
                    
                    context.runOnUiThread {
                        // Notify that a message was received
                        onMessageReceived?.invoke(messageData, false)
                        onStatusChanged?.invoke(context.getString(R.string.status_receive_mode))
                        
                        // Vibrate on message received
                        onVibrate?.invoke(200)
                    }
                } else {
                    Log.d(TAG, "Duplicate message received (same ID), ignoring: ${messageData.id}")
                    // Don't update UI or vibrate for duplicate messages
                }
            } else {
                Log.e(TAG, "Failed to parse message data: $receivedMessage")
            }
        }
    }
    
    /**
     * Send a regular (non-chunked) message
     */
    private fun sendRegularMessage(isoDep: IsoDep, message: String) {
        try {
            // Create a MessageData object with the message content and a unique ID
            val messageData = MessageData(message)
            val jsonMessage = messageData.toJson()
            
            val sendCommand = NfcProtocol.createSendDataCommand(jsonMessage)
            val sendResult = isoDep.transceive(sendCommand)
            
            if (NfcProtocol.isSuccess(sendResult)) {
                // Cancel any retry timeout since we succeeded
                chunkwiseTransferManager.cancelTransferRetryTimeout()
                chunkwiseTransferManager.isRetryingTransfer = false
                
                context.runOnUiThread {
                    onStatusChanged?.invoke(context.getString(R.string.message_sent))
                    
                    // Notify that the message was sent
                    onMessageSent?.invoke(-1) // -1 indicates the last message
                    
                    // Vibrate on message sent
                    onVibrate?.invoke(200)
                    
                    // Clear the sent message to prevent re-sending
                    lastSentMessage = ""
                    
                    // Switch to receive mode automatically
                    switchToReceiveMode()
                }
            } else {
                // If we're already in retry mode, just log the failure and wait for retry timeout
                if (chunkwiseTransferManager.isRetryingTransfer) {
                    Log.e(TAG, "Failed to send message, waiting for retry timeout or reconnection")
                    return
                }
                
                // If retry timeout is disabled (0), immediately show failure
                if (chunkwiseTransferManager.transferRetryTimeoutMs <= 0) {
                    context.runOnUiThread {
                        onStatusChanged?.invoke(context.getString(R.string.message_send_failed))
                        // Switch to receive mode to recover from error
                        switchToReceiveMode()
                    }
                    return
                }
                
                // Start retry timeout and wait for reconnection
                context.runOnUiThread {
                    onStatusChanged?.invoke("Send failed. Waiting for reconnection...")
                }
                chunkwiseTransferManager.startTransferRetryTimeout()
            }
        } catch (e: IOException) {
            // Handle communication errors with retry logic
            handleTagCommunicationError(e)
        } catch (e: TagLostException) {
            // Handle tag lost errors with retry logic
            handleTagLostError(e)
        } catch (e: Exception) {
            // For other exceptions, log and reset
            Log.e(TAG, "Unexpected error sending message: ${e.message}")
            resetAndSwitchToReceiveMode("Error: ${e.message}")
        }
    }
    
    /**
     * Handle communication errors with the NFC tag
     */
    private fun handleTagCommunicationError(e: IOException) {
        Log.e(TAG, "Error communicating with tag: ${e.message}")
        
        if (chunkwiseTransferManager.transferRetryTimeoutMs <= 0) {
            resetAndSwitchToReceiveMode("Communication error: ${e.message}")
            return
        }
        
        if (chunkwiseTransferManager.isRetryingTransfer) {
            return
        }
        
        context.runOnUiThread {
            onStatusChanged?.invoke("Connection lost. Waiting for reconnection...")
        }
        
        chunkwiseTransferManager.startTransferRetryTimeout()
    }
    
    /**
     * Handle tag lost errors
     */
    private fun handleTagLostError(e: TagLostException) {
        Log.e(TAG, "Tag lost: ${e.message}")
        
        if (chunkwiseTransferManager.transferRetryTimeoutMs <= 0) {
            resetAndSwitchToReceiveMode("Tag connection lost. Try again.")
            return
        }
        
        if (chunkwiseTransferManager.isRetryingTransfer) {
            return
        }
        
        context.runOnUiThread {
            onStatusChanged?.invoke("Tag connection lost. Waiting for reconnection...")
        }
        
        chunkwiseTransferManager.startTransferRetryTimeout()
    }
    
    /**
     * Reset all transfer state and switch to receive mode
     */
    private fun resetAndSwitchToReceiveMode(statusMessage: String) {
        // Cancel any active timeouts
        cancelTransferTimeout()
        chunkwiseTransferManager.cancelTransferRetryTimeout()
        
        context.runOnUiThread {
            // Update status
            onStatusChanged?.invoke(statusMessage)
            
            // If we were in chunked send mode, show a more specific error message
            if (chunkwiseTransferManager.chunkedTransferState == ChunkedTransferState.SENDING_CHUNKS) {
                Toast.makeText(context, context.getString(R.string.chunked_transfer_error, "Connection lost"), Toast.LENGTH_LONG).show()
                resetChunkedSendMode()
            }
            
            // Reset retry flag
            chunkwiseTransferManager.isRetryingTransfer = false
            
            // Switch to receive mode to recover from error
            switchToReceiveMode()
        }
    }
    
    /**
     * Reset chunked send mode
     */
    fun resetChunkedSendMode() {
        chunkwiseTransferManager.resetChunkedSendMode()
    }
    
    /**
     * Update chunked message settings
     */
    fun updateChunkedMessageSettings(maxChunkSize: Int, chunkDelay: Long, transferRetryTimeoutMs: Long) {
        chunkwiseTransferManager.updateSettings(maxChunkSize, chunkDelay, transferRetryTimeoutMs)
    }
    
    /**
     * Clean up resources
     */
    fun cleanup() {
        // Clean up chunked transfer manager resources
        chunkwiseTransferManager.cleanup()
        
        // If we're not in receive mode anymore, stop the service
        if (appState != AppState.RECEIVING) {
            val intent = Intent(context, CardEmulationService::class.java)
            context.stopService(intent)
        }
    }
    
    /**
     * Get the NFC adapter
     */
    fun getNfcAdapter(): NfcAdapter? {
        return nfcAdapter
    }
    
    /**
     * Set the current message for the CardEmulationService
     */
    fun setCardEmulationMessage(message: String) {
        CardEmulationService.instance?.messageToShare = message
    }
    
    /**
     * Check if the transfer manager is retrying a transfer
     */
    fun isRetryingTransfer(): Boolean {
        return chunkwiseTransferManager.isRetryingTransfer
    }
} 