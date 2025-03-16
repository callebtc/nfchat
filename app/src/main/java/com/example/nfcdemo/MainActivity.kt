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
import com.example.nfcdemo.data.AppConstants
import com.example.nfcdemo.data.MessageDbHelper
import com.example.nfcdemo.data.SettingsContract
import java.nio.charset.Charset
import java.io.IOException
import org.json.JSONObject
import java.util.UUID
import com.example.nfcdemo.nfc.MessageData
import com.example.nfcdemo.nfc.MessageProcessor
import com.example.nfcdemo.nfc.NfcProtocol
import android.content.BroadcastReceiver
import com.example.nfcdemo.nfc.ChunkwiseTransferManager
import com.example.nfcdemo.nfc.ChunkedTransferState
import com.example.nfcdemo.nfc.TransferManager

/**
 * Enum representing the different states of the app
 */
enum class AppState {
    IDLE,       // Initial state, not actively sending or receiving
    RECEIVING,  // Actively listening for incoming messages
    SENDING     // Actively sending a message
}

class MainActivity : Activity(), ReaderCallback {

    private val TAG = "MainActivity"
    private val mainHandler = Handler(Looper.getMainLooper())
    
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
    private var messageLengthLimit = AppConstants.DefaultSettings.MESSAGE_LENGTH_LIMIT
    
    // Track if the app was opened via share intent
    private var openedViaShareIntent = false
    
    // For foreground dispatch
    private lateinit var pendingIntent: PendingIntent
    private lateinit var intentFilters: Array<IntentFilter>
    private lateinit var techLists: Array<Array<String>>
    
    // Transfer manager for handling NFC operations
    private lateinit var transferManager: TransferManager

