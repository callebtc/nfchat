package com.example.nfcdemo

import android.app.Activity
import android.content.ComponentName
import android.content.Intent
import android.content.ServiceConnection
import android.nfc.NfcAdapter
import android.nfc.NfcAdapter.ReaderCallback
import android.nfc.Tag
import android.nfc.TagLostException
import android.nfc.tech.IsoDep
import android.os.Bundle
import android.os.IBinder
import android.util.Log
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import java.io.IOException
import java.nio.charset.Charset

class MainActivity : Activity(), ReaderCallback {

    private val TAG = "MainActivity"
    
    private var nfcAdapter: NfcAdapter? = null
    private lateinit var etMessage: EditText
    private lateinit var tvReceived: TextView
    private lateinit var tvStatus: TextView
    private lateinit var btnSendMode: Button
    private lateinit var btnReceiveMode: Button
    
    private var isInSendMode = false
    private var isInReceiveMode = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        etMessage = findViewById(R.id.etMessage)
        tvReceived = findViewById(R.id.tvReceived)
        tvStatus = findViewById(R.id.tvStatus)
        btnSendMode = findViewById(R.id.btnSendMode)
        btnReceiveMode = findViewById(R.id.btnReceiveMode)

        // Initialize NFC
        nfcAdapter = NfcAdapter.getDefaultAdapter(this)
        if (nfcAdapter == null) {
            tvStatus.text = getString(R.string.nfc_not_available)
            Toast.makeText(this, getString(R.string.nfc_not_available), Toast.LENGTH_LONG).show()
            btnSendMode.isEnabled = false
            btnReceiveMode.isEnabled = false
            return
        }

        // Set up button click listeners
        btnSendMode.setOnClickListener {
            if (etMessage.text.toString().isEmpty()) {
                Toast.makeText(this, getString(R.string.enter_message_prompt), Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            
            isInSendMode = true
            isInReceiveMode = false
            tvStatus.text = getString(R.string.status_send_mode)
            Toast.makeText(this, "Send mode activated. Tap your phone to another NFC device.", Toast.LENGTH_SHORT).show()
            
            // Enable reader mode for sending data
            enableReaderMode()
        }

        btnReceiveMode.setOnClickListener {
            isInReceiveMode = true
            isInSendMode = false
            tvStatus.text = getString(R.string.status_receive_mode)
            Toast.makeText(this, "Receive mode activated. Waiting for NFC data.", Toast.LENGTH_SHORT).show()
            
            // Disable reader mode and prepare to receive data via HCE
            disableReaderMode()
            
            // Set the message to be shared in the HCE service
            val messageToShare = etMessage.text.toString()
            CardEmulationService().messageToShare = messageToShare
            
            // Set up listener for received data
            CardEmulationService().onDataReceivedListener = { receivedData ->
                runOnUiThread {
                    tvReceived.text = receivedData
                    tvStatus.text = getString(R.string.message_received)
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        if (isInSendMode) {
            enableReaderMode()
        }
    }

    override fun onPause() {
        super.onPause()
        disableReaderMode()
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
                val message = etMessage.text.toString()
                val sendCommand = "SEND_DATA:$message".toByteArray(Charset.forName("UTF-8"))
                val sendResult = isoDep.transceive(sendCommand)
                
                if (isSuccess(sendResult)) {
                    runOnUiThread {
                        tvStatus.text = getString(R.string.message_sent)
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
                    
                    runOnUiThread {
                        tvReceived.text = receivedMessage
                        tvStatus.text = getString(R.string.message_received)
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
