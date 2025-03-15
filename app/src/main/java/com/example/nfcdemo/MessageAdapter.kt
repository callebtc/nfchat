package com.example.nfcdemo

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.TextView
import android.widget.Toast
import androidx.recyclerview.widget.RecyclerView

class MessageAdapter(private val context: Context) : 
    RecyclerView.Adapter<MessageAdapter.MessageViewHolder>() {
    
    private val messages = mutableListOf<String>()
    
    fun addMessage(message: String) {
        messages.add(message)
        notifyItemInserted(messages.size - 1)
    }
    
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): MessageViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_message, parent, false)
        return MessageViewHolder(view)
    }
    
    override fun onBindViewHolder(holder: MessageViewHolder, position: Int) {
        val message = messages[position]
        holder.bind(message)
    }
    
    override fun getItemCount(): Int = messages.size
    
    inner class MessageViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val tvMessageContent: TextView = itemView.findViewById(R.id.tvMessageContent)
        private val btnCopy: ImageButton = itemView.findViewById(R.id.btnCopy)
        
        fun bind(message: String) {
            tvMessageContent.text = message
            
            btnCopy.setOnClickListener {
                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                val clip = ClipData.newPlainText("NFC Message", message)
                clipboard.setPrimaryClip(clip)
                Toast.makeText(context, context.getString(R.string.message_copied), Toast.LENGTH_SHORT).show()
            }
        }
    }
} 