package com.example.nfcdemo.ui

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log

/**
 * Utility class for handling device vibration
 */
object VibrationUtils {
    private const val TAG = "VibrationUtils"

    /**
     * Vibrate the device for the specified duration
     * 
     * @param context The application context
     * @param duration Duration of vibration in milliseconds
     */
    fun vibrate(context: Context, duration: Long) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val vibratorManager =
                        context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
                val vibrator = vibratorManager.defaultVibrator
                vibrator.vibrate(
                        VibrationEffect.createOneShot(duration, VibrationEffect.DEFAULT_AMPLITUDE)
                )
            } else {
                @Suppress("DEPRECATION")
                val vibrator = context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    vibrator.vibrate(
                            VibrationEffect.createOneShot(
                                    duration,
                                    VibrationEffect.DEFAULT_AMPLITUDE
                            )
                    )
                } else {
                    @Suppress("DEPRECATION") vibrator.vibrate(duration)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error vibrating: ${e.message}")
        }
    }
} 