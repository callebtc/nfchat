package com.example.nfcdemo.nfc

import android.util.Log
import java.util.Arrays

/**
 * Handles NDEF (NFC Data Exchange Format) message processing.
 * Responsible for responding to NDEF-related APDU commands.
 */
class NdefProcessor {
    companion object {
        private const val TAG = "NdefProcessor"

        // Constants for NDEF Tag Emulation
        // Define APDU command for selecting the application
        private val NDEF_SELECT_APDU = byteArrayOf(
            0x00.toByte(), // CLA (Class)
            0xA4.toByte(), // INS (Instruction)
            0x04.toByte(), // P1 (Parameter 1)
            0x00.toByte(), // P2 (Parameter 2)
            0x07.toByte(), // Lc (Length of data)
            0xD2.toByte(), 0x76.toByte(), 0x00.toByte(), 0x00.toByte(), 0x85.toByte(),
            0x01.toByte(), 0x01.toByte(), // AID (Application Identifier)
            0x00.toByte()  // Le (Length of expected response)
        )

        // NDEF File ID related constants
        private val NDEF_FILE_ID = byteArrayOf(0xE1.toByte(), 0x03.toByte())
        private val NDEF_FILE_ID_LENGTH = byteArrayOf(0x02.toByte())
        private val NDEF_SELECT_FILE_HEADER = byteArrayOf(0x00.toByte(), 0xA4.toByte(), 0x00.toByte(), 0x0C.toByte())

        // Read command related constants
        private val NDEF_READ_BINARY_HEADER = byteArrayOf(0x00.toByte(), 0xB0.toByte())

        // Write command related constants
        private val NDEF_UPDATE_BINARY_HEADER = byteArrayOf(0x00.toByte(), 0xD6.toByte())

        // Response APDU for successful command
        private val NDEF_RESPONSE_OK = byteArrayOf(0x90.toByte(), 0x00.toByte())

        // Response APDU for error
        private val NDEF_RESPONSE_ERROR = byteArrayOf(0x6A.toByte(), 0x82.toByte())

        // APDU command for SELECT
        private val NDEF_SELECT_APDU_HEADER = byteArrayOf(0x00.toByte(), 0xA4.toByte(), 0x04.toByte(), 0x00.toByte())

        // Initial NDEF file header (Type 4 Tag format) - indicates writable tag with max size
        private val NDEF_INITIAL_HEADER = byteArrayOf(
            0xE1.toByte(), // Magic Number
            0x03.toByte(), // Version
            0x00.toByte(), 0xFF.toByte(), // Maximum NDEF data size (255 bytes - increased from 24)
            0x00.toByte(), 0x00.toByte(), // NDEF File Length (initially 0)
            0x00.toByte(), 0x00.toByte()  // NDEF Message Length (initially 0)
        )

        private val CC_FILE_ID = byteArrayOf(0xE1.toByte(), 0x00.toByte())

        // Minimal valid Type 4 CC (15 bytes)
        private val CC_FILE = byteArrayOf(
            0x00, 0x0F,      // CCLEN = 15
            0x20,            // Mapping version (2.0)
            0x00, 0x3B,      // MLe (max read)
            0x00, 0x34,      // MLc (max write)
            0x04,            // T (NDEF File Control TLV)
            0x06,            // L
            0xE1.toByte(), 0x03.toByte(), // File ID
            0x00, 0xFF.toByte(),          // Max NDEF size
            0x00,            // Read access (unrestricted)
            0x00             // Write access (unrestricted)
        )


        // NDEF Length byte positions in the data
        private const val NDEF_FILE_LENGTH_POSITION = 4
        private const val NDEF_MESSAGE_LENGTH_POSITION = 6

        // Maximum supported NDEF message size
        private const val NDEF_MAX_MESSAGE_SIZE = 254
    }

    private var ndefData =
    private var currentMessage: String = ""
    private var receivedNdefMessage: ByteArray? = null

    /**
     * Sets the current message to be shared via NDEF
     */
    fun setMessage(message: String) {
        currentMessage = message
        Log.d(TAG, "Message set: $message")
    }

