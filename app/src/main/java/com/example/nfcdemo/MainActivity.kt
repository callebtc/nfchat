package com.example.nfcdemo

import android.app.Activity
import android.app.PendingIntent
import android.content.BroadcastReceiver
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
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.nfcdemo.data.AppConstants
import com.example.nfcdemo.data.MessageDbHelper
import com.example.nfcdemo.data.SettingsContract
import com.example.nfcdemo.handlers.CashuHandler
import com.example.nfcdemo.nfc.ChunkwiseTransferManager
import com.example.nfcdemo.nfc.ChunkedTransferState
import com.example.nfcdemo.nfc.MessageData
import com.example.nfcdemo.nfc.MessageProcessor
import com.example.nfcdemo.nfc.NfcProtocol
import com.example.nfcdemo.nfc.TransferManager
import com.example.nfcdemo.ui.AnimationUtils
import java.io.IOException
import java.nio.charset.Charset
import java.util.UUID
import org.json.JSONObject
import com.example.nfcdemo.handlers.LinkHandler
import com.example.nfcdemo.handlers.MessageHandlerManager
import com.example.nfcdemo.CardEmulationService

/**
 * Enum representing the different states of the app
 */
enum class AppState {
    IDLE,       // Initial state, not actively sending or receiving
    RECEIVING,  // Actively listening for incoming messages
    SENDING     // Actively sending a message
}

class MainActivity : Activity(), ReaderCallback, IntentManager.MessageSaveCallback {

    private val TAG = "MainActivity"
    private val mainHandler = Handler(Looper.getMainLooper())
    
    private lateinit var etMessage: EditText
    private lateinit var tvStatus: TextView
    private lateinit var btnSendMode: LinearLayout
    private lateinit var btnSettings: ImageView
    private lateinit var rvMessages: RecyclerView
    private lateinit var messageAdapter: MessageAdapter
    private lateinit var dbHelper: MessageDbHelper
    private lateinit var chunkProgressBar: ProgressBar
    
    // Replace boolean flags with a single state enum
    private var appState = AppState.IDLE
    private var lastSentMessage = ""
    private var lastReceivedMessageId = "" // Track the ID of the last received message
    
    // Default message length limit before truncation
    private var messageLengthLimit = AppConstants.DefaultSettings.MESSAGE_LENGTH_LIMIT
    
    // For foreground dispatch
    private lateinit var pendingIntent: PendingIntent
    private lateinit var intentFilters: Array<IntentFilter>
    private lateinit var techLists: Array<Array<String>>
    
    // Managers
    private lateinit var transferManager: TransferManager
    private lateinit var intentManager: IntentManager

