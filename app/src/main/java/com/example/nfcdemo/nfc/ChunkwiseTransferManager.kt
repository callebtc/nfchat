package com.example.nfcdemo.nfc

import android.content.Context
import android.nfc.TagLostException
import android.nfc.tech.IsoDep
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Toast
import com.example.nfcdemo.R
import java.io.IOException

/**
 * Enum representing the different states of chunked message transfer
 */
enum class ChunkedTransferState {
    IDLE,               // Not in chunked transfer mode
    INITIALIZING,       // Preparing for chunked transfer
    SENDING_CHUNKS,     // Actively sending chunks
    COMPLETING,         // Finalizing the transfer
    ERROR               // An error occurred during transfer
}

/**
 * Manager class for handling chunked data transfers over NFC
 */
class ChunkwiseTransferManager(private val context: Context) {
    private val TAG = "ChunkwiseTransferMgr"
    
    // Chunked message transfer state
    var chunkedTransferState = ChunkedTransferState.IDLE
    private var chunksToSend = mutableListOf<String>()
    private var currentChunkIndex = 0
    private var totalChunks = 0
    private var maxChunkSize = 2048 // Default value, will be updated from settings
    private var chunkDelay = 50L // Default value, will be updated from settings
    var transferRetryTimeoutMs: Long = 5000L // Default value, will be updated from settings
    private var acknowledgedChunks = mutableSetOf<Int>()
    private var chunkSendAttempts = mutableMapOf<Int, Int>()
    private val MAX_SEND_ATTEMPTS = 3 // Default value, will be updated from settings
    
    // Transfer timeout handler
    var transferTimeoutHandler: Handler? = null
    var transferTimeoutRunnable: Runnable? = null
    
    // Transfer retry timeout handler
    var transferRetryTimeoutHandler: Handler? = null
    var transferRetryTimeoutRunnable: Runnable? = null
    var isRetryingTransfer = false
    
    // Callbacks
    var onTransferStatusChanged: ((String) -> Unit)? = null
    var onTransferCompleted: (() -> Unit)? = null
    var onTransferError: ((String) -> Unit)? = null
    var onTransferRetryStarted: (() -> Unit)? = null
    var onTransferRetryTimeout: (() -> Unit)? = null
    
    /**
     * Update the chunked message settings
     */
    fun updateSettings(maxChunkSize: Int, chunkDelay: Long, transferRetryTimeoutMs: Long) {
        this.maxChunkSize = maxChunkSize
        this.chunkDelay = chunkDelay
        this.transferRetryTimeoutMs = transferRetryTimeoutMs
        Log.d(TAG, "Updated settings: maxChunkSize=$maxChunkSize, chunkDelay=$chunkDelay, transferRetryTimeoutMs=$transferRetryTimeoutMs")
    }
    
    /**
     * Prepare for chunked message sending by splitting the message into chunks
     */
    fun prepareChunkedMessageSending(message: String): Boolean {
        // Reset chunked sending state
        chunksToSend.clear()
        acknowledgedChunks.clear()
        chunkSendAttempts.clear()
        currentChunkIndex = 0
        
        // Create a MessageData object with the message content and a unique ID
        val messageData = MessageData(message)
        val jsonMessage = messageData.toJson()
        
        // Split the message into chunks
        val messageLength = jsonMessage.length
        totalChunks = (messageLength + maxChunkSize - 1) / maxChunkSize // Ceiling division
        
        for (i in 0 until totalChunks) {
            val startIndex = i * maxChunkSize
            val endIndex = minOf(startIndex + maxChunkSize, messageLength)
            val chunk = jsonMessage.substring(startIndex, endIndex)
            chunksToSend.add(chunk)
            chunkSendAttempts[i] = 0
        }
        
        chunkedTransferState = ChunkedTransferState.SENDING_CHUNKS
        Log.d(TAG, "Prepared chunked message: ${chunksToSend.size} chunks, total length: $messageLength")
        return true
    }
    
