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
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MessageAdapter(private val context: Context) : 
    RecyclerView.Adapter<RecyclerView.ViewHolder>() {
    
    companion object {
        private const val VIEW_TYPE_SENT = 1
        private const val VIEW_TYPE_RECEIVED = 2
    }
    
    data class Message(
        val content: String, 
        val isSent: Boolean,
        var isDelivered: Boolean = false,
        val timestamp: Date = Date()
    )
    
    private val messages = mutableListOf<Message>()
    
    fun addSentMessage(message: String): Int {
        val position = messages.size
        messages.add(Message(message, true, false, Date()))
        notifyItemInserted(position)
        return position
    }
    
    fun markMessageAsDelivered(position: Int) {
        if (position >= 0 && position < messages.size && messages[position].isSent) {
            messages[position].isDelivered = true
            notifyItemChanged(position)
        }
    }
    
    fun addReceivedMessage(message: String) {
        messages.add(Message(message, false, false, Date()))
        notifyItemInserted(messages.size - 1)
    }
    
    override fun getItemViewType(position: Int): Int {
        return if (messages[position].isSent) VIEW_TYPE_SENT else VIEW_TYPE_RECEIVED
    }
    
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        return when (viewType) {
            VIEW_TYPE_SENT -> {
                val view = LayoutInflater.from(parent.context)
                    .inflate(R.layout.item_message_sent, parent, false)
                SentMessageViewHolder(view)
            }
            else -> {
                val view = LayoutInflater.from(parent.context)
                    .inflate(R.layout.item_message_received, parent, false)
                ReceivedMessageViewHolder(view)
            }
        }
    }
    
    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        val message = messages[position]
        val timeFormat = SimpleDateFormat("HH:mm", Locale.getDefault())
        val timeString = timeFormat.format(message.timestamp)
        
        when (holder) {
            is SentMessageViewHolder -> {
                val sentHolder = holder as SentMessageViewHolder
                sentHolder.messageText.text = message.content
                sentHolder.sentCheck.visibility = if (message.isDelivered) View.VISIBLE else View.GONE
                sentHolder.timestamp.text = timeString
            }
            is ReceivedMessageViewHolder -> {
                val receivedHolder = holder as ReceivedMessageViewHolder
                receivedHolder.messageText.text = message.content
                receivedHolder.timestamp.text = timeString
            }
        }
    }
    
    override fun getItemCount(): Int = messages.size
    
    inner class SentMessageViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val messageText: TextView = itemView.findViewById(R.id.tvMessageContent)
        val sentCheck: ImageView = itemView.findViewById(R.id.ivSentCheck)
        val timestamp: TextView = itemView.findViewById(R.id.tvTimestamp)
        
        fun bind(message: Message) {
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
} 