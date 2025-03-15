package com.example.nfcdemo

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.text.Layout
import android.text.Selection
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
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MessageAdapter(private val context: Context) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    companion object {
        private const val VIEW_TYPE_SENT = 1
        private const val VIEW_TYPE_RECEIVED = 2
        
        // Configurable message length limit before truncation
        private var MESSAGE_LENGTH_LIMIT = 200
    }

    data class Message(
        val content: String,
        val isSent: Boolean,
        var isDelivered: Boolean = false,
        val timestamp: Date = Date(),
        var id: Long = -1, // Database ID
        var isExpanded: Boolean = false // Track expanded state
    )

    private val messages = mutableListOf<Message>()
    private val dbHelper = MessageDbHelper(context)
    
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
                    return handleLinkClick(url)
                }
            }
            
            return super.onTouchEvent(widget, buffer, event)
        }
        
        private fun handleLinkClick(url: String): Boolean {
            // Check if we should use the internal browser
            val useInternalBrowser = dbHelper.getBooleanSetting(
                SettingsContract.SettingsEntry.KEY_USE_INTERNAL_BROWSER, 
                false
            )
            
            // Prepend http:// if the URL doesn't have a scheme
            val fullUrl = if (!url.startsWith("http://") && !url.startsWith("https://")) {
                "http://$url"
            } else {
                url
            }
            
            if (useInternalBrowser) {
                // Open the URL in an internal WebView
                val intent = Intent(context, WebViewActivity::class.java)
                intent.putExtra(WebViewActivity.EXTRA_URL, fullUrl)
                context.startActivity(intent)
            } else {
                // Open the URL in an external browser
                val intent = Intent(Intent.ACTION_VIEW, Uri.parse(fullUrl))
                context.startActivity(intent)
            }
            
            return true
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

    private fun loadMessageHistory() {
        val historyMessages = dbHelper.getRecentMessages(100)
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
        
        val newMessage = Message(message, true, false, Date())
        val position = messages.size
        
        // Save to database first
        val id = dbHelper.insertMessage(newMessage)
        newMessage.id = id
        
        // Add to in-memory list
        messages.add(newMessage)
        notifyItemInserted(position)
        return position
    }

    fun addReceivedMessage(message: String) {
        if (message.isBlank()) return
        
        val newMessage = Message(message, false, false, Date())
        
        // Save to database first
        val id = dbHelper.insertMessage(newMessage)
        newMessage.id = id
        
        // Add to in-memory list
        messages.add(newMessage)
        notifyItemInserted(messages.size - 1)
    }

    fun markMessageAsDelivered(position: Int) {
        if (position >= 0 && position < messages.size && messages[position].isSent) {
            messages[position].isDelivered = true
            
            // Update in database if we have a valid ID
            val messageId = messages[position].id
            if (messageId != -1L) {
                dbHelper.updateMessageDeliveryStatus(messageId, true)
            }
            
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
        
        fun bind(message: Message) {
            // Make links clickable
            messageText.autoLinkMask = android.text.util.Linkify.WEB_URLS or 
                                       android.text.util.Linkify.EMAIL_ADDRESSES or
                                       android.text.util.Linkify.PHONE_NUMBERS
            
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
    }
    
    inner class ReceivedMessageViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val messageText: TextView = itemView.findViewById(R.id.tvMessageContent)
        val timestamp: TextView = itemView.findViewById(R.id.tvTimestamp)
        
        fun bind(message: Message) {
            // Make links clickable
            messageText.autoLinkMask = android.text.util.Linkify.WEB_URLS or 
                                       android.text.util.Linkify.EMAIL_ADDRESSES or
                                       android.text.util.Linkify.PHONE_NUMBERS
            
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
    }
} 