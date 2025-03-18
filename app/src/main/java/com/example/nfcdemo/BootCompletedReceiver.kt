package com.example.nfcdemo

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log

/**
 * Receiver that starts the CardEmulationService when the device boots.
 * This ensures our NFC service is running even if the app hasn't been manually launched.
 */
class BootCompletedReceiver : BroadcastReceiver() {
    private val TAG = "BootCompletedReceiver"
    
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            Log.d(TAG, "Boot completed, starting CardEmulationService")
            
            // Start the CardEmulationService
            val serviceIntent = Intent(context, CardEmulationService::class.java)
            serviceIntent.action = Intent.ACTION_MAIN
            
            // On Android 8.0+ we need to use startForegroundService
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                try {
                    context.startForegroundService(serviceIntent)
                    Log.d(TAG, "Service started as foreground service")
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to start foreground service: ${e.message}")
                    // Fall back to regular service start
                    context.startService(serviceIntent)
                    Log.d(TAG, "Service started as regular service")
                }
            } else {
                context.startService(serviceIntent)
                Log.d(TAG, "Service started")
            }
        }
    }
} 