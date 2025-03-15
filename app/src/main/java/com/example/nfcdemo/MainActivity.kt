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
    
    private var isInSendMode = false
    private var isInReceiveMode = false
    private var lastSentMessage = ""
    private var lastReceivedMessage = ""
    
    // Default message length limit before truncation
    private var messageLengthLimit = 200
    
    // Track if the app was opened via share intent
    private var openedViaShareIntent = false
    
    // For foreground dispatch
    private lateinit var pendingIntent: PendingIntent
    private lateinit var intentFilters: Array<IntentFilter>
    private lateinit var techLists: Array<Array<String>>

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
            
            isInSendMode = true
            isInReceiveMode = false
            updateModeIndicators()
            tvStatus.text = getString(R.string.status_send_mode)
            
            // Store the message to send
            lastSentMessage = etMessage.text.toString()
            
            // Add the message to the chat as a sent message
            messageAdapter.addSentMessage(etMessage.text.toString())
            scrollToBottom()
            
            // Clear the input field after sending
            etMessage.text.clear()
            
            // Enable reader mode for sending data
            enableReaderMode()
        }
        
        // Settings button
        btnSettings.setOnClickListener {
            val intent = Intent(this, SettingsActivity::class.java)
            startActivity(intent)
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
        
        // Handle share intents
        if (intent.action == Intent.ACTION_SEND && intent.type?.startsWith("text/") == true) {
            Log.d(TAG, "Share intent received while app is running")
            
            // Cancel any pending receive mode initialization
            mainHandler.removeCallbacksAndMessages(null)
            
            // Handle the share intent
            handleIncomingShareIntent(intent)
            return
        }
        
        // Handle the NFC intent if we're not in reader mode
        if (!isInSendMode && (intent.action == NfcAdapter.ACTION_TECH_DISCOVERED ||
            intent.action == NfcAdapter.ACTION_TAG_DISCOVERED ||
            intent.action == NfcAdapter.ACTION_NDEF_DISCOVERED)) {
            
            val tag = intent.getParcelableExtra<Tag>(NfcAdapter.EXTRA_TAG)
            tag?.let {
                // Process the tag if we're not already in reader mode
                if (!isInSendMode) {
                    onTagDiscovered(it)
                }
            }
        }
    }
    
    private fun setupDataReceiver() {
        // This is a critical function to ensure UI updates happen
        CardEmulationService.instance?.onDataReceivedListener = { receivedData ->
            Log.d(TAG, "Data received in MainActivity: $receivedData")
            
            // Check if this is a duplicate message or empty
            if (receivedData.isNotBlank() && receivedData != lastReceivedMessage) {
                lastReceivedMessage = receivedData
                
                mainHandler.post {
                    // Update UI on the main thread
                    messageAdapter.addReceivedMessage(receivedData)
                    tvStatus.text = getString(R.string.message_received)
                    scrollToBottom()
                    
                    // Vibrate on message received
                    vibrate(200)
                    
                    // Check if auto-open links is enabled
                    if (dbHelper.getBooleanSetting(SettingsContract.SettingsEntry.KEY_AUTO_OPEN_LINKS, true)) {
                        openLinksInMessage(receivedData)
                    }
                }
            } else {
                Log.d(TAG, "Empty or duplicate message received, ignoring: $receivedData")
                // No UI updates or vibration for empty/duplicate messages
            }
        }
    }
    
    /**
     * Automatically open links in a message if auto-open links is enabled
     */
    private fun openLinksInMessage(message: String) {
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
                
                // Open the URL
                val intent = Intent(Intent.ACTION_VIEW, Uri.parse(fullUrl))
                startActivity(intent)
            }
        }
    }
    
    private fun updateModeIndicators() {
        btnSendMode.isSelected = isInSendMode
    }

    override fun onResume() {
        super.onResume()
        
        // Enable foreground dispatch to intercept all NFC intents
        enableForegroundDispatch()
        
        if (isInSendMode) {
            enableReaderMode()
        }
        
        // Update the service with the latest message if in receive mode
        if (isInReceiveMode) {
            CardEmulationService.instance?.messageToShare = etMessage.text.toString()
            // Re-establish the data receiver connection
            setupDataReceiver()
        }
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
        if (!isInReceiveMode) {
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
            val selectApdu = buildSelectApdu("F0010203040506")
            val result = isoDep.transceive(selectApdu)
            
            if (!isSuccess(result)) {
                Log.e(TAG, "Error selecting AID: ${result.toHex()}")
                return
            }
            
            if (isInSendMode) {
                // Send data to the HCE device
                val message = lastSentMessage
                val sendCommand = "SEND_DATA:$message".toByteArray(Charset.forName("UTF-8"))
                val sendResult = isoDep.transceive(sendCommand)
                
                if (isSuccess(sendResult)) {
                    runOnUiThread {
                        tvStatus.text = getString(R.string.message_sent)
                        
                        // Mark the last sent message as delivered
                        val lastPosition = messageAdapter.getItemCount() - 1
                        messageAdapter.markMessageAsDelivered(lastPosition)
                        
                        // Vibrate on message sent
                        vibrate(200)
                        
                        // Check if we should close the app after sending a shared message
                        if (openedViaShareIntent) {
                            val closeAfterSharedSend = dbHelper.getBooleanSetting(
                                SettingsContract.SettingsEntry.KEY_CLOSE_AFTER_SHARED_SEND, 
                                false
                            )
                            
                            if (closeAfterSharedSend) {
                                // Show a toast to inform the user
                                Toast.makeText(this, "Message sent successfully. Closing app.", Toast.LENGTH_SHORT).show()
                                
                                // Close the app after a short delay
                                mainHandler.postDelayed({
                                    finish()
                                }, 1000)
                                return@runOnUiThread
                            }
                        }
                        
                        // Clear the sent message to prevent re-sending
                        lastSentMessage = ""
                        
                        // Switch to receive mode automatically
                        isInSendMode = false
                        isInReceiveMode = true
                        updateModeIndicators()
                        tvStatus.text = getString(R.string.status_receive_mode)
                        
                        // Start the CardEmulationService for receiving response
                        disableReaderMode()
                        val intent = Intent(this, CardEmulationService::class.java)
                        startService(intent)
                        
                        // Set up the message and listener with a delay
                        mainHandler.postDelayed({
                            CardEmulationService.instance?.messageToShare = etMessage.text.toString()
                            setupDataReceiver()
                        }, 100)
                        scrollToBottom()
                    }
                } else {
                    runOnUiThread {
                        tvStatus.text = getString(R.string.message_send_failed)
                    }
                }
            } else {
                // Request data from the HCE device
                val getCommand = "GET_DATA".toByteArray(Charset.forName("UTF-8"))
                val getResult = isoDep.transceive(getCommand)
                
                if (isSuccess(getResult)) {
                    // Extract the data (remove the status word)
                    val dataBytes = getResult.copyOfRange(0, getResult.size - 2)
                    val receivedMessage = String(dataBytes, Charset.forName("UTF-8"))
                    
                    // Check if this is a duplicate message or empty
                    if (receivedMessage.isNotBlank() && receivedMessage != lastReceivedMessage) {
                        lastReceivedMessage = receivedMessage
                        
                        runOnUiThread {
                            messageAdapter.addReceivedMessage(receivedMessage)
                            tvStatus.text = getString(R.string.message_received)
                            scrollToBottom()
                            
                            // Vibrate on message received
                            vibrate(200)
                            
                            // Check if auto-open links is enabled
                            if (dbHelper.getBooleanSetting(SettingsContract.SettingsEntry.KEY_AUTO_OPEN_LINKS, true)) {
                                openLinksInMessage(receivedMessage)
                            }
                        }
                    } else {
                        Log.d(TAG, "Empty or duplicate message received, ignoring: $receivedMessage")
                        // Don't update UI or vibrate for empty/duplicate messages
                    }
                }
            }
            
        } catch (e: IOException) {
            Log.e(TAG, "Error communicating with tag: ${e.message}")
            runOnUiThread {
                tvStatus.text = "Communication error: ${e.message}"
            }
        } catch (e: TagLostException) {
            Log.e(TAG, "Tag lost: ${e.message}")
            runOnUiThread {
                tvStatus.text = "Tag connection lost. Try again."
            }
        } finally {
            try {
                isoDep.close()
            } catch (e: IOException) {
                Log.e(TAG, "Error closing tag connection: ${e.message}")
            }
        }
    }
    
    private fun buildSelectApdu(aid: String): ByteArray {
        // Format: [CLASS | INSTRUCTION | PARAMETER 1 | PARAMETER 2 | LENGTH | DATA]
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
    
    private fun isSuccess(response: ByteArray): Boolean {
        return response.size >= 2 && 
               response[response.size - 2] == 0x90.toByte() && 
               response[response.size - 1] == 0x00.toByte()
    }
    
    private fun hexStringToByteArray(s: String): ByteArray {
        val len = s.length
        val data = ByteArray(len / 2)
        for (i in 0 until len step 2) {
            data[i / 2] = ((Character.digit(s[i], 16) shl 4) + Character.digit(s[i + 1], 16)).toByte()
        }
        return data
    }
    
    private fun ByteArray.toHex(): String {
        return joinToString("") { "%02X".format(it) }
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
                    Toast.makeText(this, "Text received. Preparing to send via NFC.", Toast.LENGTH_LONG).show()
                    
                    // Automatically prepare to send the shared text
                    mainHandler.postDelayed({
                        // Only proceed if the text is still there (user hasn't cleared it)
                        if (!etMessage.text.isNullOrEmpty()) {
                            Log.d(TAG, "Auto-sending shared text")
                            
                            // Store the message to send
                            lastSentMessage = etMessage.text.toString()
                            
                            // Add the message to the chat as a sent message
                            messageAdapter.addSentMessage(lastSentMessage)
                            scrollToBottom()
                            
                            // Clear the input field after sending
                            etMessage.text.clear()
                            
                            // Switch to send mode
                            isInSendMode = true
                            isInReceiveMode = false
                            updateModeIndicators()
                            tvStatus.text = getString(R.string.status_send_mode)
                            
                            // Enable reader mode for sending data
                            enableReaderMode()
                        } else {
                            Log.d(TAG, "Text field is empty, not auto-sending")
                        }
                    }, 500) // Short delay to ensure UI is ready
                } else {
                    // Just show a toast that the text is ready to send
                    Toast.makeText(this, "Text received. Press send when ready.", Toast.LENGTH_LONG).show()
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
        
        mainHandler.postDelayed({
            // Only switch to receive mode if we're not already in send mode
            if (!isInSendMode) {
                isInReceiveMode = true
                isInSendMode = false
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
            }
        }, 500)
    }
}
