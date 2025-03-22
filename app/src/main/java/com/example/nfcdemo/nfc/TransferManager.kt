package com.example.nfcdemo.nfc

import android.app.Activity
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.IntentFilter.MalformedMimeTypeException
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.nfc.TagLostException
import android.nfc.tech.IsoDep
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Toast
import com.example.nfcdemo.AppState
import com.example.nfcdemo.CardEmulationService
import com.example.nfcdemo.R
import com.example.nfcdemo.ui.VibrationUtils
import java.io.IOException
import java.nio.charset.Charset

/** Manager class for handling NFC data transfer operations */
class TransferManager(private val context: Activity) {
    private val TAG = "TransferManager"

    // NFC adapter
    private var nfcAdapter: NfcAdapter? = null

    // State management
    private var appState = AppState.IDLE
    private var lastSentMessage: String = ""
    private var lastReceivedMessageId: String = ""

    // NdefProcessor for handling NDEF operations
    private val ndefProcessor = NdefProcessor()

    // Chunked transfer manager
    private val chunkwiseTransferManager = ChunkwiseTransferManager(context)

    // Handler for main thread operations
    private val mainHandler = Handler(Looper.getMainLooper())

    // For foreground dispatch
    private lateinit var pendingIntent: PendingIntent
    private lateinit var intentFilters: Array<IntentFilter>
    private lateinit var techLists: Array<Array<String>>

    // Callbacks
    var onAppStateChanged: ((AppState) -> Unit)? = null
    var onStatusChanged: ((String) -> Unit)? = null
    var onMessageSent: ((Int) -> Unit)? = null
    var onMessageReceived: ((MessageData, Boolean) -> Unit)? = null

    // Chunk transfer callbacks
    var onChunkTransferStarted: ((Int) -> Unit)? = null
    var onChunkTransferProgress: ((Int, Int) -> Unit)? = null
    var onChunkTransferCompleted: (() -> Unit)? = null

    // Service watchdog
    private val serviceWatchdogRunnable =
            object : Runnable {
                override fun run() {
                    // Check if we're in receive mode but service instance is null
                    if (appState == AppState.RECEIVING && CardEmulationService.instance == null) {
                        Log.d(TAG, "Service watchdog detected missing service, restarting")
                        restartCardEmulationService()
                    }

                    // Schedule next check
                    mainHandler.postDelayed(this, SERVICE_WATCHDOG_INTERVAL)
                }
            }

    // Service lifecycle receiver
    private val serviceLifecycleReceiver =
            object : BroadcastReceiver() {
                override fun onReceive(context: Context?, intent: Intent?) {
                    when (intent?.action) {
                        CardEmulationService.ACTION_SERVICE_STARTED -> {
                            Log.d(TAG, "CardEmulationService started, registering listeners")
                            mainHandler.postDelayed(
                                    {
                                        initializeReceiveMode()
                                        // Request the service to deliver any pending messages
                                        val registerIntent =
                                                Intent(
                                                                CardEmulationService
                                                                        .ACTION_REGISTER_LISTENERS
                                                        )
                                                        .apply {
                                                            context?.packageName?.let {
                                                                setPackage(it)
                                                            }
                                                        }
                                        context?.sendBroadcast(registerIntent)
                                    },
                                    100
                            )
                        }
                        CardEmulationService.ACTION_SERVICE_DESTROYED -> {
                            Log.d(
                                    TAG,
                                    "CardEmulationService destroyed, will re-register listeners when it restarts"
                            )
                            // The service will be restarted automatically due to START_STICKY
                            // But let's help it along by checking our watchdog soon
                            mainHandler.postDelayed(
                                    {
                                        if (appState == AppState.RECEIVING &&
                                                        CardEmulationService.instance == null
                                        ) {
                                            restartCardEmulationService()
                                        }
                                    },
                                    500
                            )
                        }
                    }
                }
            }