    private var backgroundNfcEnabled = true
    private val settingsChangeReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            Log.d(TAG, "MainActivity settingsChangeReceiver onReceive")
            if (intent.action == SettingsActivity.ACTION_BACKGROUND_NFC_SETTING_CHANGED) {
                backgroundNfcEnabled = intent.getBooleanExtra(SettingsActivity.EXTRA_BACKGROUND_NFC_ENABLED, true)
                intentManager.setBackgroundNfcEnabled(backgroundNfcEnabled)
                Log.d(TAG, "Background NFC setting changed: enabled=$backgroundNfcEnabled")
            }
        }
    }

    // Service lifecycle receiver
    private val serviceLifecycleReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                CardEmulationService.ACTION_SERVICE_STARTED -> {
                    Log.d(TAG, "Received SERVICE_STARTED broadcast")
                    
                    // Check if the service is running in degraded mode
                    val degradedMode = intent.getBooleanExtra("degraded_mode", false)
                    if (degradedMode) {
                        Log.d(TAG, "Service is running in degraded mode")
                        // Show a toast to inform the user
                        Toast.makeText(
                            context, 
                            "NFC service is running in degraded mode. Messages may be lost if the app is closed.",
                            Toast.LENGTH_LONG
                        ).show()
                    }
                    
                    // If we're in receive mode, ensure the service has our listeners
                    if (appState == AppState.RECEIVING) {
                        mainHandler.postDelayed({
                            transferManager.setupDataReceiver()
                            transferManager.setCardEmulationMessage(etMessage.text.toString())
                        }, 100)
                    }
                }
                CardEmulationService.ACTION_SERVICE_DESTROYED -> {
                    Log.d(TAG, "Received SERVICE_DESTROYED broadcast, restarting service")
                    if (appState == AppState.RECEIVING) {
                        // Let the transfer manager handle restarting the service
                        mainHandler.postDelayed({
                            transferManager.switchToReceiveMode()
                        }, 200)
                    }
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        Log.d(TAG, "MainActivity onCreate")
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // Initialize database helper
        dbHelper = MessageDbHelper(this)

        // Initialize message handlers
        initializeMessageHandlers()

        etMessage = findViewById(R.id.etMessage)
        tvStatus = findViewById(R.id.tvStatus)
        btnSendMode = findViewById(R.id.btnSendMode)
        btnSettings = findViewById(R.id.btnSettings)
        rvMessages = findViewById(R.id.rvMessages)
        chunkProgressBar = findViewById(R.id.chunkProgressBar)
        
        // Set up RecyclerView
        messageAdapter = MessageAdapter(this)
        rvMessages.layoutManager = LinearLayoutManager(this).apply {
            stackFromEnd = true // Messages appear from bottom
        }
        rvMessages.adapter = messageAdapter
        
        // Load messages from database
        loadMessagesFromDatabase()
        
        // Set the message length limit
        setMessageLengthLimit(messageLengthLimit)
        
        // Initialize managers
        transferManager = TransferManager(this)
        setupTransferManagerCallbacks()
        
        intentManager = IntentManager(this, transferManager, mainHandler, dbHelper)
        setupIntentManagerCallbacks()

        // Check if NFC is available
        if (transferManager.getNfcAdapter() == null) {
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
        
        // Check if we're starting with an NFC intent
        val isNfcIntent = intent?.action == NfcAdapter.ACTION_TECH_DISCOVERED ||
                          intent?.action == NfcAdapter.ACTION_TAG_DISCOVERED ||
                          intent?.action == NfcAdapter.ACTION_NDEF_DISCOVERED
        
        // Check if we're being brought to the foreground from a background message receive
        val fromBackgroundReceive = intent?.getBooleanExtra("from_background_receive", false) ?: false
        
        // Handle incoming share intents first
        if (isShareIntent) {
            intentManager.handleIncomingShareIntent(intent, appState, etMessage)
        }
        // Handle NFC intents if the app was launched by an NFC discovery
        else if (isNfcIntent) {
            Log.d(TAG, "App launched via NFC intent: ${intent?.action}")
            // Start in receive mode since we were launched by an NFC discovery
            intentManager.startInReceiveMode(appState, etMessage)
            // Process the NFC intent
            intent?.let { intentManager.handleNfcIntent(it, appState) }
        }
        // Handle being brought to the foreground from a background message receive
        else if (fromBackgroundReceive) {
            Log.d(TAG, "fromBackgroundReceive: App brought to foreground from background message receive")
            // Make sure we're in receive mode
            intentManager.startInReceiveMode(appState, etMessage)
            // Show a toast to inform the user
            Toast.makeText(this, getString(R.string.app_launched_by_nfc), Toast.LENGTH_SHORT).show()
        }
        // Start in receive mode by default, but only if we're not handling a share or NFC intent
        else if (!isShareIntent) {
            intentManager.startInReceiveMode(appState, etMessage)
        }
        
        // Restore state if available
        if (savedInstanceState != null) {
            restoreAppState(savedInstanceState)
        }

        // Initialize the background NFC setting
        backgroundNfcEnabled = dbHelper.getBooleanSetting(
            SettingsContract.SettingsEntry.KEY_ENABLE_BACKGROUND_NFC,
            AppConstants.DefaultSettings.ENABLE_BACKGROUND_NFC
        )
        intentManager.setBackgroundNfcEnabled(backgroundNfcEnabled)
        Log.d(TAG, "Initial background NFC setting: enabled=$backgroundNfcEnabled")
    }
    
    private fun setupClickListeners() {
        Log.d(TAG, "MainActivity setupClickListeners")
        // Send button
        btnSendMode.setOnClickListener {
            if (etMessage.text.toString().isEmpty()) {
                Toast.makeText(this, getString(R.string.enter_message_prompt), Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            
            // Store the message to send
            lastSentMessage = etMessage.text.toString()
            transferManager.setLastSentMessage(lastSentMessage)
            
            // Add the message to the chat as a sent message and save to database
            saveAndAddMessage(lastSentMessage, true)
            
            // Clear the input field after sending
            etMessage.text.clear()
            
            // Switch to send mode
            transferManager.switchToSendMode()
        }
        
        // Settings button
        btnSettings.setOnClickListener {
            val intent = Intent(this, SettingsActivity::class.java)
            startActivity(intent)
        }
        
        // Status text view - toggle between send and receive modes
        tvStatus.setOnClickListener {
            transferManager.toggleMode()
        }
    }
    
    /**
     * Set up callbacks for the transfer manager
     */
    private fun setupTransferManagerCallbacks() {
        Log.d(TAG, "MainActivity setupTransferManagerCallbacks")
        transferManager.onAppStateChanged = { newState ->
            appState = newState
            updateModeIndicators()
            
            // Update the message adapter with the new app state
            messageAdapter.updateAppState(newState)
        }
        
        transferManager.onStatusChanged = { status ->
            tvStatus.text = status
        }
        
        transferManager.onMessageSent = { position ->
            // Mark the message as delivered
            val lastPosition = if (position == -1) messageAdapter.itemCount - 1 else position
            messageAdapter.markMessageAsDelivered(lastPosition)
            
            // Check if we should close the app after sending a shared message
            val shouldClose = intentManager.handlePostSendActions()
            if (!shouldClose) {
                // Clear the sent message to prevent re-sending
                lastSentMessage = ""
                
                // Scroll to bottom
                scrollToBottom()
            }
        }
        
        transferManager.onMessageReceived = { messageData, _ ->
            // Update UI on the main thread and save to database
            saveAndAddMessage(messageData.content, false)
            
            // Process the message (e.g., open links)
            MessageProcessor.processReceivedMessage(this, messageData, dbHelper)
        }
        
        transferManager.onVibrate = { duration ->
            vibrate(duration)
        }
        
        // Add callbacks for chunk transfer progress
        transferManager.onChunkTransferStarted = { totalChunks ->
            mainHandler.post {
                // Show the progress bar
                AnimationUtils.showProgressBar(chunkProgressBar)
                // Reset progress to 0
                AnimationUtils.updateProgressBar(chunkProgressBar, 0)
                Log.d(TAG, "Chunk transfer started: $totalChunks chunks total")
            }
        }
        
        transferManager.onChunkTransferProgress = { currentChunk, totalChunks ->
            mainHandler.post {
                // Calculate progress percentage
                val progress = ((currentChunk.toFloat() / totalChunks) * 100).toInt()
                // Update the progress bar
                AnimationUtils.updateProgressBar(chunkProgressBar, progress)
                Log.d(TAG, "Chunk transfer progress: $currentChunk/$totalChunks ($progress%)")
            }
        }
        
        transferManager.onChunkTransferCompleted = {
            mainHandler.post {
                // Hide the progress bar
                AnimationUtils.hideProgressBar(chunkProgressBar)
                Log.d(TAG, "Chunk transfer completed")
            }
        }
    }
    
    /**
     * Set up callbacks for the intent manager
     */
    private fun setupIntentManagerCallbacks() {
        intentManager.messageSaveCallback = this
        
        intentManager.onOpenedViaShareIntentChanged = { opened ->
            // Update any UI elements that depend on the openedViaShareIntent state
        }
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
        Log.d(TAG, "MainActivity setupForegroundDispatch")
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
        Log.d(TAG, "MainActivity enableForegroundDispatch")
        val nfcAdapter = transferManager.getNfcAdapter()
        if (nfcAdapter != null) {
            try {
                nfcAdapter.enableForegroundDispatch(this, pendingIntent, intentFilters, techLists)
                Log.d(TAG, "Foreground dispatch enabled")
            } catch (e: IllegalStateException) {
                Log.e(TAG, "Error enabling foreground dispatch", e)
            }
        }
    }
    
    private fun disableForegroundDispatch() {
        Log.d(TAG, "MainActivity disableForegroundDispatch")
        val nfcAdapter = transferManager.getNfcAdapter()
        if (nfcAdapter != null) {
            try {
                nfcAdapter.disableForegroundDispatch(this)
                Log.d(TAG, "Foreground dispatch disabled")
            } catch (e: IllegalStateException) {
                Log.e(TAG, "Error disabling foreground dispatch", e)
            }
        }
    }
    
    override fun onNewIntent(intent: Intent) {
        Log.d(TAG, "MainActivity onNewIntent")
        super.onNewIntent(intent)
        
        // Save the new intent to replace the old one
        setIntent(intent)
        
        // Delegate to the intent manager
        intentManager.handleNewIntent(intent, appState, etMessage)
    }
    
    /**
     * Vibrate on message sent/received
     */
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

    /**
     * Helper method to ensure consistent message saving
     * This method ensures that all messages are properly stored in the database
     * @param messageText The message text to save
     * @param isSent Whether the message is sent by the user
     * @return The position of the added message in the adapter
     */
    override fun saveAndAddMessage(messageText: String, isSent: Boolean): Int {
        Log.d(TAG, "MainActivity saveAndAddMessage")
        if (messageText.isBlank()) return -1
        
        // Add the message to the adapter (which saves to the database)
        val position = if (isSent) {
            messageAdapter.addSentMessage(messageText)
        } else {
            messageAdapter.addReceivedMessage(messageText)
        }
        
        // Scroll to show the new message
        scrollToBottom()
        
        return position
    }

    /**
     * Load chunked message settings from the database
     */
    private fun loadChunkedMessageSettings() {
        transferManager.updateChunkedMessageSettings(
            dbHelper.getSetting(
                SettingsContract.SettingsEntry.KEY_MAX_CHUNK_SIZE,
                AppConstants.DefaultSettingsStrings.MAX_CHUNK_SIZE
            ).toInt(),
            dbHelper.getSetting(
                SettingsContract.SettingsEntry.KEY_CHUNK_DELAY,
                AppConstants.DefaultSettingsStrings.CHUNK_DELAY_MS
            ).toLong(),
            dbHelper.getSetting(
                SettingsContract.SettingsEntry.KEY_TRANSFER_RETRY_TIMEOUT_MS,
                AppConstants.DefaultSettingsStrings.TRANSFER_RETRY_TIMEOUT_MS
            ).toLong()
        )
    }

    /**
     * Load messages from the database
     */
    private fun loadMessagesFromDatabase() {
        Log.d(TAG, "MainActivity loadMessagesFromDatabase")
        // Clear existing messages
        messageAdapter.clearMessages()
        
        // Load messages from database
        val messages = dbHelper.getAllMessages()
        messageAdapter.setMessages(messages)
        
        // Scroll to bottom
        scrollToBottom()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        // Don't call super.onSaveInstanceState() to prevent the framework from saving the view hierarchy
        // This prevents TransactionTooLargeException when there are large messages in the RecyclerView
        
        // Save minimal app state
        outState.putString("appState", appState.name)
        outState.putString("lastReceivedMessageId", lastReceivedMessageId)
        outState.putBoolean("openedViaShareIntent", intentManager.isOpenedViaShareIntent())
        
        Log.d(TAG, "Saved minimal instance state without view hierarchy")
    }

    /**
     * Restore app state from saved instance state
     */
    private fun restoreAppState(savedInstanceState: Bundle) {
        Log.d(TAG, "MainActivity restoreAppState")
        // Restore app state
        val savedAppState = savedInstanceState.getString("appState")
        if (savedAppState != null) {
            try {
                appState = AppState.valueOf(savedAppState)
            } catch (e: IllegalArgumentException) {
                appState = AppState.IDLE
            }
        }
        
        lastReceivedMessageId = savedInstanceState.getString("lastReceivedMessageId", "")
        intentManager.setOpenedViaShareIntent(savedInstanceState.getBoolean("openedViaShareIntent", false))
        
        // If we were in the middle of a transfer, reset to receive mode
        if (appState == AppState.SENDING) {
            // Switch to receive mode
            mainHandler.postDelayed({
                transferManager.switchToReceiveMode()
            }, 500)
            
            Log.d(TAG, "Restored from a transfer state, resetting to receive mode")
        }
        
        // Update UI based on restored state
        updateModeIndicators()
        
        Log.d(TAG, "Restored app state")
    }

    private fun updateModeIndicators() {
        btnSendMode.isSelected = appState == AppState.SENDING
    }

    /**
     * Scroll to the bottom of the message list
     */
    private fun scrollToBottom() {
        rvMessages.post {
            val itemCount = messageAdapter.itemCount
            if (itemCount > 0) {
                rvMessages.smoothScrollToPosition(itemCount - 1)
            }
        }
    }

    override fun onResume() {
        Log.d(TAG, "MainActivity onResume")
        super.onResume()
        
        // Register for background NFC setting changes
        val settingsFilter = IntentFilter(SettingsActivity.ACTION_BACKGROUND_NFC_SETTING_CHANGED)
        registerReceiver(settingsChangeReceiver, settingsFilter, Context.RECEIVER_NOT_EXPORTED)
        
        // Register for service lifecycle events
        val serviceFilter = IntentFilter().apply {
            addAction(CardEmulationService.ACTION_SERVICE_DESTROYED)
            addAction(CardEmulationService.ACTION_SERVICE_STARTED)
        }
        registerReceiver(serviceLifecycleReceiver, serviceFilter, Context.RECEIVER_NOT_EXPORTED)
        
        // Enable foreground dispatch to intercept all NFC intents
        enableForegroundDispatch()
        
        if (appState == AppState.SENDING || transferManager.isRetryingTransfer()) {
            transferManager.enableReaderMode()
        }
        
        // Update the service with the latest message if in receive mode
        if (appState == AppState.RECEIVING) {
            // Send a broadcast to trigger listener registration in case the service was recreated
            val intent = Intent(CardEmulationService.ACTION_REGISTER_LISTENERS)
            sendBroadcast(intent)
            
            // Also use the transfer manager to properly set up the service
            transferManager.setCardEmulationMessage(etMessage.text.toString())
            transferManager.setupDataReceiver()
        }
        
        // Reload chunked message settings in case they were changed
        loadChunkedMessageSettings()
    }

    override fun onPause() {
        Log.d(TAG, "MainActivity onPause")
        super.onPause()
        
        // Unregister the receivers
        try {
            unregisterReceiver(settingsChangeReceiver)
            unregisterReceiver(serviceLifecycleReceiver)
        } catch (e: IllegalArgumentException) {
            // Receiver not registered
        }
        
        // Only disable reader mode if we're not in retry mode
        if (!transferManager.isRetryingTransfer()) {
            transferManager.disableReaderMode()
        }
        
        disableForegroundDispatch()
    }
    
    override fun onDestroy() {
        Log.d(TAG, "MainActivity onDestroy")
        super.onDestroy()
        // Clean up database resources
        messageAdapter.cleanup()
        dbHelper.close()
        
        // Clean up transfer manager resources
        transferManager.cleanup()
    }

    override fun onTagDiscovered(tag: Tag) {
        Log.d(TAG, "MainActivity onTagDiscovered")
        // Delegate to the transfer manager
        transferManager.handleTagDiscovered(tag)
    }

    /**
     * Initialize the message handlers
     */
    private fun initializeMessageHandlers() {
        Log.d(TAG, "MainActivity initializeMessageHandlers")
        // Clear any existing handlers
        MessageHandlerManager.clearHandlers()
        
        // Register the handlers
        MessageHandlerManager.registerHandler(LinkHandler())
        MessageHandlerManager.registerHandler(CashuHandler())
        
        Log.d(TAG, "Message handlers initialized")
    }
}
