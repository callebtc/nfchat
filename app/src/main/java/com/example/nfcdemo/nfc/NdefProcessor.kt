package com.example.nfcdemo.nfc

import android.content.Context
import android.content.Intent
import android.nfc.NdefMessage
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Toast
import com.example.nfcdemo.MessageAdapter
import com.example.nfcdemo.R
import java.nio.charset.Charset
import java.util.Arrays
import kotlin.byteArrayOf

/**
 * Handles NDEF (NFC Data Exchange Format) message processing. Responsible for responding to
 * NDEF-related APDU commands.
 */
class NdefProcessor {
    companion object {
        private const val TAG = "NdefProcessor"
        
        // Delay in milliseconds before messageToSend is cleared
        private const val DELETE_MSG_TO_SEND_DELAY = 500L

        // Command Headers
        private val NDEF_SELECT_FILE_HEADER =
                byteArrayOf(0x00.toByte(), 0xA4.toByte(), 0x00.toByte(), 0x0C.toByte())

        // Step 1: Select AID (Application Identifier)
        private val NDEF_SELECT_AID =
                byteArrayOf(
                        0x00.toByte(), // CLA (Class)
                        0xA4.toByte(), // INS (Instruction)
                        0x04.toByte(), // P1 (Parameter 1)
                        0x00.toByte(), // P2 (Parameter 2)
                        0x07.toByte(), // Lc (Length of data)
                        0xD2.toByte(),
                        0x76.toByte(),
                        0x00.toByte(),
                        0x00.toByte(),
                        0x85.toByte(),
                        0x01.toByte(),
                        0x01.toByte(), // AID (Application Identifier)
                        0x00.toByte() // Le (Length of expected response)
                )

        // Step 2: Select CC File (Capability Container): 00 A4 00 0C 02 E1 03
        private val CC_FILE_ID = byteArrayOf(0xE1.toByte(), 0x03.toByte())
        // Minimal CC File (15 bytes)
        private val CC_FILE =
                byteArrayOf(
                        0x00,
                        0x0F, // CCLEN = 15
                        0x20, // Mapping version (2.0)
                        0x00,
                        0x3B, // MLe (max read)
                        0x00,
                        0x34, // MLc (max write)
                        0x04, // T (NDEF File Control TLV)
                        0x06, // L
                        0xE1.toByte(),
                        0x04.toByte(), // File ID
                        0x70.toByte(),
                        0xFF.toByte(), // Size: 0x70FF (28,671) bytes
                        0x00, // Read access (unrestricted)
                        0x00 // Write access (unrestricted)
                )

        // Step 3: Select NDEF File: 00 A4 00 0C 02 E1 04
        private val NDEF_FILE_ID = byteArrayOf(0xE1.toByte(), 0x04.toByte())

        // Default NDEF message
        private val DEFAULT_NDEF_FILE =
                byteArrayOf(
                        0x00,
                        0x0D, // NDEF length
                        0xD1.toByte(), // NDEF record header
                        0x01, // type length
                        0x09, // payload length
                        0x54, // 'T'
                        0x02,
                        0x65,
                        0x6E, // "en"
                        0x02,
                        0x68,
                        0x65,
                        0x6C,
                        0x6C,
                        0x6F // "hello"
                )

        // Step 4: Read Binary: 00 B0 offset_MSB offset_LSB length
        private val NDEF_READ_BINARY_HEADER = byteArrayOf(0x00.toByte(), 0xB0.toByte())

        // Step 5: Update Binary: 00 D6 offset_MSB offset_LSB length data...
        private val NDEF_UPDATE_BINARY_HEADER = byteArrayOf(0x00.toByte(), 0xD6.toByte())

        // Success and Error Responses
        private val NDEF_RESPONSE_OK = byteArrayOf(0x90.toByte(), 0x00.toByte())
        val NDEF_RESPONSE_ERROR = byteArrayOf(0x6A.toByte(), 0x82.toByte())

        private const val NDEF_MAX_MESSAGE_SIZE = 65536
    }

    // Message to be sent when in write mode
    private var messageToSend: String = ""
    
    // Store the full Message object
    private var messageObj: MessageAdapter.Message? = null
    