    /**
     * Handle the chunked message sending process
     */
    fun handleChunkedMessageSending(isoDep: IsoDep): Boolean {
        if (chunkedTransferState != ChunkedTransferState.SENDING_CHUNKS || chunksToSend.isEmpty()) {
            Log.e(TAG, "Attempted chunked sending but not properly prepared")
            return false
        }
        
        try {
            // If we're just starting (no acknowledged chunks), send the initialization command
            if (acknowledgedChunks.isEmpty()) {
                if (!initializeChunkedTransfer(isoDep)) {
                    // If we're already in retry mode, just return and wait for retry timeout
                    if (isRetryingTransfer) {
                        return false
                    }
                    
                    // If retry timeout is disabled (0), immediately handle error
                    if (transferRetryTimeoutMs <= 0) {
                        handleChunkedTransferError("Failed to initialize chunked transfer")
                        return false
                    }
                    
                    // Start retry timeout and wait for reconnection
                    onTransferStatusChanged?.invoke(context.getString(R.string.failed_to_initialize_transfer))
                    startTransferRetryTimeout()
                    return false
                }
            }
            
            // Send chunks until all are acknowledged or max attempts reached
            val transferResult = sendAllChunks(isoDep)
            
            // If transfer was successful, complete it
            if (transferResult) {
                // Cancel the transfer timeout since we're done
                cancelTransferTimeout()
                cancelTransferRetryTimeout()
                isRetryingTransfer = false
                
                return completeChunkedTransfer(isoDep)
            } else {
                // If we're already in retry mode, just return and wait for retry timeout
                if (isRetryingTransfer) {
                    return false
                }
                
                // If retry timeout is disabled (0), immediately handle error
                if (transferRetryTimeoutMs <= 0) {
                    handleChunkedTransferError(context.getString(R.string.chunked_transfer_incomplete))
                    return false
                }
                
                // Start retry timeout and wait for reconnection
                onTransferStatusChanged?.invoke(context.getString(R.string.transfer_incomplete))
                startTransferRetryTimeout()
                return false
            }
        } catch (e: IOException) {
            // Handle communication errors with retry logic
            handleTagCommunicationError(e)
            return false
        } catch (e: TagLostException) {
            // Handle tag lost errors with retry logic
            handleTagLostError(e)
            return false
        } catch (e: Exception) {
            // For other exceptions, log and reset if not in retry mode
            Log.e(TAG, "Error during chunked sending: ${e.message}")
            
            // If we're already in retry mode, just return and wait for retry timeout
            if (isRetryingTransfer) {
                return false
            }
            
            // If retry timeout is disabled (0), immediately handle error
            if (transferRetryTimeoutMs <= 0) {
                handleChunkedTransferError(e.message ?: "Unknown error")
                return false
            }
            
            // Start retry timeout and wait for reconnection
            onTransferStatusChanged?.invoke(context.getString(R.string.error_during_transfer))
            startTransferRetryTimeout()
            return false
        }
    }
    
    /**
     * Initialize the chunked transfer by sending the initialization command
     * @return true if initialization was successful, false otherwise
     */
    private fun initializeChunkedTransfer(isoDep: IsoDep): Boolean {
        // Update state to initializing
        chunkedTransferState = ChunkedTransferState.INITIALIZING
        
        val totalLength = chunksToSend.joinToString("").length
        val initCommand = NfcProtocol.createChunkInitCommand(totalLength, maxChunkSize, totalChunks)
        val initResult = isoDep.transceive(initCommand)
        
        if (!NfcProtocol.isSuccess(initResult)) {
            Log.e(TAG, "Failed to initialize chunked transfer")
            chunkedTransferState = ChunkedTransferState.ERROR
            return false
        }
        
        // Update UI to show we're starting chunked transfer
        onTransferStatusChanged?.invoke(context.getString(R.string.sending_chunk, 0, totalChunks))
        
        // Start the transfer timeout
        startTransferTimeout()
        
        // Update state to sending chunks
        chunkedTransferState = ChunkedTransferState.SENDING_CHUNKS
        return true
    }
    