    /**
     * Returns the last received NDEF message content as a String, or null if none
     */
    fun getReceivedMessage(): String? {
        if (receivedNdefMessage == null) {
            return null
        }

        // Since we now store the already-parsed message in receivedNdefMessage,
        // we can just convert it directly to a string
        return String(receivedNdefMessage!!, Charsets.UTF_8)
    }

    /**
     * Processes an APDU command and returns the appropriate response
     * @param commandApdu The APDU command to process
     * @return The response APDU
     */
    fun processCommandApdu(commandApdu: ByteArray): ByteArray {
        Log.d(TAG, "Processing APDU: ${byteArrayToHex(commandApdu)}")

        // Check if NDEF AID is selected
        if (Arrays.equals(commandApdu, NDEF_SELECT_APDU)) {
            Log.d(TAG, "NDEF Application selected")
            return NDEF_RESPONSE_OK
        }

        // Check if NDEF File is selected 
        if (commandApdu.contentEquals(NDEF_SELECT_FILE_HEADER + NDEF_FILE_ID_LENGTH + NDEF_FILE_ID)) {
            Log.d(TAG, "NDEF File selected")
            return NDEF_RESPONSE_OK
        }

        // Check if reading NDEF binary data
        if (commandApdu.size >= 2 &&
            Arrays.equals(commandApdu.sliceArray(0 until 2), NDEF_READ_BINARY_HEADER)) {
            return handleReadBinary(commandApdu)
        }

        // Check if writing NDEF binary data
        if (commandApdu.size >= 2 &&
            Arrays.equals(commandApdu.sliceArray(0 until 2), NDEF_UPDATE_BINARY_HEADER)) {
            return handleUpdateBinary(commandApdu)
        }

        // Check if selecting application with data
        if (Arrays.equals(commandApdu, NDEF_SELECT_APDU_HEADER)) {
            Log.d(TAG, "NDEF Application selected - with data request")
            // Handle message sending after selection
            val messageBytes = currentMessage.toByteArray(Charsets.UTF_8)
            val response = NDEF_SELECT_APDU + messageBytes + NDEF_RESPONSE_OK
            Log.d(TAG, "NDEF Sending message: $currentMessage")
            return response
        }

        // If none of the above, it's an invalid APDU
        Log.d(TAG, "NDEF Invalid APDU received: ${byteArrayToHex(commandApdu)}")
        return NDEF_RESPONSE_ERROR
    }

    /**
     * Handles READ BINARY commands (C-APDU: 00 B0 offset_MSB offset_LSB length)
     */
    private fun handleReadBinary(apdu: ByteArray): ByteArray {
        Log.d(TAG, "READ BINARY command received")
        if (ndefData == null || apdu.size < 5) return NDEF_RESPONSE_ERROR

        val offset = ((apdu[2].toInt() and 0xFF) shl 8) or (apdu[3].toInt() and 0xFF)
        val length = (apdu[4].toInt() and 0xFF)

        val file = ndefData!!
        if (offset + length > file.size) return NDEF_RESPONSE_ERROR

        val responseData = file.copyOfRange(offset, offset + length)
        Log.d(TAG, "READ BINARY response length: ${responseData.size} bytes")
        Log.d(TAG, "READ BINARY response: ${byteArrayToHex(responseData)}")
        return responseData 
    }
    // private fun handleReadBinary(commandApdu: ByteArray): ByteArray {
    //     Log.d(TAG, "READ BINARY command received")
    //     // For the READ BINARY command, we need at least 5 bytes
    //     if (commandApdu.size < 5) {
    //         Log.e(TAG, "Invalid READ BINARY command: not enough bytes (${commandApdu.size})")
    //         return NDEF_RESPONSE_ERROR
    //     }

    //     val p1 = commandApdu[2].toInt() and 0xFF // MSB of offset
    //     val p2 = commandApdu[3].toInt() and 0xFF // LSB of offset
    //     val offset = (p1 shl 8) or p2

    //     // Length is either last byte (Le) or default to remaining NDEF data
    //     val requestedLength = if (commandApdu.size > 4) {
    //         commandApdu[4].toInt() and 0xFF
    //     } else {
    //         ndefData.size - offset
    //     }