    // Handler for delayed message deletion
    private val handler = Handler(Looper.getMainLooper())
    private var deleteMessageRunnable: Runnable? = null

    // Callback for when a message is received via UPDATE BINARY
    var onNdefMessageReceived: ((MessageData) -> Unit)? = null
    
    // Callback for received text from an NDEF intent
    var onNdefTextReceived: ((String) -> Unit)? = null
    
    // Callback for when message is sent and we need to notify
    var onMessageSent: ((String) -> Unit)? = null

    // Flag to indicate if the processor is in write mode
    // private var isInWriteMode: Boolean = false

    // set to NDEF_FILE or dynamically created message
    private var ndefData = ByteArray(NDEF_MAX_MESSAGE_SIZE)
    private var receivedMessage: String = ""
    private var selectedFile: ByteArray? = null

    /** Set the message to be sent when in write mode */
    fun setMessageToSend(message: String) {
        messageToSend = message
        Log.d(TAG, "NdefProcessor: Message to send set: $message")
    }
    
    /** Set the Message object to be sent when in write mode */
    fun setMessageObj(message: MessageAdapter.Message) {
        messageObj = message
        messageToSend = message.content
        Log.d(TAG, "NdefProcessor: Message object set: ${message.content}, ID: ${message.messageId}")
    }

    /** Set whether the processor is in write mode */
    // fun setWriteMode(enabled: Boolean) {
    //     isInWriteMode = enabled
    //     Log.d(TAG, "NdefProcessor: Write mode set to $enabled")
    // }

    /**
     * Schedules deletion of messageToSend after delay.
     * Cancels any existing scheduled deletions first.
     */
    private fun scheduleMessageDeletion() {
        // Cancel any existing scheduled deletions
        cancelScheduledMessageDeletion()
        
        // Create a new runnable to clear the message
        deleteMessageRunnable = Runnable {
            Log.d(TAG, "NdefProcessor: Clearing message to send after timeout")
            // If we have a message object and onMessageSent callback, notify that message was sent
            messageObj?.let { message ->
                Log.d(TAG, "NdefProcessor: Notifying message was sent, ID: ${message.messageId}")
                onMessageSent?.invoke(message.messageId)
            }
            
            messageToSend = ""
            messageObj = null
            deleteMessageRunnable = null
        }
        
        // Schedule the deletion after the delay
        handler.postDelayed(deleteMessageRunnable!!, DELETE_MSG_TO_SEND_DELAY)
    }
    
    /**
     * Cancels any scheduled message deletion
     */
    private fun cancelScheduledMessageDeletion() {
        deleteMessageRunnable?.let {
            handler.removeCallbacks(it)
            deleteMessageRunnable = null
        }
    }

