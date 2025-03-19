package com.example.nfcdemo.nfc

import android.util.Log
import java.util.Arrays
import kotlin.byteArrayOf

/**
 * Handles NDEF (NFC Data Exchange Format) message processing. Responsible for responding to
 * NDEF-related APDU commands.
 */
class NdefProcessor {
    companion object {
        private const val TAG = "NdefProcessor"

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

    // Callback for when a message is received via UPDATE BINARY
    var onNdefMessageReceived: ((MessageData) -> Unit)? = null

    // Flag to indicate if the processor is in write mode
    private var isInWriteMode: Boolean = false

    // set to NDEF_FILE or dynamically created message
    private var ndefData = ByteArray(NDEF_MAX_MESSAGE_SIZE)
    private var selectedFile: ByteArray? = null

    /** Set the message to be sent when in write mode */
    fun setMessageToSend(message: String) {
        messageToSend = message
        Log.d(TAG, "NdefProcessor: Message to send set: $message")
    }

    /** Set whether the processor is in write mode */
    fun setWriteMode(enabled: Boolean) {
        isInWriteMode = enabled
        Log.d(TAG, "NdefProcessor: Write mode set to $enabled")
    }

    /** Create an NDEF message from a string */
    private fun createNdefMessage(message: String): ByteArray {
        val payload = message.toByteArray()
        val language = byteArrayOf(0x02) + "en".toByteArray()
        val payloadLength = payload.size + language.size
        val type = "T".toByteArray()
        val typeLength = type.size
        val ndefLength = 3 + typeLength + payloadLength
        val ndefData =
                byteArrayOf(
                        0x00,
                        ndefLength.toByte(), // NDEF length
                        0xD1.toByte(), // NDEF record header
                        typeLength.toByte(), // type length
                        payloadLength.toByte(), // payload length
                        *type, // 'T'
                        *language, // "en"
                        *payload // message
                )
        Log.d(TAG, "NdefProcessor: Created NDEF message: ${byteArrayToHex(ndefData)}")
        return ndefData
    }

    /** Handles SELECT FILE commands */
    private fun handleSelectFile(apdu: ByteArray): ByteArray {
        val fileId = apdu.sliceArray(5 until 7)
        return when {
            Arrays.equals(fileId, CC_FILE_ID) -> {
                selectedFile = CC_FILE
                Log.d(TAG, "NdefProcessor: CC File selected")
                NDEF_RESPONSE_OK
            }
            Arrays.equals(fileId, NDEF_FILE_ID) -> {
                // If in write mode and has a message to send, use that message
                selectedFile =
                        if (isInWriteMode && messageToSend.isNotEmpty()) {
                            createNdefMessage(messageToSend)
                        } else {
                            // Otherwise use default message or empty
                            createNdefMessage("hello")
                        }
                Log.d(TAG, "NdefProcessor: NDEF File selected, write mode: $isInWriteMode")
                NDEF_RESPONSE_OK
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

        // Always return "empty" data and success
        val length = (apdu[4].toInt() and 0xFF).let { if (it == 0) 256 else it }
        val selectedFile = selectedFile!!
        val offset = ((apdu[2].toInt() and 0xFF) shl 8) or (apdu[3].toInt() and 0xFF)

        if (offset + length > selectedFile.size) return NDEF_RESPONSE_ERROR

        val data = selectedFile.sliceArray(offset until offset + length)
        val response = byteArrayOf(*data, *NDEF_RESPONSE_OK)
        Log.d(TAG, "NdefProcessor: READ BINARY requested ${length} bytes at offset ${offset}")
        Log.d(TAG, "NdefProcessor: READ BINARY response: ${byteArrayToHex(response)}")
        return response
    }

    /** Handles UPDATE BINARY commands (00 D6 offset_MSB offset_LSB length data...) */
    private fun handleUpdateBinary(apdu: ByteArray): ByteArray {
        if (selectedFile == null || apdu.size < 5) return NDEF_RESPONSE_ERROR

        val offset = ((apdu[2].toInt() and 0xFF) shl 8) or (apdu[3].toInt() and 0xFF)
        val dataLength = (apdu[4].toInt() and 0xFF)

        if (apdu.size < 5 + dataLength) return NDEF_RESPONSE_ERROR

        // Cannot write to CC file
        if (selectedFile!!.contentEquals(CC_FILE)) {
            Log.e(TAG, "Attempt to write to CC file is forbidden")
            return NDEF_RESPONSE_ERROR
        }

        val data = apdu.sliceArray(5 until 5 + dataLength)

        // Prevent overflow
        if (offset + dataLength > ndefData.size) return NDEF_RESPONSE_ERROR

        System.arraycopy(data, 0, ndefData, offset, dataLength)
        Log.d(
                TAG,
                "NdefProcessor: UPDATE BINARY success, updated ${dataLength} bytes at offset ${offset}"
        )

        // Try to extract and process the message
        try {
            // If offset is 0, this might be the start of an NDEF message
            if (offset == 0 && dataLength > 2) {
                // NDEF messages start with a length field
                val ndefLength = ((data[0].toInt() and 0xFF) shl 8) or (data[1].toInt() and 0xFF)

                // If we have the complete message already
                if (dataLength >= ndefLength + 2) {
                    processReceivedNdefMessage(data)
                }
            }
            // If we're writing elsewhere in the message, try to process the whole thing
            else if (offset + dataLength > 2) {
                val totalLength =
                        ((ndefData[0].toInt() and 0xFF) shl 8) or (ndefData[1].toInt() and 0xFF)
                if (offset + dataLength >= totalLength + 2) {
                    processReceivedNdefMessage(ndefData.sliceArray(0 until totalLength + 2))
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error processing received NDEF message: ${e.message}")
        }

        return NDEF_RESPONSE_OK
    }

    /** Process a received NDEF message and extract the text content */
    private fun processReceivedNdefMessage(ndefData: ByteArray) {
        try {
            // Simple parsing of NDEF Text Record (TNF=1, RTD_TEXT)
            if (ndefData.size > 7 && ndefData[2].toInt() == 0xD1 && ndefData[5].toInt() == 0x54) {
                // Get the payload length
                val payloadLength = ndefData[4].toInt() and 0xFF

                // Skip language code
                val languageCodeLength = ndefData[6].toInt() and 0xFF
                val textStart = 7 + languageCodeLength

                // Extract the text
                if (textStart < ndefData.size &&
                                textStart + payloadLength - languageCodeLength - 1 <= ndefData.size
                ) {
                    val textBytes =
                            ndefData.sliceArray(
                                    textStart until
                                            textStart + payloadLength - languageCodeLength - 1
                            )
                    val text = String(textBytes)
                    Log.d(TAG, "NdefProcessor: Extracted text from NDEF message: $text")

                    // Create MessageData and send to callback
                    val messageData = MessageData(text)
                    onNdefMessageReceived?.invoke(messageData)
                }
            } else {
                // Try to extract as JSON in case it's formatted that way
                val textBytes = ndefData.sliceArray(2 until ndefData.size)
                val text = String(textBytes)

                // Check if it's a valid JSON and contains our expected format
                if (MessageData.isValidJson(text)) {
                    val messageData = MessageData.fromJson(text)
                    if (messageData != null) {
                        Log.d(
                                TAG,
                                "NdefProcessor: Extracted JSON from NDEF message: ${messageData.content}"
                        )
                        onNdefMessageReceived?.invoke(messageData)
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error extracting text from NDEF message: ${e.message}")
        }
    }

    /** Processes an APDU command and returns the appropriate response */
    fun processCommandApdu(commandApdu: ByteArray): ByteArray {
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
            Log.d(TAG, "NdefProcessor: UPDATE BINARY command received")
            return handleUpdateBinary(commandApdu)
        }

        Log.d(TAG, "NdefProcessor: Invalid APDU received: ${byteArrayToHex(commandApdu)}")
        return NDEF_RESPONSE_ERROR
    }

    /** Convert a byte array to a hex string */
    private fun byteArrayToHex(bytes: ByteArray): String {
        return bytes.joinToString("") { "%02X".format(it) }
    }
}
