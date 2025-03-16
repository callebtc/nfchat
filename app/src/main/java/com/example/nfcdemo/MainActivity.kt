package com.example.nfcdemo

import android.app.Activity
import android.app.PendingIntent
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.nfc.NfcAdapter
import android.nfc.NfcAdapter.ReaderCallback
import android.nfc.Tag
import android.nfc.TagLostException
import android.nfc.tech.IsoDep
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.text.Editable
import android.text.TextWatcher
import android.util.Log
import android.util.Patterns
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.nfcdemo.data.MessageDbHelper
import com.example.nfcdemo.data.SettingsContract
import java.nio.charset.Charset
import java.io.IOException
import org.json.JSONObject
import java.util.UUID
import com.example.nfcdemo.nfc.MessageData
import com.example.nfcdemo.nfc.MessageProcessor
import com.example.nfcdemo.nfc.NfcProtocol

/**
 * Enum representing the different states of the app
 */
enum class AppState {
    IDLE,       // Initial state, not actively sending or receiving
    RECEIVING,  // Actively listening for incoming messages
    SENDING     // Actively sending a message
}

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

class MainActivity : Activity(), ReaderCallback {

    private val TAG = "MainActivity"
    private val mainHandler = Handler(Looper.getMainLooper())
    
    private var nfcAdapter: NfcAdapter? = null
    private lateinit var etMessage: EditText
    private lateinit var tvStatus: TextView
    private lateinit var btnSendMode: LinearLayout
    private lateinit var btnSettings: ImageView
    private lateinit var rvMessages: RecyclerView
    private lateinit var messageAdapter: MessageAdapter
    private lateinit var dbHelper: MessageDbHelper
    
    // Replace boolean flags with a single state enum
    private var appState = AppState.IDLE
    private var lastSentMessage = ""
    private var lastReceivedMessageId = "" // Track the ID of the last received message
    
    // Default message length limit before truncation
    private var messageLengthLimit = 200
    
    // Track if the app was opened via share intent
    private var openedViaShareIntent = false
    
    // For foreground dispatch
    private lateinit var pendingIntent: PendingIntent
    private lateinit var intentFilters: Array<IntentFilter>
    private lateinit var techLists: Array<Array<String>>
    
    // Chunked message transfer state
    private var chunkedTransferState = ChunkedTransferState.IDLE
    private var chunksToSend = mutableListOf<String>()
    private var currentChunkIndex = 0
    private var totalChunks = 0
    private var maxChunkSize = 500
    private var chunkDelay = 200L
    private var transferTimeout = 2 // in seconds
    private var acknowledgedChunks = mutableSetOf<Int>()
    private var chunkSendAttempts = mutableMapOf<Int, Int>()
    private val MAX_SEND_ATTEMPTS = 3
    
    // Transfer timeout handler
    private var transferTimeoutHandler: Handler? = null
    private var transferTimeoutRunnable: Runnable? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // Initialize database helper
        dbHelper = MessageDbHelper(this)

        etMessage = findViewById(R.id.etMessage)
        tvStatus = findViewById(R.id.tvStatus)
        btnSendMode = findViewById(R.id.btnSendMode)
        btnSettings = findViewById(R.id.btnSettings)
        rvMessages = findViewById(R.id.rvMessages)
        
        // Set up RecyclerView
        messageAdapter = MessageAdapter(this)
        rvMessages.layoutManager = LinearLayoutManager(this).apply {
            stackFromEnd = true // Messages appear from bottom
        }
        rvMessages.adapter = messageAdapter
        
        // Set the message length limit
        setMessageLengthLimit(messageLengthLimit)
        
        // Load chunked message settings
        loadChunkedMessageSettings()

        // Initialize NFC
        nfcAdapter = NfcAdapter.getDefaultAdapter(this)
        if (nfcAdapter == null) {
            tvStatus.text = getString(R.string.nfc_not_available)
            Toast.makeText(this, getString(R.string.nfc_not_available), Toast.LENGTH_LONG).show()
            btnSendMode.isEnabled = false
            return
        }
        
