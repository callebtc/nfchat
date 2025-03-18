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
            0x00.toByte(), 0x18.toByte(), // File Control TLV (24 bytes max size for now)
            0x00.toByte(), 0x00.toByte(), // NDEF File Length (initially 0)
            0x00.toByte(), 0x00.toByte()  // NDEF Message Length (initially 0)
        )

        // NDEF Length byte positions in the data
        private const val NDEF_FILE_LENGTH_POSITION = 4
        private const val NDEF_MESSAGE_LENGTH_POSITION = 6

        // Maximum supported NDEF message size
        private const val NDEF_MAX_MESSAGE_SIZE = 254
    }

    private var ndefData = NDEF_INITIAL_HEADER.copyOf()
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
        return receivedNdefMessage?.let { parseNdefMessage(it) }
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
        
        // Check if NDEF File is selected (00A4000C02E103)
        if (commandApdu.size >= (NDEF_SELECT_FILE_HEADER.size + NDEF_FILE_ID_LENGTH.size + NDEF_FILE_ID.size) &&
            Arrays.equals(commandApdu.sliceArray(0 until NDEF_SELECT_FILE_HEADER.size), NDEF_SELECT_FILE_HEADER) &&
            Arrays.equals(commandApdu.sliceArray(NDEF_SELECT_FILE_HEADER.size until NDEF_SELECT_FILE_HEADER.size + NDEF_FILE_ID_LENGTH.size), NDEF_FILE_ID_LENGTH) &&
            Arrays.equals(commandApdu.sliceArray(NDEF_SELECT_FILE_HEADER.size + NDEF_FILE_ID_LENGTH.size until NDEF_SELECT_FILE_HEADER.size + NDEF_FILE_ID_LENGTH.size + NDEF_FILE_ID.size), NDEF_FILE_ID)) {
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
    private fun handleReadBinary(commandApdu: ByteArray): ByteArray {
        // For the READ BINARY command, we need at least 5 bytes
        if (commandApdu.size < 5) {
            Log.e(TAG, "Invalid READ BINARY command: not enough bytes (${commandApdu.size})")
            return NDEF_RESPONSE_ERROR
        }

        val p1 = commandApdu[2].toInt() and 0xFF // MSB of offset
        val p2 = commandApdu[3].toInt() and 0xFF // LSB of offset
        val offset = (p1 shl 8) or p2
        
        // Length is either last byte (Le) or default to remaining NDEF data
        val requestedLength = if (commandApdu.size > 4) {
            commandApdu[4].toInt() and 0xFF 
        } else {
            ndefData.size - offset
        }
        
        Log.d(TAG, "READ BINARY with offset: $offset, length: $requestedLength")

        // Make sure we don't read past the end of our data
        if (offset >= ndefData.size) {
            Log.e(TAG, "Invalid offset: $offset (exceeds data size ${ndefData.size})")
            return NDEF_RESPONSE_ERROR
        }
        
        val actualLength = minOf(requestedLength, ndefData.size - offset)
        
        // Return the requested part of the NDEF data
        val dataToReturn = ndefData.sliceArray(offset until (offset + actualLength))
        val response = dataToReturn + NDEF_RESPONSE_OK
        Log.d(TAG, "READ BINARY response length: ${dataToReturn.size} bytes")
        Log.d(TAG, "READ BINARY response: ${byteArrayToHex(response)}")
        return response
    }

    /**
     * Handles UPDATE BINARY commands (C-APDU: 00 D6 offset_MSB offset_LSB length data...)
     */
    private fun handleUpdateBinary(commandApdu: ByteArray): ByteArray {
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
        
        // If we're writing to the NDEF message length bytes, we may have a complete message
        if (offset <= NDEF_MESSAGE_LENGTH_POSITION && offset + dataLength > NDEF_MESSAGE_LENGTH_POSITION) {
            checkForCompleteNdefMessage()
        }
        
        Log.d(TAG, "UPDATE BINARY success, updated ${dataLength} bytes at offset ${offset}")
        return NDEF_RESPONSE_OK
    }

    /**
     * Checks if we have a complete NDEF message and processes it if so
     */
    private fun checkForCompleteNdefMessage() {
        // NDEF message length is at bytes 6-7 (big-endian)
        if (ndefData.size >= 8) {
            val messageLength = ((ndefData[NDEF_MESSAGE_LENGTH_POSITION].toInt() and 0xFF) shl 8) or 
                                 (ndefData[NDEF_MESSAGE_LENGTH_POSITION + 1].toInt() and 0xFF)
            
            if (messageLength > 0 && ndefData.size >= 8 + messageLength) {
                Log.d(TAG, "Detected complete NDEF message of length $messageLength")
                
                // Extract the NDEF message
                receivedNdefMessage = ndefData.sliceArray(8 until 8 + messageLength)
                
                // Log the content
                val messageContent = parseNdefMessage(receivedNdefMessage!!)
                if (messageContent != null) {
                    Log.d(TAG, "Received NDEF message content: $messageContent")
                } else {
                    Log.d(TAG, "Received NDEF message with raw data: ${byteArrayToHex(receivedNdefMessage!!)}")
                }
            }
        }
    }
    
    /**
     * Parses an NDEF message to extract text content
     */
    private fun parseNdefMessage(ndefMessage: ByteArray): String? {
        try {
            // Simple parsing of Text record (type "T")
            if (ndefMessage.size < 3) return null
            
            // Check record header
            val firstByte = ndefMessage[0].toInt() and 0xFF
            val isShortRecord = (firstByte and 0x10) != 0
            val hasIdLength = (firstByte and 0x08) != 0
            val typeLength = ndefMessage[1].toInt() and 0xFF
            
            // Get payload length and offset
            var offset = 2
            val payloadLength = if (isShortRecord) {
                ndefMessage[offset++].toInt() and 0xFF
            } else {
                if (ndefMessage.size < 6) return null
                ((ndefMessage[offset++].toInt() and 0xFF) shl 24) or
                ((ndefMessage[offset++].toInt() and 0xFF) shl 16) or
                ((ndefMessage[offset++].toInt() and 0xFF) shl 8) or
                (ndefMessage[offset++].toInt() and 0xFF)
            }
            
            // Skip ID length if present
            if (hasIdLength) {
                val idLength = ndefMessage[offset++].toInt() and 0xFF
                offset += idLength
            }
            
            // Get record type
            val type = ndefMessage.sliceArray(offset until offset + typeLength)
            offset += typeLength
            
            // For Text record (type "T"), process the payload
            if (type.size == 1 && type[0] == 'T'.code.toByte()) {
                if (offset + payloadLength <= ndefMessage.size) {
                    val payload = ndefMessage.sliceArray(offset until offset + payloadLength)
                    
                    // Text record format: status byte + language code + text
                    if (payload.isNotEmpty()) {
                        val statusByte = payload[0].toInt() and 0xFF
                        val languageCodeLength = statusByte and 0x3F
                        val isUTF16 = (statusByte and 0x80) != 0
                        
                        val textStart = 1 + languageCodeLength
                        if (textStart < payload.size) {
                            val textBytes = payload.sliceArray(textStart until payload.size)
                            return if (isUTF16) {
                                String(textBytes, Charsets.UTF_16)
                            } else {
                                String(textBytes, Charsets.UTF_8)
                            }
                        }
                    }
                }
            } else {
                // For other record types, try to interpret as UTF-8 text
                if (offset + payloadLength <= ndefMessage.size) {
                    val payload = ndefMessage.sliceArray(offset until offset + payloadLength)
                    return String(payload, Charsets.UTF_8)
                }
            }
            
            return null
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing NDEF message: ${e.message}")
            return null
        }
    }

    /**
     * Convert a byte array to a hex string
     */
    private fun byteArrayToHex(bytes: ByteArray): String {
        return NfcProtocol.byteArrayToHex(bytes)
    }
} 