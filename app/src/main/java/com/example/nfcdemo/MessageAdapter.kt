package com.example.nfcdemo

import android.animation.ValueAnimator
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.text.Layout
import android.text.Selection
import android.text.Spannable
import android.text.SpannableString
import android.text.Spanned
import android.text.TextPaint
import android.text.method.LinkMovementMethod
import android.text.style.ClickableSpan
import android.text.style.URLSpan
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.recyclerview.widget.RecyclerView
import com.example.nfcdemo.data.MessageDbHelper
import com.example.nfcdemo.data.SettingsContract
import com.example.nfcdemo.nfc.MessageProcessor
import com.example.nfcdemo.ui.AnimationUtils
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

class MessageAdapter(private val context: Context) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    companion object {
        // Make these public for testing
        const val VIEW_TYPE_SENT = 1
        const val VIEW_TYPE_RECEIVED = 2
        
        // Configurable message length limit before truncation
        private var MESSAGE_LENGTH_LIMIT = 200
    }

    data class Message(
        val content: String,
        val isSent: Boolean,
        var isDelivered: Boolean = false,
        val timestamp: Date = Date(),
        var id: Long = -1, // Database ID
        var isExpanded: Boolean = false, // Track expanded state
        val messageId: String = UUID.randomUUID().toString(), // Unique message ID for duplicate detection
        var isPending: Boolean = false // Track if message is pending (being sent)
    )

    private val messages = mutableListOf<Message>()
    private val dbHelper = MessageDbHelper(context)
    
    // Track the currently pending message position
    private var pendingMessagePosition = -1
    private var glowAnimator: ValueAnimator? = null
    
    // Track the current app state
    private var appState = AppState.IDLE
    
    // Custom LinkMovementMethod to handle link clicks
    private val customLinkMovementMethod = object : LinkMovementMethod() {
        override fun onTouchEvent(widget: TextView, buffer: android.text.Spannable, event: MotionEvent): Boolean {
            val action = event.action
            
            if (action == MotionEvent.ACTION_UP) {
                var x = event.x.toInt()
                var y = event.y.toInt()
                
                x -= widget.totalPaddingLeft
                y -= widget.totalPaddingTop
                
                x += widget.scrollX
                y += widget.scrollY
                
                val layout = widget.layout
                val line = layout.getLineForVertical(y)
                val off = layout.getOffsetForHorizontal(line, x.toFloat())
                
                val links = buffer.getSpans(off, off, URLSpan::class.java)
                if (links.isNotEmpty()) {
                    val url = links[0].url
                    
                    // Get the position from the tag of the parent view
                    val position = (widget.tag as? Int) ?: -1
                    if (position >= 0 && position < messages.size) {
                        // Use the full message content for link handling
                        return handleLinkClick(url, messages[position].content)
                    }
                    
                    return handleLinkClick(url, null)
                }
            }
            
            return super.onTouchEvent(widget, buffer, event)
        }
        
        private fun handleLinkClick(url: String, fullMessageContent: String?): Boolean {
            // If we have the full message content, try to find the complete URL
            val completeUrl = if (fullMessageContent != null) {
                findCompleteUrl(url, fullMessageContent)
            } else {
                url
            }
            
            // Prepend https:// if the URL doesn't have a scheme
            val fullUrl = if (!completeUrl.startsWith("http://") && !completeUrl.startsWith("https://")) {
                "https://$completeUrl"
            } else {
                completeUrl
            }
            
            // Use the MessageProcessor to open the URL
            MessageProcessor.openUrl(context, fullUrl, dbHelper)
            
            return true
        }
        
        // Helper method to find the complete URL in the full message content
        private fun findCompleteUrl(partialUrl: String, fullContent: String): String {
            // Create a pattern that matches URLs
            val matcher = android.util.Patterns.WEB_URL.matcher(fullContent)
            
            // Find all URLs in the full content
            while (matcher.find()) {
                val foundUrl = matcher.group()
                // Check if this URL contains the partial URL
                if (foundUrl != null && foundUrl.contains(partialUrl)) {
                    return foundUrl
                }
            }
            
            // If no matching URL is found, return the original partial URL
            return partialUrl
        }
    }

    init {
        // Load message history from database when adapter is created
        loadMessageHistory()
    }
    
    // Method to set the message length limit
    fun setMessageLengthLimit(limit: Int) {
        MESSAGE_LENGTH_LIMIT = limit
        notifyDataSetChanged() // Refresh all messages with new limit
    }
    
    // Method to get the current message length limit
    fun getMessageLengthLimit(): Int {
        return MESSAGE_LENGTH_LIMIT
    }

    // Method to clear all messages from the adapter
    fun clearMessages() {
        messages.clear()
        notifyDataSetChanged()
    }

    // Method to set messages from a list
    fun setMessages(newMessages: List<Message>) {
        messages.clear()
        messages.addAll(newMessages)
        notifyDataSetChanged()
    }

    // Load message history from database
    fun loadMessageHistory() {
        val historyMessages = dbHelper.getRecentMessages(100)
        messages.clear()
        messages.addAll(historyMessages)
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        return if (viewType == VIEW_TYPE_SENT) {
            val view = LayoutInflater.from(context).inflate(R.layout.item_message_sent, parent, false)
            SentMessageViewHolder(view)
        } else {
            val view = LayoutInflater.from(context).inflate(R.layout.item_message_received, parent, false)
            ReceivedMessageViewHolder(view)
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        val message = messages[position]
        val timeFormat = SimpleDateFormat("HH:mm", Locale.getDefault())
        val timeString = timeFormat.format(message.timestamp)
        
        if (message.isSent) {
            val sentHolder = holder as SentMessageViewHolder
            sentHolder.bind(message)
            sentHolder.timestamp.text = timeString
            
            // Apply glowing animation if this is the pending message AND we're in SENDING mode
            if (message.isPending && appState == AppState.SENDING) {
                // Start glowing animation
                sentHolder.startGlowAnimation()
            } else {
                // Stop any existing animation
                sentHolder.stopGlowAnimation()
            }
        } else {
            val receivedHolder = holder as ReceivedMessageViewHolder
            receivedHolder.bind(message)
            receivedHolder.timestamp.text = timeString
        }
    }

    override fun getItemCount(): Int = messages.size

    override fun getItemViewType(position: Int): Int {
        return if (messages[position].isSent) VIEW_TYPE_SENT else VIEW_TYPE_RECEIVED
    }

    fun addSentMessage(message: String): Int {
        if (message.isBlank()) return -1
        
        // Stop any existing pending message animation
        setPendingMessage(-1)
        
        val newMessage = Message(message, true, false, Date(), isPending = true)
        val position = messages.size
        
        // Save to database first
        val id = dbHelper.insertMessage(newMessage)
        newMessage.id = id
        
        // Add to in-memory list
        messages.add(newMessage)
        notifyItemInserted(position)
        
        // Set this as the pending message
        setPendingMessage(position)
        
        return position
    }

    fun addReceivedMessage(message: String): Int {
        if (message.isBlank()) return -1
        
        val newMessage = Message(message, false, false, Date())
        val position = messages.size
        
        // Save to database first
        val id = dbHelper.insertMessage(newMessage)
        newMessage.id = id
        
        // Add to in-memory list
        messages.add(newMessage)
        notifyItemInserted(position)
        return position
    }

    fun markMessageAsDelivered(position: Int) {
        if (position >= 0 && position < messages.size && messages[position].isSent) {
            // Stop pending animation
            setPendingMessage(-1)
            
            messages[position].isDelivered = true
            messages[position].isPending = false
            
            // Update in database if we have a valid ID
            val messageId = messages[position].id
            if (messageId != -1L) {
                dbHelper.updateMessageDeliveryStatus(messageId, true)
            }
            
            notifyItemChanged(position)
        }
    }
    
    /**
     * Find a message by its messageId and mark it as delivered
     * @param messageId The unique messageId of the message
     * @return true if the message was found and marked, false otherwise
     */
    fun markMessageAsDeliveredById(messageId: String): Boolean {
        for (i in messages.indices) {
            if (messages[i].messageId == messageId && messages[i].isSent) {
                markMessageAsDelivered(i)
                return true
            }
        }
        return false
    }
    
    /**
     * Find a message by its messageId
     * @param messageId The unique messageId of the message
     * @return The position of the message, or -1 if not found
     */
    fun findMessagePositionById(messageId: String): Int {
        for (i in messages.indices) {
            if (messages[i].messageId == messageId) {
                return i
            }
        }
        return -1
    }
    
    /**
     * Set a message as pending (being sent)
     * @param position The position of the message to set as pending, or -1 to clear
     */
    fun setPendingMessage(position: Int) {
        // Clear previous pending message
        if (pendingMessagePosition >= 0 && pendingMessagePosition < messages.size) {
            messages[pendingMessagePosition].isPending = false
            notifyItemChanged(pendingMessagePosition)
        }
        
        // Set new pending message
        pendingMessagePosition = position
        if (position >= 0 && position < messages.size) {
            messages[position].isPending = true
            notifyItemChanged(position)
        }
    }
    
    // Helper method to create truncated text with "show more" option
    private fun createTruncatedText(message: Message, position: Int): SpannableString {
        val fullText = message.content
        
        // If message is already expanded or shorter than limit, return the full text
        if (message.isExpanded || fullText.length <= MESSAGE_LENGTH_LIMIT) {
            return SpannableString(fullText)
        }
        
        // Create truncated text with "show more" suffix
        val truncatedText = fullText.substring(0, MESSAGE_LENGTH_LIMIT) + "... "
        val showMoreText = context.getString(R.string.show_more)
        val spannableString = SpannableString(truncatedText + showMoreText)
        
        // Make "show more" clickable
        val clickableSpan = object : ClickableSpan() {
            override fun onClick(widget: View) {
                // Expand the message
                message.isExpanded = true
                notifyItemChanged(position)
            }
            
            override fun updateDrawState(ds: TextPaint) {
                super.updateDrawState(ds)
                // Style for "show more" text - grey and no underline
                ds.color = context.getColor(R.color.text_timestamp)
                ds.isUnderlineText = false
            }
        }
        
        // Apply the clickable span to the "show more" part
        spannableString.setSpan(
            clickableSpan,
            truncatedText.length,
            truncatedText.length + showMoreText.length,
            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
        )
        
        return spannableString
    }

    inner class SentMessageViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val messageText: TextView = itemView.findViewById(R.id.tvMessageContent)
        val sentCheck: ImageView = itemView.findViewById(R.id.ivSentCheck)
        val timestamp: TextView = itemView.findViewById(R.id.tvTimestamp)
        private val messageBubble: View = itemView.findViewById(R.id.messageBubble)
        private var glowAnimator: ValueAnimator? = null
        
        fun bind(message: Message) {
            // Make links clickable
            messageText.autoLinkMask = android.text.util.Linkify.WEB_URLS or 
                                       android.text.util.Linkify.EMAIL_ADDRESSES or
                                       android.text.util.Linkify.PHONE_NUMBERS
            
            // Store the position in the tag for link handling
            messageText.tag = adapterPosition
            
            // Apply truncation if needed
            val spannableText = createTruncatedText(message, adapterPosition)
            messageText.text = spannableText
            
            // Enable clickable spans with our custom movement method
            messageText.movementMethod = customLinkMovementMethod
            
            // Show checkmark if message is delivered
            sentCheck.visibility = if (message.isDelivered) View.VISIBLE else View.GONE
            
            // Set long click listener for copying
            itemView.setOnLongClickListener {
                copyToClipboard(message.content)
                true
            }
        }
        
        /**
         * Start the glowing animation on this message bubble
         */
        fun startGlowAnimation() {
            // Only start if not already animating
            if (glowAnimator == null) {
                glowAnimator = AnimationUtils.startGlowAnimation(
                    messageBubble,
                    itemView.context
                )
            }
        }
        
        /**
         * Stop the glowing animation on this message bubble
         */
        fun stopGlowAnimation() {
            glowAnimator?.cancel()
            glowAnimator = null
            
            // Reset the background to the default
            messageBubble.setBackgroundResource(R.drawable.message_sent_background)
        }
    }
    
    inner class ReceivedMessageViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val messageText: TextView = itemView.findViewById(R.id.tvMessageContent)
        val timestamp: TextView = itemView.findViewById(R.id.tvTimestamp)
        
        fun bind(message: Message) {
            // Make links clickable
            messageText.autoLinkMask = android.text.util.Linkify.WEB_URLS or 
                                       android.text.util.Linkify.EMAIL_ADDRESSES or
                                       android.text.util.Linkify.PHONE_NUMBERS
            
            // Store the position in the tag for link handling
            messageText.tag = adapterPosition
            
            // Apply truncation if needed
            val spannableText = createTruncatedText(message, adapterPosition)
            messageText.text = spannableText
            
            // Enable clickable spans with our custom movement method
            messageText.movementMethod = customLinkMovementMethod
            
            // Set long click listener for copying
            itemView.setOnLongClickListener {
                copyToClipboard(message.content)
                true
            }
        }
    }
    
    private fun copyToClipboard(message: String) {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = ClipData.newPlainText("NFC Message", message)
        clipboard.setPrimaryClip(clip)
        Toast.makeText(context, context.getString(R.string.message_copied), Toast.LENGTH_SHORT).show()
    }
    
    // Clean up database resources when adapter is no longer needed
    fun cleanup() {
        dbHelper.close()
        
        // Clean up any active animations
        glowAnimator?.cancel()
        glowAnimator = null
    }

    // Method to get a message at a specific position (for testing)
    fun getItem(position: Int): Message {
        return messages[position]
    }
    
    // Method to get the display text for a message (for testing)
    fun getDisplayText(message: Message): String {
        val fullText = message.content
        
        // If message is already expanded or shorter than limit, return the full text
        if (message.isExpanded || fullText.length <= MESSAGE_LENGTH_LIMIT) {
            return fullText
        }
        
        // Create truncated text with "show more" suffix
        val truncatedText = fullText.substring(0, MESSAGE_LENGTH_LIMIT) + "... "
        val showMoreText = context.getString(R.string.show_more)
        return truncatedText + showMoreText
    }

    /**
     * Update the current app state
     */
    fun updateAppState(newState: AppState) {
        val oldState = appState
        appState = newState
        
        // If we changed from SENDING to another state, or to SENDING from another state,
        // we need to refresh the pending message to update its animation
        if (oldState == AppState.SENDING || newState == AppState.SENDING) {
            if (pendingMessagePosition >= 0) {
                notifyItemChanged(pendingMessagePosition)
            }
        }
    }
} 