    /**
     * Send all chunks until all are acknowledged or max attempts reached
     * @return true if all chunks were acknowledged, false otherwise
     */
    private fun sendAllChunks(isoDep: IsoDep): Boolean {
        var currentAttempt = 0
        
        while (currentAttempt < MAX_SEND_ATTEMPTS * totalChunks) {
            // Find the next chunk to send (either the current one or one that needs retrying)
            val chunkToSend = findNextChunkToSend()
            
            if (chunkToSend == -1) {
                // All chunks have been acknowledged
                return acknowledgedChunks.size == totalChunks
            }
            
            // Update UI with current progress
            onTransferStatusChanged?.invoke(context.getString(R.string.sending_chunk, acknowledgedChunks.size + 1, totalChunks))
            
            // Send the chunk
            val chunkData = chunksToSend[chunkToSend]
            val chunkCommand = NfcProtocol.createChunkDataCommand(chunkToSend, chunkData)
            val chunkResult = isoDep.transceive(chunkCommand)
            
            // Increment attempt counter
            chunkSendAttempts[chunkToSend] = (chunkSendAttempts[chunkToSend] ?: 0) + 1
            currentAttempt++
            
            if (NfcProtocol.isSuccess(chunkResult)) {
                // Check if we got an acknowledgment
                val ackIndex = NfcProtocol.parseChunkAck(chunkResult)
                
                if (ackIndex != -1) {
                    Log.d(TAG, "Chunk $ackIndex acknowledged")
                    acknowledgedChunks.add(ackIndex)
                    
                    // Reset the transfer timeout since we got a response
                    startTransferTimeout()
                }
            }
            
            // Small delay between chunks to avoid overwhelming the receiver
            Thread.sleep(chunkDelay)
        }
        
        // If we get here, we've reached the maximum number of attempts
        return acknowledgedChunks.size == totalChunks
    }
    
    /**
     * Complete the chunked transfer by sending the completion command
     */
    private fun completeChunkedTransfer(isoDep: IsoDep): Boolean {
        // Update state to completing
        chunkedTransferState = ChunkedTransferState.COMPLETING
        
        val completeCommand = NfcProtocol.createChunkCompleteCommand()
        val completeResult = isoDep.transceive(completeCommand)
        
        if (NfcProtocol.isSuccess(completeResult)) {
            Log.d(TAG, "Chunked transfer completed successfully")
            
            onTransferStatusChanged?.invoke(context.getString(R.string.chunked_transfer_complete))
            onTransferCompleted?.invoke()
            
            // Reset chunked send mode
            resetChunkedSendMode()
            
            return true
        } else {
            Log.e(TAG, "Failed to complete chunked transfer")
            chunkedTransferState = ChunkedTransferState.ERROR
            handleChunkedTransferError(context.getString(R.string.chunked_transfer_failed_message))
            return false
        }
    }
    
    /**
     * Handle errors during chunked transfer
     */
    private fun handleChunkedTransferError(errorMessage: String) {
        Log.e(TAG, "Chunked transfer error: $errorMessage")
        chunkedTransferState = ChunkedTransferState.ERROR
        
        onTransferStatusChanged?.invoke(context.getString(R.string.chunked_transfer_failed))
        Toast.makeText(context, context.getString(R.string.chunked_transfer_error, errorMessage), Toast.LENGTH_LONG).show()
        
        resetChunkedSendMode()
        onTransferError?.invoke(errorMessage)
    }
    
    /**
     * Find the next chunk that needs to be sent
     * @return The index of the next chunk to send, or -1 if all chunks have been acknowledged
     */
    private fun findNextChunkToSend(): Int {
        // First, check if there are any chunks that haven't been attempted yet
        for (i in currentChunkIndex until totalChunks) {
            if (!acknowledgedChunks.contains(i)) {
                currentChunkIndex = i
                return i
            }
        }
        
        // If all chunks have been attempted at least once, check for any that haven't been acknowledged
        // and haven't reached the maximum number of attempts
        for (i in 0 until totalChunks) {
            if (!acknowledgedChunks.contains(i) && (chunkSendAttempts[i] ?: 0) < MAX_SEND_ATTEMPTS) {
                return i
            }
        }
        
        // If we get here, either all chunks have been acknowledged or we've reached the maximum
        // number of attempts for all chunks
        return -1
    }
    