    /** Create an NDEF message from a string */
    private fun createNdefMessage(message: String): ByteArray {
        val languageCode = "en".toByteArray(Charsets.US_ASCII)
        val textBytes = message.toByteArray(Charsets.UTF_8)
        val statusByte = languageCode.size.toByte() // UTF-8 + language length
        val payload = byteArrayOf(statusByte, *languageCode, *textBytes)

        val type = "T".toByteArray()
        val recordHeader = byteArrayOf(
            0xD1.toByte(), // MB + ME + SR + TNF=1 (well-known)
            type.size.toByte(),
            payload.size.toByte(),
            *type,
            *payload
        )

        val ndefLength = payload.size + 3 + type.size
        val fullMessage = byteArrayOf(
            (ndefLength shr 8).toByte(),
            (ndefLength and 0xFF).toByte(),
            *recordHeader
        )

        return fullMessage
    }
    /** Processes an APDU command and returns the appropriate response */
    fun processCommandApdu(commandApdu: ByteArray): ByteArray {
        // When not in write mode, act as if the tag doesn't exist by returning error for all commands
        // if (isInWriteMode) {
        //     Log.d(TAG, "NdefProcessor: Not in read mode, ignoring APDU: ${byteArrayToHex(commandApdu)}")
        //     return NDEF_RESPONSE_ERROR
        // }
        
        // Check if NDEF AID is selected
        if (Arrays.equals(commandApdu, NDEF_SELECT_AID)) {
            Log.d(TAG, "NdefProcessor: NDEF AID selected")
            return NDEF_RESPONSE_OK
        }

        // Handle File Selection
        if (commandApdu.size >= 7 &&
                        Arrays.equals(commandApdu.sliceArray(0 until 4), NDEF_SELECT_FILE_HEADER)
        ) {
            Log.d(TAG, "NdefProcessor: SELECT FILE command received")
            return handleSelectFile(commandApdu)
        }

        // Handle Read Binary
        if (commandApdu.size >= 2 &&
                        Arrays.equals(commandApdu.sliceArray(0 until 2), NDEF_READ_BINARY_HEADER)
        ) {
            Log.d(TAG, "NdefProcessor: READ BINARY command received")
            return handleReadBinary(commandApdu)
        }

        // Handle Update Binary
        if (commandApdu.size >= 2 &&
                        Arrays.equals(commandApdu.sliceArray(0 until 2), NDEF_UPDATE_BINARY_HEADER)
        ) {
            Log.d(TAG, "NdefProcessor: UPDATE BINARY command received: ${byteArrayToHex(commandApdu)}")
            return handleUpdateBinary(commandApdu)
        }

        Log.d(TAG, "NdefProcessor: Invalid APDU received: ${byteArrayToHex(commandApdu)}")
        return NDEF_RESPONSE_ERROR
    }

    /** Handles SELECT FILE commands */
    private fun handleSelectFile(apdu: ByteArray): ByteArray {
        val fileId = apdu.sliceArray(5 until 7)
        
        // // If we're not in write mode, act as if the tag doesn't exist by returning error
        // if (isInWriteMode) {
        //     Log.d(TAG, "NdefProcessor: Not in read mode, acting as non-existent tag")
        //     return NDEF_RESPONSE_ERROR
        // }

        
        return when {
            Arrays.equals(fileId, CC_FILE_ID) -> {
                selectedFile = CC_FILE
                Log.d(TAG, "NdefProcessor: CC File selected")
                NDEF_RESPONSE_OK
            }
            Arrays.equals(fileId, NDEF_FILE_ID) -> {
                if (messageToSend.isEmpty()) {
                    Log.d(TAG, "NdefProcessor: No message to send, returning empty message")
                    selectedFile = createNdefMessage("")
                    return NDEF_RESPONSE_OK
                }
                Log.d(TAG, "NdefProcessor: NDEF File selected, using message: $messageToSend")
                selectedFile = createNdefMessage(messageToSend)
                scheduleMessageDeletion()
                return NDEF_RESPONSE_OK
            }
            else -> {
                Log.e(TAG, "NdefProcessor: Unknown file selected")
                NDEF_RESPONSE_ERROR
            }
        }
    }

    /** Handles READ BINARY commands (00 B0 offset_MSB offset_LSB length) */
    private fun handleReadBinary(apdu: ByteArray): ByteArray {
        if (selectedFile == null || apdu.size < 5) return NDEF_RESPONSE_ERROR
        val selectedFile = selectedFile!!
        
        val length = (apdu[4].toInt() and 0xFF).let { if (it == 0) 256 else it }
        val offset = ((apdu[2].toInt() and 0xFF) shl 8) or (apdu[3].toInt() and 0xFF)
        if (offset + length > selectedFile.size) return NDEF_RESPONSE_ERROR

        val data = selectedFile.sliceArray(offset until offset + length)
        val response = byteArrayOf(*data, *NDEF_RESPONSE_OK)
        
        Log.d(TAG, "NdefProcessor: READ BINARY requested ${length} bytes at offset ${offset}")
        Log.d(TAG, "NdefProcessor: READ BINARY response: ${byteArrayToHex(response)}")
        Log.d(TAG, "NdefProcessor: READ BINARY response string: ${String(data)}")
        return response
    }

