package com.example.nfcdemo

import android.app.ActivityManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.nfc.cardemulation.HostApduService
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.core.app.NotificationCompat
import com.example.nfcdemo.data.AppConstants
import com.example.nfcdemo.data.MessageDbHelper
import com.example.nfcdemo.data.SettingsContract
import com.example.nfcdemo.nfc.MessageData
import com.example.nfcdemo.nfc.NdefProcessor
import com.example.nfcdemo.nfc.NfcProtocol
import java.nio.charset.Charset

/**
 * Host Card Emulation service for NFC communication. This service handles both foreground and
 * background NFC operations.
 */
class CardEmulationService : HostApduService() {

    companion object {
        private const val TAG = "CardEmulationService"
        private const val STATUS_FAILED = "6F00" // Status word for command failure

        // AID for our service
        private val AID = NfcProtocol.hexStringToByteArray(NfcProtocol.DEFAULT_AID)

        // Notification constants
        private const val NOTIFICATION_CHANNEL_ID = "nfc_service_channel"
        private const val NOTIFICATION_ID = 1001

        // Broadcast actions
        const val ACTION_SERVICE_STARTED = "com.example.nfcdemo.ACTION_SERVICE_STARTED"
        const val ACTION_SERVICE_DESTROYED = "com.example.nfcdemo.ACTION_SERVICE_DESTROYED"
        const val ACTION_REGISTER_LISTENERS = "com.example.nfcdemo.ACTION_REGISTER_LISTENERS"

        // Static instance of the service for communication
        var instance: CardEmulationService? = null
            private set // Only allow internal setting

        // Saved message data for when service is recreated
        private var lastReceivedMessageData: MessageData? = null

        // Restart counter to track service restarts
        private var serviceRestartCount = 0

        // Service is running flag
        private var isServiceRunning = false

        // Keep track of whether we're a foreground service
        private var isForegroundService = false

        // Constants for service management
        private const val SERVICE_RESTART_DELAY = 2000L // 2 seconds
        private const val SERVICE_RESTART_THRESHOLD =
                2 // Minimum number of restarts before scheduling
        private const val SERVICE_HEARTBEAT_INTERVAL = 60000L // 1 minute
        private const val DELAYED_MESSAGE_DELIVERY_MS = 1000L // 1 second
    }

    // NDEF processor for handling NDEF commands
    val ndefProcessor = NdefProcessor()

    // Message to be shared when requested
    var messageToShare: String = ""
        set(value) {
            field = value
            // Forward the message to the NdefProcessor
            ndefProcessor.setMessageToSend(value)
        }

    // Callback to notify MainActivity when data is received
    var onDataReceivedListener: ((MessageData) -> Unit)? = null

    // Callback to notify MainActivity when NDEF message is received
    var onNdefMessageReceivedListener: ((String) -> Unit)? = null

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

    // Main thread handler for scheduling tasks
    private val mainHandler = Handler(Looper.getMainLooper())

    // Heartbeat runnable to periodically check service health
    private val heartbeatRunnable =
            object : Runnable {
                override fun run() {
                    Log.d(
                            TAG,
                            "Service heartbeat - count: $serviceRestartCount, foreground: $isForegroundService"
                    )

                    // If we're not in foreground mode but should be, try to start as foreground
                    if (!isForegroundService) {
                        startForegroundService()
                    }

                    // Schedule next heartbeat
                    mainHandler.postDelayed(this, SERVICE_HEARTBEAT_INTERVAL)
                }
            }

    // Broadcast receiver for listener registration
    private val listenerRegistrationReceiver =
            object : BroadcastReceiver() {
                override fun onReceive(context: Context?, intent: Intent?) {
                    if (intent?.action == ACTION_REGISTER_LISTENERS) {
                        Log.d(TAG, "Received request to register listeners")

                        // If we have a saved message that wasn't delivered, deliver it now
                        lastReceivedMessageData?.let { messageData ->
                            Log.d(TAG, "Delivering saved message: ${messageData.content}")
                            onDataReceivedListener?.invoke(messageData)
                            lastReceivedMessageData = null
                        }
                    }
                }
            }

    /** Check if currently receiving a chunked message */
    fun isReceivingChunkedMessage(): Boolean {
        return isReceivingChunkedMessage
    }

    /** Reset the chunked message state */
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
        isServiceRunning = true

        Log.d(TAG, "CardEmulationService created, restart count: $serviceRestartCount")
        serviceRestartCount++

        // Set up the NDEF processor callback
        setupNdefProcessorCallbacks()

