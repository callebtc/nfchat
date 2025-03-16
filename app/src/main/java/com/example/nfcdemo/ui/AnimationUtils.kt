package com.example.nfcdemo.ui

import android.animation.ArgbEvaluator
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.View
import android.view.animation.AccelerateDecelerateInterpolator
import android.widget.ProgressBar
import androidx.core.content.ContextCompat
import com.example.nfcdemo.R

/**
 * Utility class for handling animations in the app
 */
object AnimationUtils {
    
    // Store active animations to be able to cancel them
    private var activeGlowAnimator: ValueAnimator? = null
    private var activeProgressAnimator: ValueAnimator? = null
    
    /**
     * Start a glowing animation on a view
     * @param view The view to animate
     * @param context The context
     * @param fromColor The starting color (default is the send_button_enabled color)
     * @param toColor The color to glow to (default is a slightly brighter version of fromColor)
     * @param duration The duration of one cycle in milliseconds
     * @return The created animator
     */
    fun startGlowAnimation(
        view: View, 
        context: Context,
        fromColor: Int = ContextCompat.getColor(context, R.color.send_button_enabled),
        toColor: Int = lightenColor(fromColor, 0.2f),
        duration: Long = 1500
    ): ValueAnimator {
        // Cancel any existing animation
        stopGlowAnimation()
        
        // Create a color animation
        val colorAnimator = ValueAnimator.ofObject(ArgbEvaluator(), fromColor, toColor)
        colorAnimator.duration = duration
        colorAnimator.repeatCount = ValueAnimator.INFINITE
        colorAnimator.repeatMode = ValueAnimator.REVERSE
        colorAnimator.interpolator = AccelerateDecelerateInterpolator()
        
        // Get the current background drawable
        val originalDrawable = context.getDrawable(R.drawable.message_sent_background)?.constantState?.newDrawable()?.mutate()
        
        // Update the background color of the view while preserving the shape
        colorAnimator.addUpdateListener { animator ->
            val color = animator.animatedValue as Int
            
            // Create a new drawable with the updated color but same shape
            val newDrawable = context.getDrawable(R.drawable.message_sent_background)?.constantState?.newDrawable()?.mutate()
            
            // Update the color of the drawable
            if (newDrawable is GradientDrawable) {
                newDrawable.setColor(color)
            }
            
            // Set the new drawable as the background
            view.background = newDrawable
        }
        
        colorAnimator.start()
        activeGlowAnimator = colorAnimator
        
        return colorAnimator
    }
    
    /**
     * Stop the active glow animation
     */
    fun stopGlowAnimation() {
        activeGlowAnimator?.cancel()
        activeGlowAnimator = null
    }
    
    /**
     * Update the progress bar with the current progress
     * @param progressBar The progress bar to update
     * @param currentProgress The current progress value (0-100)
     * @param animate Whether to animate the progress change
     */
    fun updateProgressBar(
        progressBar: ProgressBar,
        currentProgress: Int,
        animate: Boolean = true
    ) {
        if (animate) {
            // Cancel any existing animation
            activeProgressAnimator?.cancel()
            
            // Create a progress animation
            val progressAnimator = ObjectAnimator.ofInt(
                progressBar,
                "progress",
                progressBar.progress,
                currentProgress
            )
            progressAnimator.duration = 300
            progressAnimator.interpolator = AccelerateDecelerateInterpolator()
            progressAnimator.start()
            
            activeProgressAnimator = progressAnimator
        } else {
            progressBar.progress = currentProgress
        }
    }
    
    /**
     * Show the progress bar with an optional animation
     * @param progressBar The progress bar to show
     * @param animate Whether to animate the visibility change
     */
    fun showProgressBar(progressBar: ProgressBar, animate: Boolean = true) {
        if (progressBar.visibility == View.VISIBLE) return
        
        if (animate) {
            progressBar.alpha = 0f
            progressBar.visibility = View.VISIBLE
            progressBar.animate()
                .alpha(1f)
                .setDuration(300)
                .start()
        } else {
            progressBar.visibility = View.VISIBLE
        }
    }
    
    /**
     * Hide the progress bar with an optional animation
     * @param progressBar The progress bar to hide
     * @param animate Whether to animate the visibility change
     */
    fun hideProgressBar(progressBar: ProgressBar, animate: Boolean = true) {
        if (progressBar.visibility == View.GONE) return
        
        if (animate) {
            progressBar.animate()
                .alpha(0f)
                .setDuration(300)
                .withEndAction {
                    progressBar.visibility = View.GONE
                }
                .start()
        } else {
            progressBar.visibility = View.GONE
        }
    }
    
    /**
     * Helper method to lighten a color
     * @param color The color to lighten
     * @param factor The factor to lighten by (0.0-1.0)
     * @return The lightened color
     */
    private fun lightenColor(color: Int, factor: Float): Int {
        val a = Color.alpha(color)
        val r = Math.min(Color.red(color) + (255 - Color.red(color)) * factor, 255f).toInt()
        val g = Math.min(Color.green(color) + (255 - Color.green(color)) * factor, 255f).toInt()
        val b = Math.min(Color.blue(color) + (255 - Color.blue(color)) * factor, 255f).toInt()
        return Color.argb(a, r, g, b)
    }
} 