    //     Log.d(TAG, "READ BINARY with offset: $offset, length: $requestedLength")

    //     // Make sure we don't read past the end of our data
    //     if (offset >= ndefData.size) {
    //         Log.e(TAG, "Invalid offset: $offset (exceeds data size ${ndefData.size})")
    //         return NDEF_RESPONSE_ERROR
    //     }

    //     val actualLength = minOf(requestedLength, ndefData.size - offset)

    //     val dataToReturn = ndefData.sliceArray(offset until offset + actualLength) + NDEF_RESPONSE_OK
        
    //     Log.d(TAG, "READ BINARY response length: ${dataToReturn.size} bytes")   
    //     Log.d(TAG, "READ BINARY response: ${byteArrayToHex(dataToReturn)}")
        
    //     return dataToReturn
    // }

    /**
     * Handles UPDATE BINARY commands (C-APDU: 00 D6 offset_MSB offset_LSB length data...)
     */
    private fun handleUpdateBinary(commandApdu: ByteArray): ByteArray {
        Log.d(TAG, "UPDATE BINARY command received")
        // For the UPDATE BINARY command, we need at least 5 bytes
        if (commandApdu.size < 5) {
            Log.e(TAG, "Invalid UPDATE BINARY command: not enough bytes")
            return NDEF_RESPONSE_ERROR
        }

        val p1 = commandApdu[2].toInt() and 0xFF // MSB of offset
        val p2 = commandApdu[3].toInt() and 0xFF // LSB of offset
        val offset = (p1 shl 8) or p2
        val dataLength = commandApdu[4].toInt() and 0xFF

        // Check if data length matches actual data
        if (commandApdu.size < 5 + dataLength) {
            Log.e(TAG, "Invalid UPDATE BINARY command: data length mismatch")
            return NDEF_RESPONSE_ERROR
        }

        Log.d(TAG, "UPDATE BINARY with offset: $offset, length: $dataLength")

        // Extract the data
        val data = commandApdu.sliceArray(5 until 5 + dataLength)

        // Log what we're about to write
        Log.d(TAG, "Writing data at offset $offset: ${byteArrayToHex(data)}")

        // If this is a write to the CC bytes (first 4 bytes), we should reject it
        // These should be read-only according to NFC Forum Type 4 Tag spec
        if (offset < 4) {
            Log.e(TAG, "Attempt to write to read-only CC bytes")
            return NDEF_RESPONSE_ERROR
        }

        // Make sure we don't write past the end of our buffer
        if (offset + dataLength > ndefData.size) {
            // Resize the buffer if needed (up to a reasonable maximum)
            if (offset + dataLength <= NDEF_MAX_MESSAGE_SIZE) {
                val newNdefData = ByteArray(offset + dataLength)
                System.arraycopy(ndefData, 0, newNdefData, 0, ndefData.size)
                ndefData = newNdefData
            } else {
                Log.e(TAG, "Invalid offset/length: would exceed maximum NDEF size")
                return NDEF_RESPONSE_ERROR
            }
        }

        // Write the data to our buffer
        System.arraycopy(data, 0, ndefData, offset, dataLength)

        // Check for specific updates that might indicate a complete message
        if (offset <= NDEF_FILE_LENGTH_POSITION && offset + dataLength > NDEF_FILE_LENGTH_POSITION) {
            // We're writing to the file/message length section
            val fileLength = ((ndefData[NDEF_FILE_LENGTH_POSITION].toInt() and 0xFF) shl 8) or 
                             (ndefData[NDEF_FILE_LENGTH_POSITION + 1].toInt() and 0xFF)
            
            val messageLength = ((ndefData[NDEF_MESSAGE_LENGTH_POSITION].toInt() and 0xFF) shl 8) or 
                                (ndefData[NDEF_MESSAGE_LENGTH_POSITION + 1].toInt() and 0xFF)
            
            Log.d(TAG, "Updated NDEF length fields - File Length: $fileLength, Message Length: $messageLength")
        }

        // Check for a complete NDEF message after every write
        checkForCompleteNdefMessage()

        Log.d(TAG, "UPDATE BINARY success, updated ${dataLength} bytes at offset ${offset}")
        return NDEF_RESPONSE_OK
    }