    /**
     * Reset the chunked send mode state
     */
    fun resetChunkedSendMode() {
        // Cancel any active transfer timeout
        cancelTransferTimeout()
        
        // Cancel any active retry timeout
        cancelTransferRetryTimeout()
        isRetryingTransfer = false
        
        chunkedTransferState = ChunkedTransferState.IDLE
        chunksToSend.clear()
        acknowledgedChunks.clear()
        chunkSendAttempts.clear()
        currentChunkIndex = 0
        totalChunks = 0
    }
    
    /**
     * Start a timeout for chunked transfers
     */
    fun startTransferTimeout() {
        // Cancel any existing timeout first
        cancelTransferTimeout()
        
        // Create a new timeout handler if needed
        if (transferTimeoutHandler == null) {
            transferTimeoutHandler = Handler(Looper.getMainLooper())
        }
        
        // Create a new timeout runnable
        transferTimeoutRunnable = Runnable {
            Log.e(TAG, "Transfer timeout occurred")
            
            if (chunkedTransferState != ChunkedTransferState.IDLE) {
                // Handle timeout on sender side
                onTransferStatusChanged?.invoke(context.getString(R.string.chunked_transfer_failed))
                Toast.makeText(context, context.getString(R.string.chunked_transfer_timeout), Toast.LENGTH_LONG).show()
                resetChunkedSendMode()
                onTransferError?.invoke(context.getString(R.string.chunked_transfer_timeout))
            }
        }
        
        // Schedule the timeout
        transferTimeoutHandler?.postDelayed(transferTimeoutRunnable!!, transferRetryTimeoutMs)
    }
    
    /**
     * Cancel any active transfer timeout
     */
    fun cancelTransferTimeout() {
        transferTimeoutRunnable?.let {
            transferTimeoutHandler?.removeCallbacks(it)
            transferTimeoutRunnable = null
        }
    }
    
    /**
     * Handle communication errors with the NFC tag
     */
    private fun handleTagCommunicationError(e: IOException) {
        Log.e(TAG, "Error communicating with tag: ${e.message}")
        
        // If retry timeout is disabled (0), immediately reset
        if (transferRetryTimeoutMs <= 0) {
            resetAndNotifyError("Communication error: ${e.message}")
            return
        }
        
        // If we're already retrying, don't start another retry timer
        if (isRetryingTransfer) {
            return
        }
        
        // Update UI to show we're waiting for reconnection
        onTransferStatusChanged?.invoke(context.getString(R.string.connection_lost))
        
        // Start retry timeout
        startTransferRetryTimeout()
    }
    
    /**
     * Handle tag lost errors
     */
    private fun handleTagLostError(e: TagLostException) {
        Log.e(TAG, "Tag lost: ${e.message}")
        
        // If retry timeout is disabled (0), immediately reset
        if (transferRetryTimeoutMs <= 0) {
            resetAndNotifyError(context.getString(R.string.tag_connection_lost))
            return
        }
        
        // If we're already retrying, don't start another retry timer
        if (isRetryingTransfer) {
            return
        }
        
        // Update UI to show we're waiting for reconnection
        onTransferStatusChanged?.invoke(context.getString(R.string.tag_connection_lost_waiting))
        
        // Start retry timeout
        startTransferRetryTimeout()
    }
    
    /**
     * Start a timeout for transfer retry
     * After this timeout, we'll give up and switch to receive mode
     */
    fun startTransferRetryTimeout() {
        // Mark that we're in retry mode
        isRetryingTransfer = true
        
        // Cancel any existing retry timeout
        cancelTransferRetryTimeout()
        
        // Create a new timeout handler if needed
        if (transferRetryTimeoutHandler == null) {
            transferRetryTimeoutHandler = Handler(Looper.getMainLooper())
        }
        
        // Create a new timeout runnable
        transferRetryTimeoutRunnable = Runnable {
            Log.e(TAG, "Transfer retry timeout occurred")
            
            // Reset retry flag
            isRetryingTransfer = false
            
            // Reset and notify error
            resetAndNotifyError(context.getString(R.string.connection_not_restored))
        }
        
        // Notify that we're starting retry
        onTransferRetryStarted?.invoke()
        
        // Schedule the timeout
        transferRetryTimeoutHandler?.postDelayed(transferRetryTimeoutRunnable!!, transferRetryTimeoutMs)
    }
    