        // Register broadcast receiver
        val filter = IntentFilter(ACTION_REGISTER_LISTENERS)
        registerReceiver(listenerRegistrationReceiver, filter, Context.RECEIVER_NOT_EXPORTED)

        // Start as a foreground service to prevent it from being killed
        startForegroundService()

        // Start heartbeat to keep service healthy
        startHeartbeat()

        // Broadcast that the service has started
        val intent = Intent(ACTION_SERVICE_STARTED)
        intent.setPackage(packageName)
        sendBroadcast(intent)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "CardEmulationService started with startId: $startId")

        // If the service is restarted after being killed, make sure it's in foreground
        if (intent?.action == Intent.ACTION_MAIN) {
            startForegroundService()
        }

        // Reset the flags to ensure we're in a good state
        isServiceRunning = true

        // If the service is killed, restart it
        return Service.START_STICKY
    }

    override fun onDestroy() {
        Log.d(TAG, "CardEmulationService destroyed")

        isServiceRunning = false
        isForegroundService = false

        // Stop heartbeat
        mainHandler.removeCallbacks(heartbeatRunnable)

        // Unregister receiver
        try {
            unregisterReceiver(listenerRegistrationReceiver)
        } catch (e: IllegalArgumentException) {
            // Receiver not registered
        }

        // Broadcast that the service is being destroyed
        val intent = Intent(ACTION_SERVICE_DESTROYED)
        intent.setPackage(packageName)
        sendBroadcast(intent)

        // Clear the static instance
        instance = null

        // Schedule a delayed restart if needed
        scheduleServiceRestart()

        super.onDestroy()
    }

    /** Schedule a delayed restart of the service */
    private fun scheduleServiceRestart() {
        // Only attempt to restart if we've been running for a while
        // This prevents rapid restart loops
        if (serviceRestartCount > SERVICE_RESTART_THRESHOLD) {
            val restartIntent = Intent(applicationContext, CardEmulationService::class.java)
            restartIntent.action = Intent.ACTION_MAIN

            val pendingIntent =
                    PendingIntent.getService(
                            applicationContext,
                            1,
                            restartIntent,
                            PendingIntent.FLAG_ONE_SHOT or PendingIntent.FLAG_IMMUTABLE
                    )

            val alarmManager = getSystemService(Context.ALARM_SERVICE) as android.app.AlarmManager
            alarmManager.set(
                    android.app.AlarmManager.RTC_WAKEUP,
                    System.currentTimeMillis() + SERVICE_RESTART_DELAY,
                    pendingIntent
            )

            Log.d(TAG, "Scheduled service restart in ${SERVICE_RESTART_DELAY}ms")
        }
    }

    /** Start the heartbeat to periodically check service health */
    private fun startHeartbeat() {
        mainHandler.postDelayed(heartbeatRunnable, SERVICE_HEARTBEAT_INTERVAL)
    }

    /** Start the service as a foreground service */
    private fun startForegroundService() {
        try {
            // Create notification channel for Android O and above
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val channel =
                        NotificationChannel(
                                        NOTIFICATION_CHANNEL_ID,
                                        "NFC Service Channel",
                                        NotificationManager.IMPORTANCE_LOW
                                )
                                .apply {
                                    description = "Channel for NFC service notifications"
                                    setShowBadge(false)
                                }

                val notificationManager =
                        getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                notificationManager.createNotificationChannel(channel)
            }

            // Create an intent to open the MainActivity when the notification is tapped
            val pendingIntent =
                    PendingIntent.getActivity(
                            this,
                            0,
                            Intent(this, MainActivity::class.java),
                            PendingIntent.FLAG_IMMUTABLE
                    )

            // Build the notification
            val notification =
                    NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
                            .setContentTitle("NFC Chat Active")
                            .setContentText("Ready to receive NFC messages")
                            .setSmallIcon(R.drawable.ic_nfc)
                            .setContentIntent(pendingIntent)
                            .setPriority(NotificationCompat.PRIORITY_LOW)
                            .setOngoing(true)
                            .build()

            // Start as foreground service
            startForeground(NOTIFICATION_ID, notification)
            isForegroundService = true
            Log.d(TAG, "Started as foreground service")
        } catch (e: SecurityException) {
            // Check if this is the specific permission issue for Android 14+
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE &&
                            e.message?.contains("FOREGROUND_SERVICE_DATA_SYNC") == true
            ) {
                Log.e(
                        TAG,
                        "Failed to start as foreground service due to missing FOREGROUND_SERVICE_DATA_SYNC permission: ${e.message}"
                )
                Log.d(
                        TAG,
                        "Running as a regular service instead. Some messages may be lost if the service is killed."
                )

                // Broadcast that we're running in a degraded mode
                val intent =
                        Intent(ACTION_SERVICE_STARTED).apply {
                            putExtra("degraded_mode", true)
                            setPackage(packageName)
                        }
                sendBroadcast(intent)
                isForegroundService = false
            } else {
                // If we don't have the permission, just run as a regular service
                Log.e(TAG, "Failed to start as foreground service: ${e.message}")
                Log.d(TAG, "Running as a regular service instead")
                isForegroundService = false
            }
        } catch (e: Exception) {
            // Handle any other exceptions
            Log.e(TAG, "Error starting foreground service: ${e.message}")
            isForegroundService = false
        }
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
        Log.d(TAG, "> Received APDU: ${NfcProtocol.byteArrayToHex(commandApdu)}")

        // Check if background NFC is enabled
        val dbHelper = MessageDbHelper(this)
        val backgroundNfcEnabled =
                dbHelper.getBooleanSetting(
                        SettingsContract.SettingsEntry.KEY_ENABLE_BACKGROUND_NFC,
                        true
                )

        // If the app is not in foreground and background NFC is disabled, return error
        if (!isAppInForeground() && !backgroundNfcEnabled) {
            Log.d(TAG, "Background NFC is disabled, ignoring command")
            return NfcProtocol.hexStringToByteArray(STATUS_FAILED)
        }

        // First, try to process as an NDEF command
        try {
            // Check if this is an NDEF command - some basic pattern matching
            if (isNdefCommand(commandApdu)) {
                val response = ndefProcessor.processCommandApdu(commandApdu)
                if (response != NdefProcessor.NDEF_RESPONSE_ERROR) {
                    return response
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error processing NDEF command: ${e.message}")
            return NfcProtocol.UNKNOWN_CMD_SW
        }

        // If not an NDEF command, process as a regular NFC command

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

    /** Set up callbacks for the NdefProcessor */
    private fun setupNdefProcessorCallbacks() {
        ndefProcessor.onNdefMessageReceived = { messageData ->
            // When an NDEF message is received via UPDATE BINARY
            Log.d(TAG, "Received NDEF message via UPDATE BINARY: ${messageData.content}")

            // Process the message data similar to our regular message handler
            processReceivedMessageData(messageData)
        }
    }

    /** Determine if the command is NDEF-related */
    private fun isNdefCommand(commandApdu: ByteArray): Boolean {
        // Check if command has minimum length for APDU command
        if (commandApdu.size < 4) return false

        // Check command type
        return when {
            // SELECT Application - 00 A4 04 00 (used for NDEF application selection)
            commandApdu[0] == 0x00.toByte() &&
                    commandApdu[1] == 0xA4.toByte() &&
                    commandApdu[2] == 0x04.toByte() &&
                    commandApdu[3] == 0x00.toByte() -> true

            // SELECT File - 00 A4 00 0C (used for NDEF file selection)
            commandApdu[0] == 0x00.toByte() &&
                    commandApdu[1] == 0xA4.toByte() &&
                    commandApdu[2] == 0x00.toByte() &&
                    commandApdu[3] == 0x0C.toByte() -> true

            // READ BINARY - 00 B0 (used to read NDEF data)
            commandApdu[0] == 0x00.toByte() && commandApdu[1] == 0xB0.toByte() -> true

            // UPDATE BINARY - 00 D6 (used to write NDEF data)
            commandApdu[0] == 0x00.toByte() && commandApdu[1] == 0xD6.toByte() -> true

            // Otherwise, not an NDEF command
            else -> false
        }
    }

    /** Handle the GET_DATA command */
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

    /** Handle the SEND_DATA command */
    private fun handleSendDataCommand(commandString: String): ByteArray {
        Log.d(TAG, "Handling SEND_DATA command")

        // Extract the data from the command
        val data = NfcProtocol.parseSendData(commandString)

        if (data != null) {
            // Parse the JSON message
            val messageData = MessageData.fromJson(data)

            if (messageData != null) {
                // Process the message data
                processReceivedMessageData(messageData)
            } else {
                Log.e(TAG, "Failed to parse message data: $data")
            }
        } else {
            Log.e(TAG, "Failed to parse SEND_DATA command: $commandString")
        }

        return NfcProtocol.SELECT_OK_SW
    }

    /** Process received message data and handle bringing app to foreground if needed */
    private fun processReceivedMessageData(messageData: MessageData) {
        // Check if we should bring the app to the foreground
        val dbHelper = MessageDbHelper(this)
        val bringToForeground =
                dbHelper.getBooleanSetting(
                        SettingsContract.SettingsEntry.KEY_BRING_TO_FOREGROUND,
                        AppConstants.DefaultSettings.BRING_TO_FOREGROUND
                )

        if (bringToForeground && !isAppInForeground()) {
            // Create an intent to launch the MainActivity
            val intent =
                    Intent(this, MainActivity::class.java).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                        putExtra("from_background_receive", true)
                    }
            startActivity(intent)
        }

        // Save the message data in case the service is destroyed before the listener is registered
        lastReceivedMessageData = messageData

        // Notify the listener if available
        if (onDataReceivedListener != null) {
            onDataReceivedListener?.invoke(messageData)
            lastReceivedMessageData = null
        } else {
            Log.d(TAG, "onDataReceivedListener is null, saving message for later delivery")

            // Since no listener is available, schedule a task to check again soon
            // This helps in cases where the activity is being launched but hasn't registered yet
            mainHandler.postDelayed(
                    {
                        if (lastReceivedMessageData != null && onDataReceivedListener != null) {
                            Log.d(
                                    TAG,
                                    "Delivering delayed message: ${lastReceivedMessageData?.content}"
                            )
                            onDataReceivedListener?.invoke(lastReceivedMessageData!!)
                            lastReceivedMessageData = null
                        }
                    },
                    DELAYED_MESSAGE_DELIVERY_MS
            )
        }
    }

    /** Handle the CHUNK_INIT command */
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

            Log.d(
                    TAG,
                    "Initialized chunked message: totalLength=$totalLength, chunkSize=$chunkSize, totalChunks=$totalChunks"
            )

            // Notify the listener about the start of a chunk transfer
            onChunkProgressListener?.invoke(0, totalChunks)
        } else {
            Log.e(TAG, "Failed to parse CHUNK_INIT command: $commandString")

            // Notify the listener about the error
            onChunkErrorListener?.invoke("Failed to parse CHUNK_INIT command")
        }

        return NfcProtocol.SELECT_OK_SW
    }

    /** Handle the CHUNK_DATA command */
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

                Log.d(
                        TAG,
                        "Received chunk $chunkIndex: ${data.take(20)}... (${receivedChunks}/$totalChunks)"
                )

                // Notify the listener about progress
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

    /** Handle the CHUNK_COMPLETE command */
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
            // Check if we should bring the app to the foreground
            val dbHelper = MessageDbHelper(this)
            val bringToForeground =
                    dbHelper.getBooleanSetting(
                            SettingsContract.SettingsEntry.KEY_BRING_TO_FOREGROUND,
                            AppConstants.DefaultSettings.BRING_TO_FOREGROUND
                    )

            if (bringToForeground) {
                // Create an intent to launch the MainActivity
                val intent =
                        Intent(this, MainActivity::class.java).apply {
                            addFlags(
                                    Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
                            )
                            putExtra("from_background_receive", true)
                        }
                startActivity(intent)
            }

            // Notify the listener about the received message
            onDataReceivedListener?.invoke(messageData)

            // Notify the listener that the chunk transfer is complete (for progress bar)
            onChunkProgressListener?.invoke(totalChunks, totalChunks)
        } else {
            Log.e(TAG, "Failed to parse message data: $completeMessage")

            // Notify the listener about the error
            onChunkErrorListener?.invoke("Failed to parse message data")
        }

        // Reset chunked message state
        resetChunkedMessageState()

        return NfcProtocol.SELECT_OK_SW
    }

    /** Check if a command is a SELECT AID command */
    private fun isSelectAidCommand(command: ByteArray): Boolean {
        return command.size >= 6 &&
                command[0] == NfcProtocol.SELECT_APDU_HEADER[0] &&
                command[1] == NfcProtocol.SELECT_APDU_HEADER[1] &&
                command[2] == NfcProtocol.SELECT_APDU_HEADER[2] &&
                command[3] == NfcProtocol.SELECT_APDU_HEADER[3] &&
                command[4] == AID.size.toByte() &&
                command.sliceArray(5 until 5 + AID.size).contentEquals(AID)
    }

    /** Check if the app is in foreground */
    private fun isAppInForeground(): Boolean {
        val activityManager = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val appProcesses = activityManager.runningAppProcesses ?: return false

        val packageName = packageName
        for (appProcess in appProcesses) {
            if (appProcess.importance ==
                            ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND &&
                            appProcess.processName == packageName
            ) {
                return true
            }
        }
        return false
    }
}