    init {
        // Initialize NFC adapter
        nfcAdapter = NfcAdapter.getDefaultAdapter(context)

        // Set up chunked transfer manager callbacks
        setupChunkwiseTransferCallbacks()

        // Register service lifecycle receiver
        val filter =
                IntentFilter().apply {
                    addAction(CardEmulationService.ACTION_SERVICE_STARTED)
                    addAction(CardEmulationService.ACTION_SERVICE_DESTROYED)
                }
        context.registerReceiver(serviceLifecycleReceiver, filter, Context.RECEIVER_NOT_EXPORTED)

        // Start the service watchdog
        startServiceWatchdog()
    }

    /** Set up callbacks for the chunked transfer manager */
    private fun setupChunkwiseTransferCallbacks() {
        chunkwiseTransferManager.onTransferStatusChanged = { status ->
            onStatusChanged?.invoke(status)
        }

        chunkwiseTransferManager.onTransferCompleted = {
            context.runOnUiThread {
                // Notify that the message was sent
                onMessageSent?.invoke(-1) // -1 indicates the last message

                // Vibrate on message sent
                VibrationUtils.vibrate(context, 200)

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

    /** Get the current app state */
    fun getAppState(): AppState {
        return appState
    }

    /** Set the last sent message */
    fun setLastSentMessage(message: String) {
        lastSentMessage = message
        
        // Set the message in our NdefProcessor
        // ndefProcessor.setMessageToSend(message)
        
        // Also set the message in the CardEmulationService's NdefProcessor
        // CardEmulationService.instance?.ndefProcessor?.setMessageToSend(message)
        setMessageToSend(message)
        // Also set the write mode based on current state
        ndefProcessor.setWriteMode(appState == AppState.SENDING)
        CardEmulationService.instance?.ndefProcessor?.setWriteMode(appState == AppState.SENDING)
    }

    /** Get the last sent message */
    fun getLastSentMessage(): String {
        return lastSentMessage
    }

    /** Toggle between send and receive modes */
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
                    Toast.makeText(
                                    context,
                                    context.getString(R.string.no_pending_message),
                                    Toast.LENGTH_SHORT
                            )
                            .show()
                }
            }
            AppState.IDLE -> {
                // If in idle mode, switch to receive mode
                switchToReceiveMode()
            }
        }
    }
    
    /** Switch to send mode */
    fun switchToSendMode() {
        Log.d(TAG, "switchToSendMode: Switching to send mode")
        // Only proceed if there's a message to send
        if (lastSentMessage.isEmpty()) {
            Log.d(TAG, "No message to send, not switching to send mode")
            return
        }

        // First, stop any receive mode operations
        if (appState == AppState.RECEIVING) {
            // Stop the CardEmulationService
            val intent = Intent(context, CardEmulationService::class.java)
            Log.d(TAG, "switchToSendMode: NOT Stopping CardEmulationService")
            // context.stopService(intent)
        }

        // Update state
        appState = AppState.SENDING
        onAppStateChanged?.invoke(appState)
        onStatusChanged?.invoke(context.getString(R.string.status_send_mode))

        // Set current message on the service
        setCardEmulationMessage(lastSentMessage)

        // Configure NdefProcessor for write mode
        ndefProcessor.setWriteMode(true)
        setMessageToSend(lastSentMessage)
        // ndefProcessor.setMessageToSend(lastSentMessage)
        
        // // Also set the CardEmulationService's NdefProcessor if available
        // CardEmulationService.instance?.ndefProcessor?.setWriteMode(true)
        // CardEmulationService.instance?.ndefProcessor?.setMessageToSend(lastSentMessage)

        // Enable reader mode for sending data
        enableReaderModeForWriting()

        Log.d(TAG, "Switched to send mode, ready to send: $lastSentMessage")
    }

    /** Switch to receive mode */
    fun switchToReceiveMode() {
        Log.d(TAG, "switchToReceiveMode: Switching to receive mode")
        // First, disable reader mode if we were in send mode
        if (appState == AppState.SENDING) {
            disableReaderMode()
            // Reset NdefProcessor write mode
            ndefProcessor.setWriteMode(false)
            
            // Also reset the CardEmulationService's NdefProcessor if available
            CardEmulationService.instance?.ndefProcessor?.setWriteMode(false)
        }

        // Update state
        appState = AppState.RECEIVING
        onAppStateChanged?.invoke(appState)
        onStatusChanged?.invoke(context.getString(R.string.status_receive_mode))

        // Start the CardEmulationService if it's not already running
        if (CardEmulationService.instance == null) {
            val intent = Intent(context, CardEmulationService::class.java)
            intent.action = Intent.ACTION_MAIN
            context.startService(intent)

            // When service starts, it will broadcast ACTION_SERVICE_STARTED
            // The serviceLifecycleReceiver will handle setting up the data receiver
            // We don't need to call setupDataReceiver() here
        } else {
            // Set up the data receiver and NDEF handling in a single call
            initializeReceiveMode()
        }

        chunkwiseTransferManager.cancelTransferTimeout()
        
        Log.d(TAG, "Switched to receive mode")
    }

    /** 
     * Initialize receive mode by setting up all required components 
     * This single method consolidates all setup required for receive mode
     */
    private fun initializeReceiveMode() {
        setupDataReceiver()
        setupNdefDataReceiver()
    }

    /** Setup data receiver for NDEF card emulation */
    fun setupDataReceiver() {
        Log.d(TAG, "setupDataReceiver: Setting up data receiver")
        // If the service instance is null, the service might have been destroyed and not yet
        // restarted
        if (CardEmulationService.instance == null) {
            Log.d(TAG, "CardEmulationService instance is null, restarting service")
            restartCardEmulationService()
            return
        }

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
                    VibrationUtils.vibrate(context, 200)
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
            chunkwiseTransferManager.startTransferTimeout()

            mainHandler.post {
                if (receivedChunks == 0) {
                    // This is the initial notification (CHUNK_INIT)
                    Log.d(TAG, "Chunk receive started: $totalChunks chunks total")
                    // Notify that chunk receive has started
                    chunkwiseTransferManager.onChunkReceiveStarted?.invoke(totalChunks)
                    // Update status text
                    onStatusChanged?.invoke(
                            context.getString(R.string.receiving_chunk, receivedChunks, totalChunks)
                    )
                } else if (receivedChunks == totalChunks) {
                    // This is the final notification (CHUNK_COMPLETE)
                    Log.d(TAG, "Chunk receive completed: $receivedChunks/$totalChunks")
                    // Notify about progress
                    chunkwiseTransferManager.onChunkReceiveProgress?.invoke(
                            receivedChunks,
                            totalChunks
                    )
                    // Notify that chunk transfer is completed to hide the progress bar
                    onChunkTransferCompleted?.invoke()
                } else {
                    // This is a progress notification (CHUNK_PROGRESS)
                    Log.d(TAG, "Chunk receive progress: $receivedChunks/$totalChunks")
                    // Update status text
                    onStatusChanged?.invoke(
                            context.getString(R.string.receiving_chunk, receivedChunks, totalChunks)
                    )
                    // Notify about progress
                    chunkwiseTransferManager.onChunkReceiveProgress?.invoke(
                            receivedChunks,
                            totalChunks
                    )
                }
            }
        }

        // Only setup the message to be shared if we're in send mode or if it was already set before
        if (CardEmulationService.instance?.messageToShare != null) {
            // If in sending mode and we have a message, set it
            if (appState == AppState.SENDING && lastSentMessage.isNotEmpty()) {
                setMessageToSend(lastSentMessage)
            }
        }

        Log.d(TAG, "setupDataReceiver: NDEF data receiver setup complete")
    }

    /** Setup NDEF data receiver for bridging with the CardEmulationService */
    private fun setupNdefDataReceiver() {
        Log.d(TAG, "setupNdefDataReceiver: Setting up NDEF data receiver")
        val cardEmulationService = CardEmulationService.instance
        if (cardEmulationService == null) {
            Log.d(TAG, "CardEmulationService instance is null, cannot setup NDEF data receiver")
            return
        }

        // Initialize the NdefProcessor in the CardEmulationService
        cardEmulationService.ndefProcessor.apply {
            // Set the callback for received NDEF messages
            onNdefMessageReceived = { messageData ->
                Log.d(TAG, "NDEF message received from NdefProcessor: ${messageData.content}")

                // Check if this is a duplicate message based on ID
                if (messageData.id != lastReceivedMessageId) {
                    lastReceivedMessageId = messageData.id

                    mainHandler.post {
                        // Notify that a message was received
                        onMessageReceived?.invoke(messageData, false)
                        onStatusChanged?.invoke(context.getString(R.string.status_receive_mode))

                        // Vibrate on message received
                        VibrationUtils.vibrate(context, 200)
                    }
                } else {
                    Log.d(
                            TAG,
                            "Duplicate NDEF message received (same ID), ignoring: ${messageData.id}"
                    )
                }
            }

            // Ensure write mode is set correctly based on current state
            setWriteMode(appState == AppState.SENDING)

            // If in sending mode and we have a message, set it
            if (appState == AppState.SENDING && lastSentMessage.isNotEmpty()) {
                setMessageToSend(lastSentMessage)
            } else {
                // Ensure it's clear when in receive mode
                setMessageToSend(lastSentMessage)
            }
        }

        Log.d(TAG, "private setupDataReceiver: NDEF data receiver setup complete")
    }

    /** Cancel any active transfer timeout */
    fun cancelTransferTimeout() {
        Log.d(TAG, "cancelTransferTimeout: Cancelling transfer timeout")
        chunkwiseTransferManager.cancelTransferTimeout()
    }

    /** Enable reader mode for NFC */
    fun enableReaderModeForWriting() {
        Log.d(TAG, "enableReaderModeForWriting: Enabling reader mode for writing")
        nfcAdapter?.enableReaderMode(
                context,
                { tag -> handleTagDiscovered(tag) },
                NfcAdapter.FLAG_READER_NFC_A,
                null
        )
    }

    /** Disable reader mode for NFC */
    fun disableReaderMode() {
        Log.d(TAG, "disableReaderMode: Disabling reader mode")
        nfcAdapter?.disableReaderMode(context)
    }

    /** Handle tag discovered event */
    fun handleTagDiscovered(tag: Tag) {
        Log.d(TAG, "handleTagDiscovered: Tag discovered: ${tag.id}")
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
                    if (chunkwiseTransferManager.chunkedTransferState ==
                                    ChunkedTransferState.SENDING_CHUNKS
                    ) {
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
                    // requestDataFromTag(isoDep)
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

    // /** Request data from an NFC tag */
    // private fun requestDataFromTag(isoDep: IsoDep) {
    //     Log.d(TAG, "requestDataFromTag: Requesting data from tag")
    //     val getCommand = NfcProtocol.createGetDataCommand()
    //     val getResult = isoDep.transceive(getCommand)

    //     if (NfcProtocol.isSuccess(getResult)) {
    //         Log.d(TAG, "requestDataFromTag: Data received from tag")
    //         // Extract the data (remove the status word)
    //         val dataBytes = getResult.copyOfRange(0, getResult.size - 2)
    //         val receivedMessage = String(dataBytes, Charset.forName("UTF-8"))
    //         Log.d(TAG, "requestDataFromTag: Received message: $receivedMessage")

    //         // Parse the JSON message
    //         val messageData = MessageData.fromJson(receivedMessage)

    //         if (messageData != null) {
    //             // Check if this is a duplicate message based on ID
    //             if (messageData.id != lastReceivedMessageId) {
    //                 lastReceivedMessageId = messageData.id

    //                 context.runOnUiThread {
    //                     // Notify that a message was received
    //                     onMessageReceived?.invoke(messageData, false)
    //                     onStatusChanged?.invoke(context.getString(R.string.status_receive_mode))

    //                     // Vibrate on message received
    //                     VibrationUtils.vibrate(context, 200)
    //                 }
    //             } else {
    //                 Log.d(TAG, "Duplicate message received (same ID), ignoring: ${messageData.id}")
    //                 // Don't update UI or vibrate for duplicate messages
    //             }
    //         } else {
    //             Log.e(TAG, "Failed to parse message data: $receivedMessage")
    //         }
    //     }
    // }

    /** Send a regular (non-chunked) message */
    private fun sendRegularMessage(isoDep: IsoDep, message: String) {
        Log.d(TAG, "sendRegularMessage: Sending regular message")
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
                    VibrationUtils.vibrate(context, 200)

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

    /** Handle communication errors with the NFC tag */
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

    /** Handle tag lost errors */
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

    /** Reset all transfer state and switch to receive mode */
    private fun resetAndSwitchToReceiveMode(statusMessage: String) {
        // Cancel any active timeouts
        cancelTransferTimeout()
        chunkwiseTransferManager.cancelTransferRetryTimeout()

        context.runOnUiThread {
            // Update status
            onStatusChanged?.invoke(statusMessage)

            // If we were in chunked send mode, show a more specific error message
            if (chunkwiseTransferManager.chunkedTransferState == ChunkedTransferState.SENDING_CHUNKS
            ) {
                Toast.makeText(
                                context,
                                context.getString(
                                        R.string.chunked_transfer_error,
                                        "Connection lost"
                                ),
                                Toast.LENGTH_LONG
                        )
                        .show()
                resetChunkedSendMode()
            }

            // Reset retry flag
            chunkwiseTransferManager.isRetryingTransfer = false

            // Switch to receive mode to recover from error
            switchToReceiveMode()
        }
    }

    /** Reset chunked send mode */
    fun resetChunkedSendMode() {
        chunkwiseTransferManager.resetChunkedSendMode()
    }

    /** Update chunked message settings */
    fun updateChunkedMessageSettings(
            maxChunkSize: Int,
            chunkDelay: Long,
            transferRetryTimeoutMs: Long
    ) {
        chunkwiseTransferManager.updateSettings(maxChunkSize, chunkDelay, transferRetryTimeoutMs)
    }

    /** Clean up resources */
    fun cleanup() {
        // Stop service watchdog
        mainHandler.removeCallbacks(serviceWatchdogRunnable)

        // Clean up chunked transfer manager resources
        chunkwiseTransferManager.cleanup()

        // Unregister service lifecycle receiver
        try {
            context.unregisterReceiver(serviceLifecycleReceiver)
        } catch (e: IllegalArgumentException) {
            // Receiver not registered
        }

        // If we're not in receive mode anymore, stop the service
        if (appState != AppState.RECEIVING) {
            val intent = Intent(context, CardEmulationService::class.java)
            Log.d(TAG, "Transfermanager.cleanup: Stopping CardEmulationService")
            context.stopService(intent)
        }
    }

    /** Get the NFC adapter */
    fun getNfcAdapter(): NfcAdapter? {
        return nfcAdapter
    }

    /** Set the current message for the CardEmulationService */
    fun setCardEmulationMessage(message: String) {
        CardEmulationService.instance?.messageToShare = message
    }

    /** Check if the transfer manager is retrying a transfer */
    fun isRetryingTransfer(): Boolean {
        return chunkwiseTransferManager.isRetryingTransfer
    }

    /** Start the service watchdog to ensure the service stays alive */
    private fun startServiceWatchdog() {
        mainHandler.postDelayed(serviceWatchdogRunnable, SERVICE_WATCHDOG_INTERVAL)
    }

    /** Restart the CardEmulationService */
    private fun restartCardEmulationService() {
        try {
            Log.d(TAG, "restartCardEmulationService: Restarting CardEmulationService")
            // First stop any existing service
            val stopIntent = Intent(context, CardEmulationService::class.java)
            Log.d(TAG, "restartCardEmulationService: Stopping CardEmulationService")
            context.stopService(stopIntent)

            // Wait a moment before starting again
            mainHandler.postDelayed(
                    {
                        val startIntent = Intent(context, CardEmulationService::class.java)
                        startIntent.action = Intent.ACTION_MAIN
                        Log.d(TAG, "restartCardEmulationService: Starting CardEmulationService")
                        context.startService(startIntent)

                        // Service start will trigger serviceLifecycleReceiver which calls initializeReceiveMode
                        // No need to explicitly call setupDataReceiver here
                    },
                    100
            )
        } catch (e: Exception) {
            Log.e(TAG, "Error restarting service: ${e.message}")
        }
    }

    /** Set the message to send */
    private fun setMessageToSend(message: String) {
        Log.d(TAG, "setMessageToSend: Setting message to send: $message")
        if (message.isEmpty()) {
            Log.d(TAG, "setMessageToSend: Trying to set empty message")
        }
        // Set the message in the service
        CardEmulationService.instance?.setMessageToSend(message)
        // Also set the message in the NdefProcessor
        ndefProcessor.setMessageToSend("ndef " + message)
    }

    /**
     * Set up NFC foreground dispatch
     */
    fun setupForegroundDispatch() {
        Log.d(TAG, "TransferManager setupForegroundDispatch")
        // Create a PendingIntent that will be used to deliver NFC intents to our activity
        val intent = Intent(context, context.javaClass).apply { addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP) }

        val flags =
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
                } else {
                    PendingIntent.FLAG_UPDATE_CURRENT
                }

        pendingIntent = PendingIntent.getActivity(context, 0, intent, flags)

        // Set up intent filters for NFC discovery
        val ndef =
                IntentFilter(NfcAdapter.ACTION_NDEF_DISCOVERED).apply {
                    try {
                        addDataType("*/*")
                    } catch (e: MalformedMimeTypeException) {
                        Log.e(TAG, "MalformedMimeTypeException", e)
                    }
                }

        val tech = IntentFilter(NfcAdapter.ACTION_TECH_DISCOVERED)
        val tag = IntentFilter(NfcAdapter.ACTION_TAG_DISCOVERED)

        intentFilters = arrayOf(ndef, tech, tag)

        // Set up tech lists
        techLists = arrayOf(arrayOf(IsoDep::class.java.name))
    }

    /**
     * Enable foreground dispatch
     */
    fun enableForegroundDispatch() {
        Log.d(TAG, "TransferManager enableForegroundDispatch")
        val nfcAdapterLocal = nfcAdapter
        if (nfcAdapterLocal != null) {
            try {
                nfcAdapterLocal.enableForegroundDispatch(context, pendingIntent, intentFilters, techLists)
                Log.d(TAG, "Foreground dispatch enabled")
            } catch (e: IllegalStateException) {
                Log.e(TAG, "Error enabling foreground dispatch", e)
            }
        }
    }

    /**
     * Disable foreground dispatch
     */
    fun disableForegroundDispatch() {
        Log.d(TAG, "TransferManager disableForegroundDispatch")
        val nfcAdapterLocal = nfcAdapter
        if (nfcAdapterLocal != null) {
            try {
                nfcAdapterLocal.disableForegroundDispatch(context)
                Log.d(TAG, "Foreground dispatch disabled")
            } catch (e: IllegalStateException) {
                Log.e(TAG, "Error disabling foreground dispatch", e)
            }
        }
    }

    companion object {
        // Service watchdog interval (5 minutes)
        private const val SERVICE_WATCHDOG_INTERVAL = 5 * 60 * 1000L
    }
}
