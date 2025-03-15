package com.example.nfcdemo

import android.app.Activity
import android.app.PendingIntent
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.nfc.NfcAdapter
import android.nfc.NfcAdapter.ReaderCallback
import android.nfc.Tag
import android.nfc.TagLostException
import android.nfc.tech.IsoDep
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.EditText
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import java.io.IOException
import java.nio.charset.Charset

class MainActivity : Activity(), ReaderCallback {

    private val TAG = "MainActivity"
    private val mainHandler = Handler(Looper.getMainLooper())
    
    private var nfcAdapter: NfcAdapter? = null
    private lateinit var etMessage: EditText
    private lateinit var tvStatus: TextView
    private lateinit var btnSendMode: LinearLayout
    private lateinit var btnReceiveMode: LinearLayout
    private lateinit var btnPaste: ImageButton
    private lateinit var rvMessages: RecyclerView
    private lateinit var messageAdapter: MessageAdapter
    
    private var isInSendMode = false
    private var isInReceiveMode = false
    private var lastSentMessage = ""
    private var lastReceivedMessage = ""
    
    // For foreground dispatch
    private lateinit var pendingIntent: PendingIntent
    private lateinit var intentFilters: Array<IntentFilter>
    private lateinit var techLists: Array<Array<String>>

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        etMessage = findViewById(R.id.etMessage)
        tvStatus = findViewById(R.id.tvStatus)
        btnSendMode = findViewById(R.id.btnSendMode)
        btnReceiveMode = findViewById(R.id.btnReceiveMode)
        btnPaste = findViewById(R.id.btnPaste)
        rvMessages = findViewById(R.id.rvMessages)
        
        // Set up RecyclerView
        messageAdapter = MessageAdapter(this)
        rvMessages.layoutManager = LinearLayoutManager(this).apply {
            stackFromEnd = true // Messages appear from bottom
        }
        rvMessages.adapter = messageAdapter

        // Initialize NFC
        nfcAdapter = NfcAdapter.getDefaultAdapter(this)
        if (nfcAdapter == null) {
            tvStatus.text = getString(R.string.nfc_not_available)
            Toast.makeText(this, getString(R.string.nfc_not_available), Toast.LENGTH_LONG).show()
            btnSendMode.isEnabled = false
            btnReceiveMode.isEnabled = false
            return
        }
        
        // Set up NFC foreground dispatch system
        setupForegroundDispatch()

        // Set up button click listeners
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
            messageAdapter.addSentMessage(lastSentMessage)
            
            // Enable reader mode for sending data
            enableReaderMode()
        }

        btnReceiveMode.setOnClickListener {
            isInReceiveMode = true
            isInSendMode = false
            updateModeIndicators()
            tvStatus.text = getString(R.string.status_receive_mode)
            
            // Disable reader mode and prepare to receive data via HCE
            disableReaderMode()
            
            // Start the CardEmulationService
            val intent = Intent(this, CardEmulationService::class.java)
            startService(intent)
            
            // Set up the message and listener with a delay to ensure service is ready
            mainHandler.postDelayed({
                CardEmulationService.instance?.messageToShare = etMessage.text.toString()
                setupDataReceiver()
            }, 100) // Short delay to ensure service is initialized
        }
        
        // Set up paste button
        btnPaste.setOnClickListener {
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            if (clipboard.hasPrimaryClip() && clipboard.primaryClip?.itemCount ?: 0 > 0) {
                val item = clipboard.primaryClip?.getItemAt(0)
                val pasteText = item?.text.toString()
                etMessage.setText(pasteText)
                Toast.makeText(this, "Text pasted from clipboard", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, "Nothing to paste", Toast.LENGTH_SHORT).show()
            }
        }
        
        // Start in receive mode by default
        mainHandler.postDelayed({
            btnReceiveMode.performClick()
        }, 100)
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
        
        // Handle the NFC intent if we're not in reader mode
        if (!isInSendMode && intent.action == NfcAdapter.ACTION_TECH_DISCOVERED ||
            intent.action == NfcAdapter.ACTION_TAG_DISCOVERED ||
            intent.action == NfcAdapter.ACTION_NDEF_DISCOVERED) {
            
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
            
            // Check if this is a duplicate message
            if (receivedData != lastReceivedMessage) {
                lastReceivedMessage = receivedData
                
                mainHandler.post {
                    // Update UI on the main thread
                    messageAdapter.addReceivedMessage(receivedData)
                    tvStatus.text = getString(R.string.message_received)
                }
            } else {
                Log.d(TAG, "Duplicate message received, ignoring: $receivedData")
            }
        }
    }
    
    private fun updateModeIndicators() {
        btnSendMode.isSelected = isInSendMode
        btnReceiveMode.isSelected = isInReceiveMode
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

    // ReaderCallback implementation
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
                    
                    // Check if this is a duplicate message
                    if (receivedMessage != lastReceivedMessage) {
                        lastReceivedMessage = receivedMessage
                        
                        runOnUiThread {
                            messageAdapter.addReceivedMessage(receivedMessage)
                            tvStatus.text = getString(R.string.message_received)
                        }
                    } else {
                        Log.d(TAG, "Duplicate message received, ignoring: $receivedMessage")
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
}
