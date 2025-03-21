package com.example.nfcdemo

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.os.Build
import android.os.Handler
import android.util.Log
import android.widget.EditText
import android.widget.Toast
import com.example.nfcdemo.nfc.TransferManager
import android.nfc.NdefMessage
import com.example.nfcdemo.nfc.NdefProcessor
import java.nio.charset.Charset
import com.example.nfcdemo.ui.VibrationUtils

/**
 * Manager class for handling various intents in the application
 */
class IntentManager(
    private val context: Activity,
    private val transferManager: TransferManager,
    private val mainHandler: Handler,
    private val dbHelper: com.example.nfcdemo.data.MessageDbHelper
) {
    private val TAG = "IntentManager"
    
    // Callback interfaces
    interface MessageSaveCallback {
        fun saveAndAddMessage(messageText: String, isSent: Boolean): Int
    }
    
    // Callbacks
    var messageSaveCallback: MessageSaveCallback? = null
    var onOpenedViaShareIntentChanged: ((Boolean) -> Unit)? = null
    
    // State
    private var openedViaShareIntent = false
    private var backgroundNfcEnabled = true
    
    // NdefProcessor for handling NDEF operations
    private val ndefProcessor = NdefProcessor()
    
    /**
     * Set the background NFC enabled state
     */
    fun setBackgroundNfcEnabled(enabled: Boolean) {
        backgroundNfcEnabled = enabled
        Log.d(TAG, "Background NFC setting set: enabled=$backgroundNfcEnabled")
    }
    
    /**
     * Get the opened via share intent state
     */
    fun isOpenedViaShareIntent(): Boolean {
        return openedViaShareIntent
    }
    
    /**
     * Set the opened via share intent state
     */
    fun setOpenedViaShareIntent(opened: Boolean) {
        openedViaShareIntent = opened
        onOpenedViaShareIntentChanged?.invoke(opened)
    }
    
    /**
     * Set up callbacks for the NdefProcessor
     */
    fun setupNdefProcessorCallbacks() {
        // Set the callback for when text is received from an NDEF message
        ndefProcessor.onNdefTextReceived = { textData ->
            messageSaveCallback?.saveAndAddMessage(textData, false)
        }
    }
    
    /**
     * Handle a new intent received by the activity
     */
    fun handleNewIntent(intent: Intent, appState: AppState, etMessage: EditText) {
        // check it intent is null
        if (intent.action == null) {
            Log.d(TAG, "Intent is null")
            return
        }
        Log.d(TAG, "New intent received: ${intent.action}")
        
        // Handle share intents
        if (intent.action == Intent.ACTION_SEND && intent.type?.startsWith("text/") == true) {
            Log.d(TAG, "Share intent received while app is running")
            
            // Cancel any pending receive mode initialization
            mainHandler.removeCallbacksAndMessages(null)
            
            // If we're in send mode, finish the current operation first
            if (appState == AppState.SENDING) {
                // If we're already in send mode, we need to wait until the current operation is complete
                Toast.makeText(context, context.getString(R.string.wait_for_current_operation), Toast.LENGTH_SHORT).show()
                return
            }
            
            // Handle the share intent
            handleIncomingShareIntent(intent, appState, etMessage)
            return
        }
        
        // Handle being brought to the foreground from a background message receive
        if (intent.getBooleanExtra("from_background_receive", false)) {
            Log.d(TAG, "from_background_receive: App brought to foreground from background message receive")
            // Make sure we're in receive mode
            if (appState != AppState.RECEIVING) {
                transferManager.switchToReceiveMode()
            }
            return
        }
        
        // Handle the NFC intent
        if (intent.action == NfcAdapter.ACTION_TECH_DISCOVERED ||
            intent.action == NfcAdapter.ACTION_TAG_DISCOVERED ||
            intent.action == NfcAdapter.ACTION_NDEF_DISCOVERED) {
            
            handleNfcIntent(intent, appState)
            
            // Use the NdefProcessor for NDEF messages
            if (intent.action == NfcAdapter.ACTION_NDEF_DISCOVERED) {
                ndefProcessor.handleNfcNdefCardIntent(context, intent)
            }
        }
    }
    
    /**
     * Handle NFC intents, whether from foreground dispatch or from app launch
     */
    fun handleNfcIntent(intent: Intent, appState: AppState) {
        Log.d(TAG, "IntentManager: Handling NFC intent: ${intent.action}")
        // Check if the app was launched from background and if background NFC is disabled
        if (!isAppInForeground(intent) && !backgroundNfcEnabled) {
            Log.d(TAG, "Ignoring NFC intent because background NFC is disabled")
            return
        }
        
        Log.d(TAG, "Handling NFC intent: ${intent.action}")
        
        // Use the new API for getting parcelable extras if available
        val tag = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(NfcAdapter.EXTRA_TAG, Tag::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(NfcAdapter.EXTRA_TAG)
        }
        
        tag?.let {
            // If we're in send mode, we'll process the tag in onTagDiscovered
            // If we're not in send mode, process the tag directly
            if (appState != AppState.SENDING) {
                // Make sure we're in receive mode
                if (appState != AppState.RECEIVING) {
                    transferManager.switchToReceiveMode()
                }
                
                // Process the tag
                transferManager.handleTagDiscovered(it)
                
                // Vibrate to indicate NFC detection
                VibrationUtils.vibrate(context, 100)
                
                // Show a toast to indicate the app was launched by NFC
                // if (intent.action != NfcAdapter.ACTION_TECH_DISCOVERED) {
                //     Toast.makeText(context, context.getString(R.string.app_launched_by_nfc), Toast.LENGTH_SHORT).show()
                // }
            }
        } ?: run {
            Log.e(TAG, "No tag found in intent")
        }
    }
    
    /**
     * Handle incoming share intents
     */
    fun handleIncomingShareIntent(intent: Intent?, appState: AppState, etMessage: EditText) {
        // Check if this activity was started from a share intent
        if (intent?.action == Intent.ACTION_SEND && intent.type?.startsWith("text/") == true) {
            Log.d(TAG, "Handling share intent: ${intent.action}, type: ${intent.type}")
            
            // Mark that we were opened via share intent
            setOpenedViaShareIntent(true)
            
            // If we're in receive mode, stop it first
            if (appState == AppState.RECEIVING) {
                // Stop the CardEmulationService
                val serviceIntent = Intent(context, CardEmulationService::class.java)
                Log.d(TAG, "handleIncomingShareIntent: Stopping CardEmulationService")
                context.stopService(serviceIntent)
            }
            
            // Extract the shared text
            val sharedText = intent.getStringExtra(Intent.EXTRA_TEXT)
            if (!sharedText.isNullOrEmpty()) {
                Log.d(TAG, "Shared text received: ${sharedText.take(50)}${if (sharedText.length > 50) "..." else ""}")
                
                // Set the shared text in the message field
                etMessage.setText(sharedText)
                
                // Check if auto-send is enabled
                val autoSendShared = dbHelper.getBooleanSetting(
                    com.example.nfcdemo.data.SettingsContract.SettingsEntry.KEY_AUTO_SEND_SHARED, 
                    true
                )
                
                if (autoSendShared) {
                    // Show a toast to inform the user
                    Toast.makeText(context, context.getString(R.string.text_received_auto_send), Toast.LENGTH_LONG).show()
                    
                    // Automatically prepare to send the shared text
                    mainHandler.postDelayed({
                        // Only proceed if the text is still there (user hasn't cleared it)
                        if (!etMessage.text.isNullOrEmpty()) {
                            Log.d(TAG, "Auto-sending shared text")
                            
                            // Store the message to send
                            val message = etMessage.text.toString()
                            transferManager.setLastSentMessage(message)
                            
                            // Add the message to the chat as a sent message and save to database
                            messageSaveCallback?.saveAndAddMessage(message, true)
                            
                            // Clear the input field after sending
                            etMessage.text.clear()
                            
                            // Switch to send mode
                            transferManager.switchToSendMode()
                        } else {
                            Log.d(TAG, "Text field is empty, not auto-sending")
                        }
                    }, 500) // Short delay to ensure UI is ready
                } else {
                    // Just show a toast that the text is ready to send
                    Toast.makeText(context, context.getString(R.string.text_received_manual_send), Toast.LENGTH_LONG).show()
                    
                    // We don't save the message yet - it will be saved when the user presses send
                    // This avoids duplicate messages in the database
                    // The message is already in the input field, so the user can edit it before sending
                }
            } else {
                Log.d(TAG, "Shared text is null or empty")
            }
        } else {
            Log.d(TAG, "Not a share intent or wrong type: ${intent?.action}, type: ${intent?.type}")
        }
    }
    
    /**
     * Handle post-send actions (e.g., closing the app if it was opened via a share intent)
     */
    fun handlePostSendActions(): Boolean {
        // If we were opened via a share intent, and the setting is enabled, finish the activity
        val finishAfterSend = dbHelper.getBooleanSetting(
            com.example.nfcdemo.data.SettingsContract.SettingsEntry.KEY_CLOSE_AFTER_SHARED_SEND,
            com.example.nfcdemo.data.AppConstants.DefaultSettings.CLOSE_AFTER_SHARED_SEND
        )
        
        if (openedViaShareIntent && finishAfterSend) {
            Log.d(TAG, "Finishing activity after sending shared content")
            mainHandler.postDelayed({
                context.finish()
            }, 1000)
            return true
        }
        
        return false
    }
    
    /**
     * Check if the app is in the foreground
     */
    private fun isAppInForeground(intent: Intent): Boolean {
        // If this is a new intent to an existing activity, we're in the foreground
        return intent.flags and Intent.FLAG_ACTIVITY_BROUGHT_TO_FRONT != 0 ||
                intent.flags and Intent.FLAG_ACTIVITY_NEW_TASK == 0
    }
} 