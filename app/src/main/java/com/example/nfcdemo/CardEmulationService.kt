package com.example.nfcdemo

import android.nfc.cardemulation.HostApduService
import android.os.Bundle
import android.util.Log
import java.nio.charset.Charset
import java.util.*

class CardEmulationService : HostApduService() {
    
    companion object {
        private const val TAG = "CardEmulationService"
        
        // ISO-DEP command HEADER for selecting an AID
        private val SELECT_APDU_HEADER = byteArrayOf(
            0x00.toByte(), // CLA (class of instruction)
            0xA4.toByte(), // INS (instruction code)
            0x04.toByte(), // P1  (parameter 1)
            0x00.toByte()  // P2  (parameter 2)
        )
        
        // "OK" status word sent in response to SELECT AID command (0x9000)
        private val SELECT_OK_SW = byteArrayOf(0x90.toByte(), 0x00.toByte())
        
        // "UNKNOWN" status word sent in response to invalid APDU command (0x0000)
        private val UNKNOWN_CMD_SW = byteArrayOf(0x00.toByte(), 0x00.toByte())
        
        // AID for our service
        private val AID = "F0010203040506".hexStringToByteArray()
        
        // Commands
        private const val CMD_GET_DATA = "GET_DATA"
        private const val CMD_SEND_DATA = "SEND_DATA:"
        
        // Convert a hex string to a byte array
        private fun String.hexStringToByteArray(): ByteArray {
            val len = this.length
            val data = ByteArray(len / 2)
            for (i in 0 until len step 2) {
                data[i / 2] = ((Character.digit(this[i], 16) shl 4) + Character.digit(this[i + 1], 16)).toByte()
            }
            return data
        }
    }
    
    // Message to be shared when requested
    var messageToShare: String = ""
    
    // Callback to notify MainActivity when data is received
    var onDataReceivedListener: ((String) -> Unit)? = null
    
    override fun processCommandApdu(commandApdu: ByteArray, extras: Bundle?): ByteArray {
        Log.d(TAG, "Received APDU: ${commandApdu.toHex()}")
        
        // Check if this is a SELECT AID command
        if (isSelectAidCommand(commandApdu)) {
            Log.d(TAG, "SELECT AID command received")
            return SELECT_OK_SW
        }
        
        // Convert command to string
        val commandString = String(commandApdu, Charset.forName("UTF-8"))
        Log.d(TAG, "Command string: $commandString")
        
        // Handle GET_DATA command
        if (commandString == CMD_GET_DATA) {
            Log.d(TAG, "GET_DATA command received, sending: $messageToShare")
            val dataBytes = messageToShare.toByteArray(Charset.forName("UTF-8"))
            return concatArrays(dataBytes, SELECT_OK_SW)
        }
        
        // Handle SEND_DATA command
        if (commandString.startsWith(CMD_SEND_DATA)) {
            val receivedData = commandString.substring(CMD_SEND_DATA.length)
            Log.d(TAG, "SEND_DATA command received with data: $receivedData")
            
            // Notify MainActivity about received data
            onDataReceivedListener?.invoke(receivedData)
            
            return SELECT_OK_SW
        }
        
        // Unknown command
        return UNKNOWN_CMD_SW
    }
    
    override fun onDeactivated(reason: Int) {
        Log.d(TAG, "Deactivated: $reason")
    }
    
    private fun isSelectAidCommand(command: ByteArray): Boolean {
        return command.size >= SELECT_APDU_HEADER.size + 1 + AID.size + 1 && 
               command.sliceArray(0 until SELECT_APDU_HEADER.size).contentEquals(SELECT_APDU_HEADER) &&
               command[SELECT_APDU_HEADER.size].toInt() == AID.size &&
               command.sliceArray(SELECT_APDU_HEADER.size + 1 until SELECT_APDU_HEADER.size + 1 + AID.size).contentEquals(AID)
    }
    
    private fun concatArrays(array1: ByteArray, array2: ByteArray): ByteArray {
        val result = ByteArray(array1.size + array2.size)
        System.arraycopy(array1, 0, result, 0, array1.size)
        System.arraycopy(array2, 0, result, array1.size, array2.size)
        return result
    }
    
    private fun ByteArray.toHex(): String {
        return joinToString("") { "%02X".format(it) }
    }
} 