    /** Handles UPDATE BINARY commands (00 D6 offset_MSB offset_LSB length data...) */
    private fun handleUpdateBinary(apdu: ByteArray): ByteArray {
        if (selectedFile == null || apdu.size < 5) {
            Log.e(TAG, "UPDATE BINARY selectedFile is null or apdu.size < 5")
            return NDEF_RESPONSE_ERROR
        }

        val offset = ((apdu[2].toInt() and 0xFF) shl 8) or (apdu[3].toInt() and 0xFF)
        val dataLength = (apdu[4].toInt() and 0xFF)

        if (apdu.size < 5 + dataLength) {
            Log.e(TAG, "UPDATE BINARY apdu.size < 5 + dataLength")
            return NDEF_RESPONSE_ERROR
        }

        // Cannot write to CC file
        if (selectedFile!!.contentEquals(CC_FILE)) {
            Log.e(TAG, "Attempt to write to CC file is forbidden")
            return NDEF_RESPONSE_ERROR
        }

        val data = apdu.sliceArray(5 until 5 + dataLength)

        // Prevent overflow
        if (offset + dataLength > ndefData.size) {
            Log.e(TAG, "UPDATE BINARY command would overflow NDEF data buffer")
            return NDEF_RESPONSE_ERROR
        }

        
        Log.d(
                TAG,
                "NdefProcessor: UPDATE BINARY success, updated ${dataLength} bytes at offset ${offset}"
        )
        Log.d(TAG, "NdefProcessor: UPDATE BINARY data: ${String(data)}")
        Log.d(TAG, "NdefProcessor: UPDATE BINARY data: ${byteArrayToHex(data)}")

        // store the data
        System.arraycopy(data, 0, ndefData, offset, dataLength)
        
    
        // If the transfer is complete, process the message
        if (offset == 0 && dataLength == 2) {
            try {
            // NDEF messages start with a length field
            val ndefLength = ((ndefData[0].toInt() and 0xFF) shl 8) or (ndefData[1].toInt() and 0xFF)
            // If we have the complete message already
            if (ndefLength + 2 <= ndefData.size) { 
                try {
                    processReceivedNdefMessage(ndefData)
                } catch (e: Exception) {
                    Log.e(TAG, "Error processing received NDEF message: ${e.message}")
                }
                
            } else {
                Log.d(TAG, "NdefProcessor: Not all data received, waiting for more")
            }
            } catch (e: Exception) {
                Log.e(TAG, "Error processing received NDEF message: ${e.message}")
            }
        }
        

        return NDEF_RESPONSE_OK
    }


    
    private fun processReceivedNdefMessage(ndefData: ByteArray) {
        Log.d(TAG, "Processing received NDEF message of length ${ndefData.size}")
        Log.d(
            TAG, "Hex dump: " +
                    ndefData.sliceArray(0 until ndefData.size.coerceAtMost(64))
                        .joinToString(" ") { "%02X".format(it) }
        )
    
        var offset = 0
        var totalLength = 0
        // Detect framing:
        
        // Type 2: starts with TLV tag 0x03 followed by one byte length
        // Type 4: first two bytes form the NDEF file length
        if (ndefData.isNotEmpty() && ndefData[0] == 0x03.toByte()) {
            if (ndefData.size < 2) {
                Log.e(TAG, "Invalid NDEF data")
                return
            }
            totalLength = ndefData[1].toInt() and 0xFF
            offset = 2  // Skip the TLV tag and length
        } else if (ndefData.size >= 2) {
            Log.d(TAG, "Type 4 style NDEF")
            totalLength = ((ndefData[0].toInt() and 0xFF) shl 8) or (ndefData[1].toInt() and 0xFF)
            offset = 2
        } else {
            Log.e(TAG, "Invalid NDEF data")
            return
        }
    
        try {
            // Read record header starting at offset
            val header = ndefData[offset]
            val typeLength = ndefData[offset + 1].toInt() and 0xFF
            // Determine payload length field size based on the SR flag (0x10)
            var payloadLength = 0
            var typeFieldStart: Int
            if ((header.toInt() and 0x10) != 0) { // Short record: 1 byte payload length
                payloadLength = ndefData[offset + 2].toInt() and 0xFF
                typeFieldStart = offset + 3
            } else { // Normal record: payload length is 4 bytes
                payloadLength = ((ndefData[offset + 2].toInt() and 0xFF) shl 24) or
                        ((ndefData[offset + 3].toInt() and 0xFF) shl 16) or
                        ((ndefData[offset + 4].toInt() and 0xFF) shl 8) or
                        (ndefData[offset + 5].toInt() and 0xFF)
                typeFieldStart = offset + 6
            }
            // Verify the record type is "T" (0x54) for a Text record
            if (ndefData[typeFieldStart] != 0x54.toByte()) {
                Log.d(TAG, "NDEF message is not a Text Record. Found type: ${ndefData[typeFieldStart].toChar()}, returning")
                return
            }

            // Payload starts immediately after the type field
            val payloadStart = typeFieldStart + typeLength
            if (payloadStart >= ndefData.size) {
                Log.e(TAG, "Payload start index out of bounds, returning")
                return
            }
    
            // For a Text record, first payload byte is the status byte.
            val status = ndefData[payloadStart]
            // Lower 6 bits of status indicate the language code length.
            val languageCodeLength = status.toInt() and 0x3F
            val textStart = payloadStart + 1 + languageCodeLength
            val textLength = payloadLength - 1 - languageCodeLength

            if (textStart + textLength > ndefData.size) {
                Log.e(TAG, "Text extraction bounds exceed data size.")
            }
    
            val textBytes = ndefData.sliceArray(textStart until textStart + textLength)
            val text = String(textBytes)
            receivedMessage = text
            Log.d(TAG, "Extracted text: $text")
    
            val messageData = MessageData(text)
            onNdefMessageReceived?.invoke(messageData)
            ndefData.fill(0)
        } catch (e: Exception) {
            Log.e(TAG, "Error extracting text from NDEF message: ${e.message}")
        }
    }