    private var backgroundNfcEnabled = true
    private val settingsChangeReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == SettingsActivity.ACTION_BACKGROUND_NFC_SETTING_CHANGED) {
                backgroundNfcEnabled = intent.getBooleanExtra(SettingsActivity.EXTRA_BACKGROUND_NFC_ENABLED, true)
                Log.d(TAG, "Background NFC setting changed: enabled=$backgroundNfcEnabled")
            }
        }
    }

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
        
        // Load messages from database
        loadMessagesFromDatabase()
        
        // Set the message length limit
        setMessageLengthLimit(messageLengthLimit)
        
        // Initialize transfer manager
        transferManager = TransferManager(this)
        setupTransferManagerCallbacks()

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
            handleIncomingShareIntent(intent)
        }
        // Handle NFC intents if the app was launched by an NFC discovery
        else if (isNfcIntent) {
            Log.d(TAG, "App launched via NFC intent: ${intent?.action}")
            // Start in receive mode since we were launched by an NFC discovery
            startInReceiveMode()
            // Process the NFC intent
            intent?.let { handleNfcIntent(it) }
        }
        // Handle being brought to the foreground from a background message receive
        else if (fromBackgroundReceive) {
            Log.d(TAG, "App brought to foreground from background message receive")
            // Make sure we're in receive mode
            startInReceiveMode()
            // Show a toast to inform the user
            Toast.makeText(this, getString(R.string.app_launched_by_nfc), Toast.LENGTH_SHORT).show()
        }
        // Start in receive mode by default, but only if we're not handling a share or NFC intent
        else if (!isShareIntent) {
            startInReceiveMode()
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
        Log.d(TAG, "Initial background NFC setting: enabled=$backgroundNfcEnabled")
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
        transferManager.onAppStateChanged = { newState ->
            appState = newState
            updateModeIndicators()
        }
        
        transferManager.onStatusChanged = { status ->
            tvStatus.text = status
        }
        
        transferManager.onMessageSent = { position ->
            // Mark the message as delivered
            val lastPosition = if (position == -1) messageAdapter.itemCount - 1 else position
            messageAdapter.markMessageAsDelivered(lastPosition)
            
            // Check if we should close the app after sending a shared message
            val shouldClose = handlePostSendActions()
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
    }
    
    /**
     * Toggle between send and receive modes when the status text is tapped
     */
    private fun toggleMode() {
        transferManager.toggleMode()
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
        
        // Handle being brought to the foreground from a background message receive
        if (intent.getBooleanExtra("from_background_receive", false)) {
            Log.d(TAG, "App brought to foreground from background message receive")
            // Make sure we're in receive mode
            if (appState != AppState.RECEIVING) {
                transferManager.switchToReceiveMode()
            }
            return
        }
        
        // Handle the NFC intent
        if (intent.action == NfcAdapter.ACTION_TECH_DISCOVERED ||
            intent.action == NfcAdapter.ACTION_TAG_DISCOVERED ||
            intent.action == NfcAdapter.ACTION_NDEF_DISCOVERED) {
            
            handleNfcIntent(intent)
        }
    }
    
    /**
     * Handle NFC intents, whether from foreground dispatch or from app launch
     */
    private fun handleNfcIntent(intent: Intent) {
        // Check if the app was launched from background and if background NFC is disabled
        if (!isAppInForeground && !backgroundNfcEnabled) {
            Log.d(TAG, "Ignoring NFC intent because background NFC is disabled")
            return
        }
        
        Log.d(TAG, "Handling NFC intent: ${intent.action}")
        
        // Use the new API for getting parcelable extras if available
        val tag = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(NfcAdapter.EXTRA_TAG, Tag::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(NfcAdapter.EXTRA_TAG)
        }
        
        tag?.let {
            // If we're in send mode, we'll process the tag in onTagDiscovered
            // If we're not in send mode, process the tag directly
            if (appState != AppState.SENDING) {
                // Make sure we're in receive mode
                if (appState != AppState.RECEIVING) {
                    transferManager.switchToReceiveMode()
                }
                
                // Process the tag
                transferManager.handleTagDiscovered(it)
                
                // Vibrate to indicate NFC detection
                vibrate(100)
                
                // Show a toast to indicate the app was launched by NFC
                if (intent.action != NfcAdapter.ACTION_TECH_DISCOVERED) {
                    Toast.makeText(this, getString(R.string.app_launched_by_nfc), Toast.LENGTH_SHORT).show()
                }
            }
        } ?: run {
            Log.e(TAG, "No tag found in intent")
        }
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
                            transferManager.setLastSentMessage(lastSentMessage)
                            
                            // Add the message to the chat as a sent message and save to database
                            saveAndAddMessage(lastSentMessage, true)
                            
                            // Clear the input field after sending
                            etMessage.text.clear()
                            
                            // Switch to send mode
                            transferManager.switchToSendMode()
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
                transferManager.switchToReceiveMode()
                
                // Set up the data receiver
                transferManager.setupDataReceiver()
                
                // Set the current message for the CardEmulationService
                transferManager.setCardEmulationMessage(etMessage.text.toString())
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
        outState.putBoolean("openedViaShareIntent", openedViaShareIntent)
        
        Log.d(TAG, "Saved minimal instance state without view hierarchy")
    }

    /**
     * Restore app state from saved instance state
     */
    private fun restoreAppState(savedInstanceState: Bundle) {
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
        openedViaShareIntent = savedInstanceState.getBoolean("openedViaShareIntent", false)
        
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

    // Helper method to determine if the app is in the foreground
    private val isAppInForeground: Boolean
        get() {
            return hasWindowFocus() || (intent?.getBooleanExtra("from_background_receive", false) == true)
        }

    private fun handlePostSendActions(): Boolean {
        // Check if we should close the app after sending a shared message
        if (openedViaShareIntent) {
            val closeAfterSharedSend = dbHelper.getBooleanSetting(
                SettingsContract.SettingsEntry.KEY_CLOSE_AFTER_SHARED_SEND, 
                AppConstants.DefaultSettings.CLOSE_AFTER_SHARED_SEND
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

    private fun updateModeIndicators() {
        btnSendMode.isSelected = appState == AppState.SENDING
    }

    override fun onResume() {
        super.onResume()
        
        // Register for background NFC setting changes
        val filter = IntentFilter(SettingsActivity.ACTION_BACKGROUND_NFC_SETTING_CHANGED)
        registerReceiver(settingsChangeReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        
        // Enable foreground dispatch to intercept all NFC intents
        enableForegroundDispatch()
        
        if (appState == AppState.SENDING || transferManager.isRetryingTransfer()) {
            transferManager.enableReaderMode()
        }
        
        // Update the service with the latest message if in receive mode
        if (appState == AppState.RECEIVING) {
            transferManager.setCardEmulationMessage(etMessage.text.toString())
            // Re-establish the data receiver connection
            transferManager.setupDataReceiver()
        }
        
        // Reload chunked message settings in case they were changed
        loadChunkedMessageSettings()
    }

    override fun onPause() {
        super.onPause()
        
        // Unregister the receiver
        try {
            unregisterReceiver(settingsChangeReceiver)
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
        super.onDestroy()
        // Clean up database resources
        messageAdapter.cleanup()
        dbHelper.close()
        
        // Clean up transfer manager resources
        transferManager.cleanup()
    }

    override fun onTagDiscovered(tag: Tag) {
        // Delegate to the transfer manager
        transferManager.handleTagDiscovered(tag)
    }
}
