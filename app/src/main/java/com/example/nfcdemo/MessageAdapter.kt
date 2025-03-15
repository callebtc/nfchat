package com.example.nfcdemo

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.recyclerview.widget.RecyclerView
import com.example.nfcdemo.data.MessageDbHelper
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MessageAdapter(private val context: Context) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    companion object {
        private const val VIEW_TYPE_SENT = 1
        private const val VIEW_TYPE_RECEIVED = 2
    }

    data class Message(
        val content: String,
        val isSent: Boolean,
        var isDelivered: Boolean = false,
        val timestamp: Date = Date(),
        var id: Long = -1 // Database ID
    )

    private val messages = mutableListOf<Message>()
    private val dbHelper = MessageDbHelper(context)

    init {
        // Load message history from database when adapter is created
        loadMessageHistory()
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
            receivedHolder.bind(message.content)
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

    inner class SentMessageViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val messageText: TextView = itemView.findViewById(R.id.tvMessageContent)
        val sentCheck: ImageView = itemView.findViewById(R.id.ivSentCheck)
        val timestamp: TextView = itemView.findViewById(R.id.tvTimestamp)
        
        fun bind(message: Message) {
            // Make links clickable
            messageText.autoLinkMask = android.text.util.Linkify.WEB_URLS or 
                                       android.text.util.Linkify.EMAIL_ADDRESSES or
                                       android.text.util.Linkify.PHONE_NUMBERS
            messageText.text = message.content
            
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
        
        fun bind(message: String) {
            // Make links clickable
            messageText.autoLinkMask = android.text.util.Linkify.WEB_URLS or 
                                       android.text.util.Linkify.EMAIL_ADDRESSES or
                                       android.text.util.Linkify.PHONE_NUMBERS
            messageText.text = message
            
            // Set long click listener for copying
            itemView.setOnLongClickListener {
                copyToClipboard(message)
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