    fun getReceivedMessage(): String {
        return receivedMessage
    }

    /** Convert a byte array to a hex string */
    private fun byteArrayToHex(bytes: ByteArray): String {
        return bytes.joinToString("") { "%02X".format(it) }
    }

    /**
     * Handles an NFC NDEF intent from a card or tag
     * @param context Context for displaying toast messages
     * @param intent The intent from the NFC discovery
     */
    fun handleNfcNdefCardIntent(context: Context, intent: Intent) {
        Log.d(TAG, "NdefProcessor handleNfcNdefCardIntent ${intent.action}")
        if (NfcAdapter.ACTION_NDEF_DISCOVERED == intent.action) {
            val rawMessages = intent.getParcelableArrayExtra(NfcAdapter.EXTRA_NDEF_MESSAGES)
            if (rawMessages != null) {
                val messages = rawMessages.map { it as NdefMessage }
                // Process NDEF messages
                for (message in messages) {
                    for (record in message.records) {
                        if (record.toMimeType()?.contentEquals("text/plain") == true) {
                            val payload = record.payload
                            // Get the text encoding
                            val textEncoding =
                                    if ((payload[0].toInt() and 128) == 0
                                    ) { // Bit 7 signals encoding. 0 for UTF-8, 1 for UTF-16.
                                        Charset.forName("UTF-8")
                                    } else {
                                        Charset.forName("UTF-16")
                                    }
                            // Get the language code
                            val languageCodeLength =
                                    payload[0].toInt() and
                                            0x3f // Bits 5..0 reserve for language code length.
                            // Get the actual text data by decoding the payload
                            val textData =
                                    String(
                                            payload,
                                            languageCodeLength + 1,
                                            payload.size - languageCodeLength - 1,
                                            textEncoding
                                    )
                            Log.d(TAG, "Received NDEF message: $textData")
                            // Notify via callback
                            onNdefTextReceived?.invoke(textData)
                        }
                    }
                }
            } else {
                // Tag might not contain NDEF data
                val tag = intent.getParcelableExtra<Tag>(NfcAdapter.EXTRA_TAG)
                if (tag != null) {
                    Log.d(TAG, "Received NFC tag without NDEF data")
                    Toast.makeText(context, context.getString(R.string.nfc_tag_detected), Toast.LENGTH_SHORT).show()
                }
            }
        } else {
            Log.d(TAG, "Not handling ANOTHER NFC intent: ${intent.action}")
        }
    }
}