    /**
     * Checks if we have a complete NDEF message and processes it if so
     */
    private fun checkForCompleteNdefMessage() {
        try {
            // NDEF File Length is at bytes 4-5 (big-endian)
            // NDEF Message Length is at bytes 6-7 (big-endian)
            if (ndefData.size >= 8) {
                // Get file length (2 bytes)
                val fileLength = ((ndefData[NDEF_FILE_LENGTH_POSITION].toInt() and 0xFF) shl 8) or 
                                (ndefData[NDEF_FILE_LENGTH_POSITION + 1].toInt() and 0xFF)
                
                // Get message length (2 bytes)
                val messageLength = ((ndefData[NDEF_MESSAGE_LENGTH_POSITION].toInt() and 0xFF) shl 8) or 
                                    (ndefData[NDEF_MESSAGE_LENGTH_POSITION + 1].toInt() and 0xFF)
                
                Log.d(TAG, "NDEF file length: $fileLength, message length: $messageLength")
                
                // Check if we have enough data for the complete message
                if (messageLength > 0 && ndefData.size >= 8 + messageLength) {
                    Log.d(TAG, "Detected complete NDEF message of length $messageLength")
                    
                    // Extract the NDEF message
                    val ndefMessage = ndefData.sliceArray(8 until 8 + messageLength)
                    
                    // Log the raw message
                    Log.d(TAG, "Received NDEF message data: ${byteArrayToHex(ndefMessage)}")
                    
                    // Parse the NDEF message to extract the content
                    val messageContent = parseNdefMessage(ndefMessage)
                    if (messageContent != null) {
                        Log.d(TAG, "Parsed NDEF message content: $messageContent")
                        // Store the parsed content directly
                        receivedNdefMessage = messageContent.toByteArray(Charsets.UTF_8)
                        // Log the parsed message content with higher visibility
                        Log.i(TAG, "🔥 FINAL NDEF MESSAGE RECEIVED: $messageContent 🔥")
                    } else {
                        Log.d(TAG, "Could not parse NDEF message content, storing raw NDEF message")
                        receivedNdefMessage = ndefMessage
                        Log.i(TAG, "🔥 FINAL RAW NDEF MESSAGE RECEIVED: ${byteArrayToHex(ndefMessage)} 🔥")
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error checking for complete NDEF message: ${e.message}")
            e.printStackTrace()
        }
    }

    /**
     * Parses an NDEF message to extract text content
     */
    private fun parseNdefMessage(ndefMessage: ByteArray): String? {
        try {
            Log.d(TAG, "Parsing NDEF message: ${byteArrayToHex(ndefMessage)}")

            if (ndefMessage.isEmpty()) return null

            // First byte contains the header flags
            val header = ndefMessage[0].toInt() and 0xFF
            val typeNameFormat = header and 0x07
            val isShortRecord = (header and 0x10) != 0
            val hasIdLength = (header and 0x08) != 0
            val isFirstRecord = (header and 0x80) != 0
            val isLastRecord = (header and 0x40) != 0

            Log.d(TAG, "NDEF record header: TNF=$typeNameFormat, SR=$isShortRecord, IL=$hasIdLength, MB=$isFirstRecord, ME=$isLastRecord")

            // Minimum valid record has header, type length, and payload length
            if (ndefMessage.size < 3) return null

            // Second byte is type length
            val typeLength = ndefMessage[1].toInt() and 0xFF
            var currentPos = 2

            // Next 1 or 4 bytes is payload length
            val payloadLength = if (isShortRecord) {
                // Short record: 1 byte payload length
                ndefMessage[currentPos++].toInt() and 0xFF
            } else {
                // Regular record: 4 bytes payload length (big-endian)
                if (ndefMessage.size < 6) return null

                val length = ((ndefMessage[currentPos++].toInt() and 0xFF) shl 24) or
                             ((ndefMessage[currentPos++].toInt() and 0xFF) shl 16) or
                             ((ndefMessage[currentPos++].toInt() and 0xFF) shl 8) or
                             (ndefMessage[currentPos++].toInt() and 0xFF)
                length
            }

            Log.d(TAG, "NDEF payload length: $payloadLength")

            // ID Length field is present if IL flag is set
            val idLength = if (hasIdLength) {
                if (currentPos >= ndefMessage.size) return null
                ndefMessage[currentPos++].toInt() and 0xFF
            } else {
                0
            }

            // Type field
            if (typeLength > 0) {
                if (currentPos + typeLength > ndefMessage.size) return null

                val type = ndefMessage.sliceArray(currentPos until currentPos + typeLength)
                val typeStr = String(type, Charsets.US_ASCII)
                Log.d(TAG, "NDEF record type: $typeStr")
                
                currentPos += typeLength

                // ID field
                if (idLength > 0) {
                    currentPos += idLength // Skip ID field
                }

                // Payload field
                if (currentPos + payloadLength > ndefMessage.size) return null

                val payload = ndefMessage.sliceArray(currentPos until currentPos + payloadLength)

                // Process based on TNF and type
                if (typeNameFormat == 1) { // NFC Forum well-known type
                    if (typeStr == "T") { // Text record
                        // Text record format: status byte + language code + text
                        if (payload.isNotEmpty()) {
                            val statusByte = payload[0].toInt() and 0xFF
                            val languageCodeLength = statusByte and 0x3F
                            val isUTF16 = (statusByte and 0x80) != 0

                            if (1 + languageCodeLength < payload.size) {
                                val languageCode = String(payload.sliceArray(1 until 1 + languageCodeLength), Charsets.US_ASCII)
                                Log.d(TAG, "Text record language code: $languageCode")
                                
                                val textBytes = payload.sliceArray(1 + languageCodeLength until payload.size)

                                val text = if (isUTF16) {
                                    String(textBytes, Charsets.UTF_16)
                                } else {
                                    String(textBytes, Charsets.UTF_8)
                                }

                                Log.d(TAG, "Parsed text from NDEF: $text")
                                return text
                            }
                        }
                    } else if (typeStr == "U") { // URI record
                        if (payload.isNotEmpty()) {
                            val prefixCode = payload[0].toInt() and 0xFF
                            val prefix = getUriPrefix(prefixCode)
                            Log.d(TAG, "URI prefix code: $prefixCode, prefix: $prefix")

                            if (payload.size > 1) {
                                val uriBytes = payload.sliceArray(1 until payload.size)
                                val uriText = String(uriBytes, Charsets.UTF_8)
                                val uri = prefix + uriText
                                Log.d(TAG, "Parsed URI from NDEF: $uri")
                                return uri
                            }
                        }
                    } else if (typeStr == "Sp") { // Smart Poster
                        Log.d(TAG, "Smart Poster record detected, attempting to extract content")
                        // Try to parse the nested NDEF message in the Smart Poster payload
                        val nestedContent = parseNdefMessage(payload)
                        if (nestedContent != null) {
                            return "Smart Poster: $nestedContent"
                        }
                    }
                } else if (typeNameFormat == 2) { // MIME media type
                    Log.d(TAG, "MIME type: $typeStr")

                    // Try to interpret as text if it's a text-related MIME type
                    if (typeStr.startsWith("text/")) {
                        val text = String(payload, Charsets.UTF_8)
                        Log.d(TAG, "Parsed MIME content as text: $text")
                        return text
                    } else {
                        // For other MIME types, return a descriptive string
                        return "MIME content: $typeStr (${payload.size} bytes)"
                    }
                } else if (typeNameFormat == 3) { // Absolute URI
                    val uri = String(type, Charsets.UTF_8)
                    val content = String(payload, Charsets.UTF_8)
                    Log.d(TAG, "Absolute URI: $uri, content: $content")
                    return "$uri: $content"
                } else if (typeNameFormat == 4) { // External type
                    Log.d(TAG, "External type: $typeStr")
                    if (payload.isNotEmpty()) {
                        // Try to interpret as text
                        try {
                            val text = String(payload, Charsets.UTF_8)
                            if (text.all { it.code in 32..126 || it.code == 10 || it.code == 13 }) {
                                // If it looks like printable text
                                Log.d(TAG, "Parsed external type content as text: $text")
                                return "External [$typeStr]: $text"
                            } else {
                                return "External type: $typeStr (binary data)"
                            }
                        } catch (e: Exception) {
                            return "External type: $typeStr (binary data)"
                        }
                    }
                }
            }

            // If we couldn't parse in a structured way, try a simple text extraction
            if (ndefMessage.size > 3) {
                // Try to extract text from the payload assuming common formats
                for (i in 3 until ndefMessage.size) {
                    if (ndefMessage[i] == 'T'.code.toByte() && i + 2 < ndefMessage.size) {
                        // Look for text record pattern: status byte + language code + text
                        val possibleText = extractTextAfter(ndefMessage, i + 1)
                        if (possibleText != null && possibleText.isNotBlank()) {
                            Log.d(TAG, "Extracted possible text: $possibleText")
                            return possibleText
                        }
                    }
                }
                
                // As a last resort, try to interpret the whole message as UTF-8 text
                try {
                    val text = String(ndefMessage, Charsets.UTF_8)
                    // Check if the string contains mostly printable characters
                    if (text.count { it.code in 32..126 || it.code == 10 || it.code == 13 } > text.length * 0.7) {
                        Log.d(TAG, "Interpreted raw NDEF message as text: $text")
                        return "Raw text: $text"
                    }
                } catch (e: Exception) {
                    // Not valid UTF-8
                }
            }

            return "Unrecognized NDEF format (${ndefMessage.size} bytes)"
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing NDEF message: ${e.message}")
            e.printStackTrace()
            return "Error parsing NDEF: ${e.message}"
        }
    }

    /**
     * Helper method to extract text after a specific position in the NDEF message
     */
    private fun extractTextAfter(data: ByteArray, startPos: Int): String? {
        try {
            if (startPos >= data.size) return null

            // Check if this looks like a status byte for a text record
            val statusByte = data[startPos].toInt() and 0xFF
            val languageCodeLength = statusByte and 0x3F
            val isUTF16 = (statusByte and 0x80) != 0

            if (languageCodeLength > 10) return null // Sanity check for language code length

            val textStart = startPos + 1 + languageCodeLength
            if (textStart < data.size) {
                val textBytes = data.sliceArray(textStart until data.size)
                return if (isUTF16) {
                    String(textBytes, Charsets.UTF_16)
                } else {
                    String(textBytes, Charsets.UTF_8)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error extracting text: ${e.message}")
        }

        return null
    }

    /**
     * Get the URI prefix for the given code according to the NFC Forum URI Record Type Definition
     */
    private fun getUriPrefix(code: Int): String {
        return when (code) {
            0x00 -> ""
            0x01 -> "http://www."
            0x02 -> "https://www."
            0x03 -> "http://"
            0x04 -> "https://"
            0x05 -> "tel:"
            0x06 -> "mailto:"
            0x07 -> "ftp://anonymous:anonymous@"
            0x08 -> "ftp://ftp."
            0x09 -> "ftps://"
            0x0A -> "sftp://"
            0x0B -> "smb://"
            0x0C -> "nfs://"
            0x0D -> "ftp://"
            0x0E -> "dav://"
            0x0F -> "news:"
            0x10 -> "telnet://"
            0x11 -> "imap:"
            0x12 -> "rtsp://"
            0x13 -> "urn:"
            0x14 -> "pop:"
            0x15 -> "sip:"
            0x16 -> "sips:"
            0x17 -> "tftp:"
            0x18 -> "btspp://"
            0x19 -> "btl2cap://"
            0x1A -> "btgoep://"
            0x1B -> "tcpobex://"
            0x1C -> "irdaobex://"
            0x1D -> "file://"
            0x1E -> "urn:epc:id:"
            0x1F -> "urn:epc:tag:"
            0x20 -> "urn:epc:pat:"
            0x21 -> "urn:epc:raw:"
            0x22 -> "urn:epc:"
            0x23 -> "urn:nfc:"
            else -> ""
        }
    }

    /**
     * Convert a byte array to a hex string
     */
    private fun byteArrayToHex(bytes: ByteArray): String {
        return NfcProtocol.byteArrayToHex(bytes)
    }
}