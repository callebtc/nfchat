package com.example.nfcdemo.nfc

import android.util.Log
import java.util.Arrays
import kotlin.byteArrayOf

/**
 * Handles NDEF (NFC Data Exchange Format) message processing.
 * Responsible for responding to NDEF-related APDU commands.
 */
class NdefProcessor {
    companion object {
        private const val TAG = "NdefProcessor"

        // Command Headers
        private val NDEF_SELECT_FILE_HEADER = byteArrayOf(0x00.toByte(), 0xA4.toByte(), 0x00.toByte(), 0x0C.toByte())

        // Step 1: Select AID (Application Identifier)
        private val NDEF_SELECT_AID = byteArrayOf(
            0x00.toByte(), // CLA (Class)
            0xA4.toByte(), // INS (Instruction)
            0x04.toByte(), // P1 (Parameter 1)
            0x00.toByte(), // P2 (Parameter 2)
            0x07.toByte(), // Lc (Length of data)
            0xD2.toByte(), 0x76.toByte(), 0x00.toByte(), 0x00.toByte(), 0x85.toByte(),
            0x01.toByte(), 0x01.toByte(), // AID (Application Identifier)
            0x00.toByte()  // Le (Length of expected response)
        )

        // Step 2: Select CC File (Capability Container): 00 A4 00 0C 02 E1 03
        private val CC_FILE_ID = byteArrayOf(0xE1.toByte(), 0x03.toByte())
        // Minimal CC File (15 bytes)
        private val CC_FILE = byteArrayOf(
            0x00, 0x0F,      // CCLEN = 15
            0x20,            // Mapping version (2.0)
            0x00, 0x3B,      // MLe (max read)
            0x00, 0x34,      // MLc (max write)
            0x04,            // T (NDEF File Control TLV)
            0x06,            // L
            0xE1.toByte(), 0x04.toByte(), // File ID
            0x70.toByte(), 0xFF.toByte(),  // Size: 0x70FF (28,671) bytes
            0x00,            // Read access (unrestricted)
            0x00             // Write access (unrestricted)
        )

        // Step 3: Select NDEF File: 00 A4 00 0C 02 E1 04
        private val NDEF_FILE_ID = byteArrayOf(0xE1.toByte(), 0x04.toByte())

        // example NDEF message: "hello"
        private val NDEF_FILE = byteArrayOf(
            // 0x000B = total NDEF length (2 bytes)
            // D1 = NDEF record header (MB=1, ME=1, TNF=0x01)
            // 01 = type length (T)
            // 09 = payload length
            // 54 = 'T' (type for Text)
            // 02 65 6E = language code "en"
            // 68 65 6C 6C 6F = "hello"
            0x00, 0x0D, // NDEF length
            0xD1.toByte(), // NDEF record header
            0x01, // type length
            0x09, // payload length
            0x54, // 'T'
            0x02, 0x65, 0x6E, // "en"
            0x02, 0x68, 0x65, 0x6C, 0x6C, 0x6F // "hello"
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

    // set to NDEF_FILE
    private var ndefData = ByteArray(NDEF_MAX_MESSAGE_SIZE)
    private var selectedFile: ByteArray? = null

    private fun createNdefMessage(message: String): ByteArray {
        val payload = message.toByteArray()
        val language = byteArrayOf(0x02) + "en".toByteArray()
        val payloadLength = payload.size + language.size
        val type = "T".toByteArray()
        val typeLength = type.size
        val ndefLength = 3 + typeLength + payloadLength
        val ndefData = byteArrayOf(
            0x00, ndefLength.toByte(), // NDEF length
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

    /**
     * Handles SELECT FILE commands
     */
    private fun handleSelectFile(apdu: ByteArray): ByteArray {
        val fileId = apdu.sliceArray(5 until 7)
        return when {
            Arrays.equals(fileId, CC_FILE_ID) -> {
                selectedFile = CC_FILE
                Log.d(TAG, "NdefProcessor: CC File selected")
                NDEF_RESPONSE_OK
            }
            Arrays.equals(fileId, NDEF_FILE_ID) -> {
                // selectedFile = NDEF_FILE
                selectedFile = createNdefMessage("hello")
                Log.d(TAG, "NdefProcessor: NDEF File selected")
                NDEF_RESPONSE_OK
            }
            else -> {
                Log.e(TAG, "NdefProcessor: Unknown file selected")
                NDEF_RESPONSE_ERROR
            }
        }
    }

    /**
     * Handles READ BINARY commands (00 B0 offset_MSB offset_LSB length)
     */
    private fun handleReadBinary(apdu: ByteArray): ByteArray {
        if (selectedFile == null || apdu.size < 5) return NDEF_RESPONSE_ERROR
        
        // Always return “empty” data and success
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
    

    /**
     * Handles UPDATE BINARY commands (00 D6 offset_MSB offset_LSB length data...)
     */
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
        Log.d(TAG, "NdefProcessor: UPDATE BINARY success, updated ${dataLength} bytes at offset ${offset}")
        // print string
        Log.d(TAG, "NdefProcessor: UPDATE BINARY data: ${String(data)}")

        return NDEF_RESPONSE_OK
    }

    /**
     * Processes an APDU command and returns the appropriate response
     */
    fun processCommandApdu(commandApdu: ByteArray): ByteArray {
        // Check if NDEF AID is selected
        if (Arrays.equals(commandApdu, NDEF_SELECT_AID)) {
            Log.d(TAG, "NdefProcessor: NDEF AID selected")
            return NDEF_RESPONSE_OK
        }

        // Handle File Selection
        if (commandApdu.size >= 7 && Arrays.equals(commandApdu.sliceArray(0 until 4), NDEF_SELECT_FILE_HEADER)) {
            Log.d(TAG, "NdefProcessor: SELECT FILE command received")
            return handleSelectFile(commandApdu)
        }

        // Handle Read Binary
        if (commandApdu.size >= 2 && Arrays.equals(commandApdu.sliceArray(0 until 2), NDEF_READ_BINARY_HEADER)) {
            Log.d(TAG, "NdefProcessor: READ BINARY command received")
            return handleReadBinary(commandApdu)
        }

        // Handle Update Binary
        if (commandApdu.size >= 2 && Arrays.equals(commandApdu.sliceArray(0 until 2), NDEF_UPDATE_BINARY_HEADER)) {
            Log.d(TAG, "NdefProcessor: UPDATE BINARY command received")
            return handleUpdateBinary(commandApdu)
        }

        Log.d(TAG, "NdefProcessor: Invalid APDU received: ${byteArrayToHex(commandApdu)}")
        return NDEF_RESPONSE_ERROR
    }

    /**
     * Convert a byte array to a hex string
     */
    private fun byteArrayToHex(bytes: ByteArray): String {
        return bytes.joinToString("") { "%02X".format(it) }
    }

}