        // Set up NFC foreground dispatch system
        setupForegroundDispatch()

        // Disable send button initially
        btnSendMode.isEnabled = false
        findViewById<ImageView>(R.id.ivSendIcon).alpha = 0.5f

        // Add text watcher to enable/disable send button
        etMessage.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            
            override fun afterTextChanged(s: Editable?) {
                btnSendMode.isEnabled = !s.isNullOrEmpty()
                findViewById<ImageView>(R.id.ivSendIcon).alpha = if (!s.isNullOrEmpty()) 1.0f else 0.5f
            }
        })

        // Set up button click listeners
        setupClickListeners()
        
        // Check if we're starting with a share intent
        val isShareIntent = intent?.action == Intent.ACTION_SEND && intent.type?.startsWith("text/") == true
        
        // Handle incoming share intents first
        if (isShareIntent) {
            handleIncomingShareIntent(intent)
        }
        
        // Start in receive mode by default, but only if we're not handling a share intent
        if (!isShareIntent) {
            startInReceiveMode()
        }
    }
    
    private fun setupClickListeners() {
        // Send button
        btnSendMode.setOnClickListener {
            if (etMessage.text.toString().isEmpty()) {
                Toast.makeText(this, getString(R.string.enter_message_prompt), Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            
            // Store the message to send
            lastSentMessage = etMessage.text.toString()
            
            // Add the message to the chat as a sent message and save to database
            saveAndAddMessage(lastSentMessage, true)
            
            // Clear the input field after sending
            etMessage.text.clear()
            
            // Switch to send mode
            switchToSendMode()
        }
        
        // Settings button
        btnSettings.setOnClickListener {
            val intent = Intent(this, SettingsActivity::class.java)
            startActivity(intent)
        }
        
        // Status text view - toggle between send and receive modes
        tvStatus.setOnClickListener {
            toggleMode()
        }
    }
    
    /**
     * Toggle between send and receive modes when the status text is tapped
     */
    private fun toggleMode() {
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
                    Toast.makeText(this, getString(R.string.no_pending_message), Toast.LENGTH_SHORT).show()
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
    private fun switchToSendMode() {
        // Only proceed if there's a message to send
        if (lastSentMessage.isEmpty()) {
            Log.d(TAG, "No message to send, not switching to send mode")
            return
        }
        
        // First, stop any receive mode operations
        if (appState == AppState.RECEIVING) {
            // Stop the CardEmulationService
            val intent = Intent(this, CardEmulationService::class.java)
            stopService(intent)
        }
        
        // Update state
        appState = AppState.SENDING
        updateModeIndicators()
        tvStatus.text = getString(R.string.status_send_mode)
        
        // Enable reader mode for sending data
        enableReaderMode()
        
        Log.d(TAG, "Switched to send mode, ready to send: $lastSentMessage")
    }
    
    /**
     * Switch to receive mode
     */
    private fun switchToReceiveMode() {
        // First, disable reader mode if we were in send mode
        if (appState == AppState.SENDING) {
            disableReaderMode()
        }
        
        // Update state
        appState = AppState.RECEIVING
        updateModeIndicators()
        tvStatus.text = getString(R.string.status_receive_mode)
        
        // Start the CardEmulationService
        val intent = Intent(this, CardEmulationService::class.java)
        startService(intent)
        
        // Set up the message and listener
        mainHandler.postDelayed({
            CardEmulationService.instance?.messageToShare = etMessage.text.toString()
            setupDataReceiver()
        }, 100)
        
        Log.d(TAG, "Switched to receive mode")
    }
    
    /**
     * Set the message length limit before truncation
     * @param limit The maximum number of characters to display before truncating
     */
    fun setMessageLengthLimit(limit: Int) {
        messageLengthLimit = limit
        messageAdapter.setMessageLengthLimit(limit)
    }
    
    /**
     * Get the current message length limit
     * @return The current message length limit
     */
    fun getMessageLengthLimit(): Int {
        return messageLengthLimit
    }
    
    private fun setupForegroundDispatch() {
        // Create a PendingIntent that will be used to deliver NFC intents to our activity
        val intent = Intent(this, javaClass).apply {
            addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }
        
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }
        
        pendingIntent = PendingIntent.getActivity(this, 0, intent, flags)
        
        // Set up intent filters for NFC discovery
        val ndef = IntentFilter(NfcAdapter.ACTION_NDEF_DISCOVERED).apply {
            try {
                addDataType("*/*")
            } catch (e: IntentFilter.MalformedMimeTypeException) {
                Log.e(TAG, "MalformedMimeTypeException", e)
            }
        }
        
        val tech = IntentFilter(NfcAdapter.ACTION_TECH_DISCOVERED)
        val tag = IntentFilter(NfcAdapter.ACTION_TAG_DISCOVERED)
        
        intentFilters = arrayOf(ndef, tech, tag)
        
        // Set up tech lists
        techLists = arrayOf(arrayOf(IsoDep::class.java.name))
    }
    
    private fun enableForegroundDispatch() {
        if (nfcAdapter != null) {
            try {
                nfcAdapter?.enableForegroundDispatch(this, pendingIntent, intentFilters, techLists)
                Log.d(TAG, "Foreground dispatch enabled")
            } catch (e: IllegalStateException) {
                Log.e(TAG, "Error enabling foreground dispatch", e)
            }
        }
    }
    
    private fun disableForegroundDispatch() {
        if (nfcAdapter != null) {
            try {
                nfcAdapter?.disableForegroundDispatch(this)
                Log.d(TAG, "Foreground dispatch disabled")
            } catch (e: IllegalStateException) {
                Log.e(TAG, "Error disabling foreground dispatch", e)
            }
        }
    }
    
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        Log.d(TAG, "New intent received: ${intent.action}")
        
        // Save the new intent to replace the old one
        setIntent(intent)
        
        // Handle share intents
        if (intent.action == Intent.ACTION_SEND && intent.type?.startsWith("text/") == true) {
            Log.d(TAG, "Share intent received while app is running")
            
            // Cancel any pending receive mode initialization
            mainHandler.removeCallbacksAndMessages(null)
            
            // If we're in send mode, finish the current operation first
            if (appState == AppState.SENDING) {
                // If we're already in send mode, we need to wait until the current operation is complete
                Toast.makeText(this, getString(R.string.wait_for_current_operation), Toast.LENGTH_SHORT).show()
                return
            }
            
            // Handle the share intent
            handleIncomingShareIntent(intent)
            return
        }
        
        // Handle the NFC intent if we're not in reader mode
        if (appState != AppState.SENDING && (intent.action == NfcAdapter.ACTION_TECH_DISCOVERED ||
            intent.action == NfcAdapter.ACTION_TAG_DISCOVERED ||
            intent.action == NfcAdapter.ACTION_NDEF_DISCOVERED)) {
            
            val tag = intent.getParcelableExtra<Tag>(NfcAdapter.EXTRA_TAG)
            tag?.let {
                // Process the tag if we're not already in reader mode
                if (appState != AppState.SENDING) {
                    onTagDiscovered(it)
                }
            }
        }
    }
    
    private fun setupDataReceiver() {
        // This is a critical function to ensure UI updates happen
        CardEmulationService.instance?.onDataReceivedListener = { messageData ->
            Log.d(TAG, "Data received in MainActivity: ${messageData.content}")
            
            // Check if this is a duplicate message based on ID
            if (messageData.id != lastReceivedMessageId) {
                lastReceivedMessageId = messageData.id
                
                mainHandler.post {
                    // Update UI on the main thread and save to database
                    saveAndAddMessage(messageData.content, false)
                    
                    // Show "Ready to receive" status instead of "Message received"
                    tvStatus.text = getString(R.string.status_receive_mode)
                    
                    // Vibrate on message received
                    vibrate(200)
                    
                    // Process the message (e.g., open links)
                    MessageProcessor.processReceivedMessage(this, messageData, dbHelper)
                }
            } else {
                Log.d(TAG, "Duplicate message received (same ID), ignoring: ${messageData.id}")
            }
        }
        
        // Set up chunk progress listener
        CardEmulationService.instance?.onChunkProgressListener = { receivedChunks, totalChunks ->
            // Cancel any existing timeout
            cancelTransferTimeout()
            
            // Start a new timeout
            startTransferTimeout()
            
            mainHandler.post {
                if (receivedChunks > 0) {
                    tvStatus.text = getString(R.string.receiving_chunk, receivedChunks, totalChunks)
                }
            }
        }
        
        // Set up chunk error listener
        CardEmulationService.instance?.onChunkErrorListener = { errorMessage ->
            // Cancel any existing timeout
            cancelTransferTimeout()
            
            mainHandler.post {
                Log.e(TAG, "Chunk error: $errorMessage")
                tvStatus.text = getString(R.string.chunked_transfer_failed)
                Toast.makeText(this, getString(R.string.chunked_transfer_error_receiver, errorMessage), Toast.LENGTH_LONG).show()
                
                // Make sure we're in receive mode to recover from the error
                if (appState != AppState.RECEIVING) {
                    switchToReceiveMode()
                }
            }
        }
    }
    
    /**
     * Start a timeout for chunked transfers
     */
    private fun startTransferTimeout() {
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
                runOnUiThread {
                    tvStatus.text = getString(R.string.chunked_transfer_failed)
                    Toast.makeText(this, getString(R.string.chunked_transfer_timeout), Toast.LENGTH_LONG).show()
                    resetChunkedSendMode()
                    switchToReceiveMode()
                }
            } else if (CardEmulationService.instance?.isReceivingChunkedMessage() == true) {
                // Handle timeout on receiver side
                CardEmulationService.instance?.resetChunkedMessageState()
                runOnUiThread {
                    tvStatus.text = getString(R.string.chunked_transfer_failed)
                    Toast.makeText(this, getString(R.string.chunked_transfer_timeout), Toast.LENGTH_LONG).show()
                }
            }
        }
        
        // Schedule the timeout
        transferTimeoutHandler?.postDelayed(transferTimeoutRunnable!!, transferTimeout * 1000L)
    }
    
    /**
     * Cancel any active transfer timeout
     */
    private fun cancelTransferTimeout() {
        transferTimeoutRunnable?.let {
            transferTimeoutHandler?.removeCallbacks(it)
            transferTimeoutRunnable = null
        }
    }
    
    /**
     * Automatically open links in a message if auto-open links is enabled
     */
    private fun openLinksInMessage(message: String) {
        // Check if auto-open links is enabled
        if (!dbHelper.getBooleanSetting(SettingsContract.SettingsEntry.KEY_AUTO_OPEN_LINKS, true)) {
            return
        }
        
        // Find URLs in the message
        val matcher = Patterns.WEB_URL.matcher(message)
        if (matcher.find()) {
            val url = matcher.group()
            if (url != null) {
                // Prepend http:// if the URL doesn't have a scheme
                val fullUrl = if (!url.startsWith("http://") && !url.startsWith("https://")) {
                    "http://$url"
                } else {
                    url
                }
                
                // Check if we should use the internal browser
                val useInternalBrowser = dbHelper.getBooleanSetting(
                    SettingsContract.SettingsEntry.KEY_USE_INTERNAL_BROWSER, 
                    false
                )
                
                if (useInternalBrowser) {
                    // Check if there's already a WebViewActivity open
                    val currentWebView = WebViewActivityManager.getCurrentWebViewActivity()
                    if (currentWebView != null) {
                        // Close the existing WebView first
                        currentWebView.finish()
                        
                        // Small delay to ensure the previous activity is properly closed
                        mainHandler.postDelayed({
                            // Open the URL in a new WebView
                            val intent = Intent(this, WebViewActivity::class.java)
                            intent.putExtra(WebViewActivity.EXTRA_URL, fullUrl)
                            startActivity(intent)
                        }, 100)
                    } else {
                        // Open the URL in an internal WebView
                        val intent = Intent(this, WebViewActivity::class.java)
                        intent.putExtra(WebViewActivity.EXTRA_URL, fullUrl)
                        startActivity(intent)
                    }
                } else {
                    // Open the URL in an external browser
                    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(fullUrl))
                    startActivity(intent)
                }
            }
        }
    }
    
    private fun updateModeIndicators() {
        btnSendMode.isSelected = appState == AppState.SENDING
    }

    override fun onResume() {
        super.onResume()
        
        // Enable foreground dispatch to intercept all NFC intents
        enableForegroundDispatch()
        
        if (appState == AppState.SENDING) {
            enableReaderMode()
        }
        
        // Update the service with the latest message if in receive mode
        if (appState == AppState.RECEIVING) {
            CardEmulationService.instance?.messageToShare = etMessage.text.toString()
            // Re-establish the data receiver connection
            setupDataReceiver()
        }
        
        // Reload chunked message settings in case they were changed
        loadChunkedMessageSettings()
    }

    override fun onPause() {
        super.onPause()
        disableReaderMode()
        disableForegroundDispatch()
    }
    
    override fun onDestroy() {
        super.onDestroy()
        // Clean up database resources
        messageAdapter.cleanup()
        dbHelper.close()
        // If we're not in receive mode anymore, stop the service
        if (appState != AppState.RECEIVING) {
            val intent = Intent(this, CardEmulationService::class.java)
            stopService(intent)
        }
    }

    private fun enableReaderMode() {
        nfcAdapter?.enableReaderMode(this, this,
            NfcAdapter.FLAG_READER_NFC_A or
                    NfcAdapter.FLAG_READER_SKIP_NDEF_CHECK,
            null)
    }

    private fun disableReaderMode() {
        nfcAdapter?.disableReaderMode(this)
    }

    override fun onTagDiscovered(tag: Tag) {
        val isoDep = IsoDep.get(tag) ?: return
        
        try {
            isoDep.connect()
            Log.d(TAG, "Connected to tag")
            
            // Select our AID
            val selectApdu = NfcProtocol.buildSelectApdu(NfcProtocol.DEFAULT_AID)
            val result = isoDep.transceive(selectApdu)
            
            if (!NfcProtocol.isSuccess(result)) {
                Log.e(TAG, "Error selecting AID: ${NfcProtocol.byteArrayToHex(result)}")
                return
            }
            
            when (appState) {
                AppState.SENDING -> {
                    if (chunkedTransferState == ChunkedTransferState.SENDING_CHUNKS) {
                        // Handle chunked message sending
                        handleChunkedMessageSending(isoDep)
                    } else {
                        // Check if the message is too long and needs to be chunked
                        val message = lastSentMessage
                        if (message.length > maxChunkSize) {
                            // Prepare for chunked sending
                            prepareChunkedMessageSending(message)
                            // Start chunked sending
                            handleChunkedMessageSending(isoDep)
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
            handleTagCommunicationError(e)
        } catch (e: TagLostException) {
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
                    
                    runOnUiThread {
                        // Add the message to the chat and save to database
                        saveAndAddMessage(messageData.content, false)
                        tvStatus.text = getString(R.string.status_receive_mode)
                        
                        // Vibrate on message received
                        vibrate(200)
                        
                        // Process the message (e.g., open links)
                        MessageProcessor.processReceivedMessage(this, messageData, dbHelper)
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
     * Handle communication errors with the NFC tag
     */
    private fun handleTagCommunicationError(e: IOException) {
        Log.e(TAG, "Error communicating with tag: ${e.message}")
        runOnUiThread {
            tvStatus.text = "Communication error: ${e.message}"
            
            // If we were in chunked send mode, show a more specific error message
            if (chunkedTransferState == ChunkedTransferState.SENDING_CHUNKS) {
                Toast.makeText(this, getString(R.string.chunked_transfer_error, e.message), Toast.LENGTH_LONG).show()
                resetChunkedSendMode()
                // Switch to receive mode to recover from error
                switchToReceiveMode()
            }
        }
    }
    
    /**
     * Handle tag lost errors
     */
    private fun handleTagLostError(e: TagLostException) {
        Log.e(TAG, "Tag lost: ${e.message}")
        runOnUiThread {
            tvStatus.text = "Tag connection lost. Try again."
            
            // If we were in chunked send mode, show a more specific error message
            if (chunkedTransferState == ChunkedTransferState.SENDING_CHUNKS) {
                Toast.makeText(this, getString(R.string.chunked_transfer_error, "Connection lost"), Toast.LENGTH_LONG).show()
                resetChunkedSendMode()
                // Switch to receive mode to recover from error
                switchToReceiveMode()
            }
        }
    }
    
    /**
     * Prepare for chunked message sending by splitting the message into chunks
     */
    private fun prepareChunkedMessageSending(message: String) {
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
    }
    
    /**
     * Handle the chunked message sending process
     */
    private fun handleChunkedMessageSending(isoDep: IsoDep) {
        if (chunkedTransferState != ChunkedTransferState.SENDING_CHUNKS || chunksToSend.isEmpty()) {
            Log.e(TAG, "Attempted chunked sending but not properly prepared")
            return
        }
        
        try {
            // If we're just starting (no acknowledged chunks), send the initialization command
            if (acknowledgedChunks.isEmpty()) {
                if (!initializeChunkedTransfer(isoDep)) {
                    handleChunkedTransferError("Failed to initialize chunked transfer")
                    return
                }
            }
            
            // Send chunks until all are acknowledged or max attempts reached
            val transferResult = sendAllChunks(isoDep)
            
            // Cancel the transfer timeout since we're done
            cancelTransferTimeout()
            
            // Handle the result of the transfer
            if (transferResult) {
                completeChunkedTransfer(isoDep)
            } else {
                handleChunkedTransferError(getString(R.string.chunked_transfer_incomplete))
            }
        } catch (e: Exception) {
            // Cancel the transfer timeout
            cancelTransferTimeout()
            
            Log.e(TAG, "Error during chunked sending: ${e.message}")
            handleChunkedTransferError(e.message ?: "Unknown error")
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
        runOnUiThread {
            tvStatus.text = getString(R.string.sending_chunk, 0, totalChunks)
        }
        
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
            runOnUiThread {
                tvStatus.text = getString(R.string.sending_chunk, acknowledgedChunks.size + 1, totalChunks)
            }
            
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
    private fun completeChunkedTransfer(isoDep: IsoDep) {
        // Update state to completing
        chunkedTransferState = ChunkedTransferState.COMPLETING
        
        val completeCommand = NfcProtocol.createChunkCompleteCommand()
        val completeResult = isoDep.transceive(completeCommand)
        
        if (NfcProtocol.isSuccess(completeResult)) {
            Log.d(TAG, "Chunked transfer completed successfully")
            
            runOnUiThread {
                tvStatus.text = getString(R.string.chunked_transfer_complete)
                
                // Mark the last sent message as delivered
                val lastPosition = messageAdapter.itemCount - 1
                messageAdapter.markMessageAsDelivered(lastPosition)
                
                // Vibrate on message sent
                vibrate(200)
                
                // Check if we should close the app after sending a shared message
                if (handlePostSendActions()) {
                    return@runOnUiThread
                }
                
                // Clear the sent message to prevent re-sending
                lastSentMessage = ""
                
                // Reset chunked send mode
                resetChunkedSendMode()
                
                // Switch to receive mode automatically
                switchToReceiveMode()
                scrollToBottom()
            }
        } else {
            Log.e(TAG, "Failed to complete chunked transfer")
            chunkedTransferState = ChunkedTransferState.ERROR
            handleChunkedTransferError(getString(R.string.chunked_transfer_failed_message))
        }
    }
    
    /**
     * Handle post-send actions like closing the app if needed
     * @return true if the app is closing, false otherwise
     */
    private fun handlePostSendActions(): Boolean {
        // Check if we should close the app after sending a shared message
        if (openedViaShareIntent) {
            val closeAfterSharedSend = dbHelper.getBooleanSetting(
                SettingsContract.SettingsEntry.KEY_CLOSE_AFTER_SHARED_SEND, 
                false
            )
            
            if (closeAfterSharedSend) {
                // Show a toast to inform the user
                Toast.makeText(this, getString(R.string.message_sent_closing), Toast.LENGTH_SHORT).show()
                
                // Close the app after a short delay
                mainHandler.postDelayed({
                    finish()
                }, 1000)
                return true
            }
        }
        return false
    }
    
    /**
     * Handle errors during chunked transfer
     */
    private fun handleChunkedTransferError(errorMessage: String) {
        Log.e(TAG, "Chunked transfer error: $errorMessage")
        chunkedTransferState = ChunkedTransferState.ERROR
        runOnUiThread {
            tvStatus.text = getString(R.string.chunked_transfer_failed)
            Toast.makeText(this, getString(R.string.chunked_transfer_error, errorMessage), Toast.LENGTH_LONG).show()
            resetChunkedSendMode()
            // Switch to receive mode to recover from error
            switchToReceiveMode()
        }
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
    private fun resetChunkedSendMode() {
        // Cancel any active transfer timeout
        cancelTransferTimeout()
        
        chunkedTransferState = ChunkedTransferState.IDLE
        chunksToSend.clear()
        acknowledgedChunks.clear()
        chunkSendAttempts.clear()
        currentChunkIndex = 0
        totalChunks = 0
    }

    private fun scrollToBottom() {
        rvMessages.post {
            rvMessages.smoothScrollToPosition(messageAdapter.itemCount - 1)
        }
    }
    
    private fun vibrate(duration: Long) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val vibratorManager = getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
                val vibrator = vibratorManager.defaultVibrator
                vibrator.vibrate(VibrationEffect.createOneShot(duration, VibrationEffect.DEFAULT_AMPLITUDE))
            } else {
                @Suppress("DEPRECATION")
                val vibrator = getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    vibrator.vibrate(VibrationEffect.createOneShot(duration, VibrationEffect.DEFAULT_AMPLITUDE))
                } else {
                    @Suppress("DEPRECATION")
                    vibrator.vibrate(duration)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error vibrating: ${e.message}")
        }
    }

    private fun handleIncomingShareIntent(intent: Intent?) {
        // Check if this activity was started from a share intent
        if (intent?.action == Intent.ACTION_SEND && intent.type?.startsWith("text/") == true) {
            Log.d(TAG, "Handling share intent: ${intent.action}, type: ${intent.type}")
            
            // Mark that we were opened via share intent
            openedViaShareIntent = true
            
            // If we're in receive mode, stop it first
            if (appState == AppState.RECEIVING) {
                // Stop the CardEmulationService
                val serviceIntent = Intent(this, CardEmulationService::class.java)
                stopService(serviceIntent)
                
                // Update state
                appState = AppState.IDLE
                updateModeIndicators()
            }
            
            // Extract the shared text
            val sharedText = intent.getStringExtra(Intent.EXTRA_TEXT)
            if (!sharedText.isNullOrEmpty()) {
                Log.d(TAG, "Shared text received: ${sharedText.take(50)}${if (sharedText.length > 50) "..." else ""}")
                
                // Set the shared text in the message field
                etMessage.setText(sharedText)
                
                // Check if auto-send is enabled
                val autoSendShared = dbHelper.getBooleanSetting(
                    SettingsContract.SettingsEntry.KEY_AUTO_SEND_SHARED, 
                    true
                )
                
                if (autoSendShared) {
                    // Show a toast to inform the user
                    Toast.makeText(this, getString(R.string.text_received_auto_send), Toast.LENGTH_LONG).show()
                    
                    // Automatically prepare to send the shared text
                    mainHandler.postDelayed({
                        // Only proceed if the text is still there (user hasn't cleared it)
                        if (!etMessage.text.isNullOrEmpty()) {
                            Log.d(TAG, "Auto-sending shared text")
                            
                            // Store the message to send
                            lastSentMessage = etMessage.text.toString()
                            
                            // Add the message to the chat as a sent message and save to database
                            saveAndAddMessage(lastSentMessage, true)
                            
                            // Clear the input field after sending
                            etMessage.text.clear()
                            
                            // Switch to send mode
                            switchToSendMode()
                        } else {
                            Log.d(TAG, "Text field is empty, not auto-sending")
                        }
                    }, 500) // Short delay to ensure UI is ready
                } else {
                    // Just show a toast that the text is ready to send
                    Toast.makeText(this, getString(R.string.text_received_manual_send), Toast.LENGTH_LONG).show()
                    
                    // We don't save the message yet - it will be saved when the user presses send
                    // This avoids duplicate messages in the database
                    // The message is already in the input field, so the user can edit it before sending
                }
            } else {
                Log.d(TAG, "Shared text is null or empty")
            }
        } else {
            Log.d(TAG, "Not a share intent or wrong type: ${intent?.action}, type: ${intent?.type}")
        }
    }

    private fun startInReceiveMode() {
        // Reset the share intent flag since we're starting normally
        openedViaShareIntent = false
        
        // Use the new switchToReceiveMode method for consistency
        mainHandler.postDelayed({
            // Only switch to receive mode if we're not already in send mode
            if (appState != AppState.SENDING) {
                switchToReceiveMode()
            }
        }, 500)
    }

    /**
     * Helper method to ensure consistent message saving
     * This method ensures that all messages are properly stored in the database
     * @param messageText The message text to save
     * @param isSent Whether the message is sent by the user
     * @return The position of the added message in the adapter
     */
    private fun saveAndAddMessage(messageText: String, isSent: Boolean): Int {
        if (messageText.isBlank()) return -1
        
        // Add the message to the adapter (which saves to the database)
        val position = if (isSent) {
            messageAdapter.addSentMessage(messageText)
        } else {
            messageAdapter.addReceivedMessage(messageText)
            messageAdapter.itemCount - 1
        }
        
        // Scroll to show the new message
        scrollToBottom()
        
        return position
    }

    /**
     * Load chunked message settings from the database
     */
    private fun loadChunkedMessageSettings() {
        maxChunkSize = dbHelper.getSetting(
            SettingsContract.SettingsEntry.KEY_MAX_CHUNK_SIZE,
            "500"
        ).toInt()
        
        chunkDelay = dbHelper.getSetting(
            SettingsContract.SettingsEntry.KEY_CHUNK_DELAY,
            "200"
        ).toLong()
        
        transferTimeout = dbHelper.getSetting(
            SettingsContract.SettingsEntry.KEY_TRANSFER_TIMEOUT,
            "2"
        ).toInt()
        
        Log.d(TAG, "Loaded chunked message settings: maxChunkSize=$maxChunkSize, chunkDelay=$chunkDelay, transferTimeout=$transferTimeout")
    }

    /**
     * Send a regular (non-chunked) message
     */
    private fun sendRegularMessage(isoDep: IsoDep, message: String) {
        // Create a MessageData object with the message content and a unique ID
        val messageData = MessageData(message)
        val jsonMessage = messageData.toJson()
        
        val sendCommand = NfcProtocol.createSendDataCommand(jsonMessage)
        val sendResult = isoDep.transceive(sendCommand)
        
        if (NfcProtocol.isSuccess(sendResult)) {
            runOnUiThread {
                tvStatus.text = getString(R.string.message_sent)
                
                // Mark the last sent message as delivered
                val lastPosition = messageAdapter.itemCount - 1
                messageAdapter.markMessageAsDelivered(lastPosition)
                
                // Vibrate on message sent
                vibrate(200)
                
                // Check if we should close the app after sending a shared message
                if (handlePostSendActions()) {
                    return@runOnUiThread
                }
                
                // Clear the sent message to prevent re-sending
                lastSentMessage = ""
                
                // Switch to receive mode automatically
                switchToReceiveMode()
                scrollToBottom()
            }
        } else {
            runOnUiThread {
                tvStatus.text = getString(R.string.message_send_failed)
                // Switch to receive mode to recover from error
                switchToReceiveMode()
            }
        }
    }
}