    /**
     * Cancel any active transfer retry timeout
     */
    fun cancelTransferRetryTimeout() {
        transferRetryTimeoutRunnable?.let {
            transferRetryTimeoutHandler?.removeCallbacks(it)
            transferRetryTimeoutRunnable = null
        }
    }
    
    /**
     * Reset all transfer state and notify error
     */
    private fun resetAndNotifyError(errorMessage: String) {
        // Cancel any active timeouts
        cancelTransferTimeout()
        cancelTransferRetryTimeout()
        
        // Update status
        onTransferStatusChanged?.invoke(errorMessage)
        
        // If we were in chunked send mode, show a more specific error message
        if (chunkedTransferState == ChunkedTransferState.SENDING_CHUNKS) {
            Toast.makeText(context, context.getString(R.string.chunked_transfer_error, "Connection lost"), Toast.LENGTH_LONG).show()
            resetChunkedSendMode()
        }
        
        // Reset retry flag
        isRetryingTransfer = false
        
        // Notify error
        onTransferError?.invoke(errorMessage)
        
        // Notify retry timeout
        onTransferRetryTimeout?.invoke()
    }
    
    /**
     * Check if a message needs to be sent in chunks
     */
    fun needsChunkedTransfer(message: String): Boolean {
        // Create a MessageData object with the message content and a unique ID
        val messageData = MessageData(message)
        val jsonMessage = messageData.toJson()
        
        return jsonMessage.length > maxChunkSize
    }
    
    /**
     * Send a regular (non-chunked) message
     */
    fun sendRegularMessage(isoDep: IsoDep, message: String): Boolean {
        try {
            // Create a MessageData object with the message content and a unique ID
            val messageData = MessageData(message)
            val jsonMessage = messageData.toJson()
            
            val sendCommand = NfcProtocol.createSendDataCommand(jsonMessage)
            val sendResult = isoDep.transceive(sendCommand)
            
            if (NfcProtocol.isSuccess(sendResult)) {
                // Cancel any retry timeout since we succeeded
                cancelTransferRetryTimeout()
                isRetryingTransfer = false
                
                onTransferStatusChanged?.invoke(context.getString(R.string.message_sent))
                onTransferCompleted?.invoke()
                
                return true
            } else {
                // If we're already in retry mode, just log the failure and wait for retry timeout
                if (isRetryingTransfer) {
                    Log.e(TAG, "Failed to send message, waiting for retry timeout or reconnection")
                    return false
                }
                
                // If retry timeout is disabled (0), immediately show failure
                if (transferRetryTimeoutMs <= 0) {
                    onTransferStatusChanged?.invoke(context.getString(R.string.message_send_failed))
                    onTransferError?.invoke(context.getString(R.string.message_send_failed))
                    return false
                }
                
                // Start retry timeout and wait for reconnection
                onTransferStatusChanged?.invoke(context.getString(R.string.send_failed_waiting))
                startTransferRetryTimeout()
                return false
            }
        } catch (e: IOException) {
            // Handle communication errors with retry logic
            handleTagCommunicationError(e)
            return false
        } catch (e: TagLostException) {
            // Handle tag lost errors with retry logic
            handleTagLostError(e)
            return false
        } catch (e: Exception) {
            // For other exceptions, log and reset
            Log.e(TAG, "Unexpected error sending message: ${e.message}")
            resetAndNotifyError("Error: ${e.message}")
            return false
        }
    }
    
    /**
     * Clean up resources
     */
    fun cleanup() {
        cancelTransferTimeout()
        cancelTransferRetryTimeout()
        resetChunkedSendMode()
    }
} 