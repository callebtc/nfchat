package com.example.nfcdemo

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.recyclerview.widget.RecyclerView

class MessageAdapter(private val context: Context) : 
    RecyclerView.Adapter<RecyclerView.ViewHolder>() {
    
    companion object {
        private const val VIEW_TYPE_SENT = 1
        private const val VIEW_TYPE_RECEIVED = 2
    }
    
    data class Message(
        val content: String, 
        val isSent: Boolean,
        var isDelivered: Boolean = false
    )
    
    private val messages = mutableListOf<Message>()
    
    fun addSentMessage(message: String): Int {
        val position = messages.size
        messages.add(Message(message, true))
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
        messages.add(Message(message, false))
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
        
        when (holder) {
            is SentMessageViewHolder -> holder.bind(message)
            is ReceivedMessageViewHolder -> holder.bind(message.content)
        }
    }
    
    override fun getItemCount(): Int = messages.size
    
    inner class SentMessageViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val tvMessageContent: TextView = itemView.findViewById(R.id.tvMessageContent)
        private val btnCopy: ImageButton = itemView.findViewById(R.id.btnCopy)
        private val ivSentCheck: ImageView = itemView.findViewById(R.id.ivSentCheck)
        
        fun bind(message: Message) {
            tvMessageContent.text = message.content
            
            // Show checkmark if message is delivered
            ivSentCheck.visibility = if (message.isDelivered) View.VISIBLE else View.GONE
            
            btnCopy.setOnClickListener {
                copyToClipboard(message.content)
            }
        }
    }
    
    inner class ReceivedMessageViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val tvMessageContent: TextView = itemView.findViewById(R.id.tvMessageContent)
        private val btnCopy: ImageButton = itemView.findViewById(R.id.btnCopy)
        
        fun bind(message: String) {
            tvMessageContent.text = message
            
            btnCopy.setOnClickListener {
                copyToClipboard(message)
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