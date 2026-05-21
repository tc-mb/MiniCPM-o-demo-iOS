package com.example.minicpm_v_demo

import android.graphics.Bitmap
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.google.android.material.progressindicator.LinearProgressIndicator
import io.noties.markwon.Markwon

class ChatAdapter(
    private val markwon: Markwon
) : ListAdapter<ChatMessage, RecyclerView.ViewHolder>(DiffCallback()) {

    companion object {
        private const val TYPE_WELCOME = 0
        private const val TYPE_USER = 1
        private const val TYPE_AI = 2
    }

    private var onSuggestionClick: ((String) -> Unit)? = null
    private var onStopClick: (() -> Unit)? = null

    private var activeAiHolder: AiMessageViewHolder? = null
    private var activeAiId: Long = -1L

    fun setOnSuggestionClick(listener: (String) -> Unit) {
        onSuggestionClick = listener
    }

    fun setOnStopClick(listener: () -> Unit) {
        onStopClick = listener
    }

    fun setActiveAiMessage(id: Long) {
        activeAiId = id
    }

    fun clearActiveAiMessage() {
        activeAiId = -1L
        activeAiHolder = null
    }

    fun updateStreamingText(id: Long, text: String) {
        if (id == activeAiId) {
            activeAiHolder?.updateText(text)
        }
    }

    fun setGeneratingDone(id: Long) {
        if (id == activeAiId) {
            activeAiHolder?.setStopButtonVisible(false)
        }
    }

    override fun getItemViewType(position: Int): Int {
        return when (getItem(position)) {
            is ChatMessage.WelcomeCard -> TYPE_WELCOME
            is ChatMessage.UserMessage -> TYPE_USER
            is ChatMessage.AiMessage -> TYPE_AI
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        return when (viewType) {
            TYPE_WELCOME -> {
                val view = LayoutInflater.from(parent.context)
                    .inflate(R.layout.item_welcome_card, parent, false)
                WelcomeViewHolder(view)
            }
            TYPE_USER -> {
                val view = LayoutInflater.from(parent.context)
                    .inflate(R.layout.item_user_message, parent, false)
                UserMessageViewHolder(view)
            }
            TYPE_AI -> {
                val view = LayoutInflater.from(parent.context)
                    .inflate(R.layout.item_ai_message, parent, false)
                AiMessageViewHolder(view)
            }
            else -> throw IllegalArgumentException("Unknown view type: $viewType")
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val item = getItem(position)) {
            is ChatMessage.WelcomeCard -> (holder as WelcomeViewHolder).bind(item)
            is ChatMessage.UserMessage -> (holder as UserMessageViewHolder).bind(item)
            is ChatMessage.AiMessage -> {
                val aiHolder = holder as AiMessageViewHolder
                aiHolder.bind(item)
                if (item.id == activeAiId) {
                    activeAiHolder = aiHolder
                }
            }
        }
    }

    override fun onViewRecycled(holder: RecyclerView.ViewHolder) {
        if (holder is AiMessageViewHolder && holder == activeAiHolder) {
            activeAiHolder = null
        }
    }

    inner class WelcomeViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val tvWelcomeTitle: TextView = itemView.findViewById(R.id.tv_welcome_title)
        private val tvWelcomeDesc: TextView = itemView.findViewById(R.id.tv_welcome_desc)
        private val btnSuggestion1: MaterialButton = itemView.findViewById(R.id.btn_suggestion_1)
        private val btnSuggestion2: MaterialButton = itemView.findViewById(R.id.btn_suggestion_2)

        fun bind(item: ChatMessage.WelcomeCard) {
            val ctx = itemView.context
            if (item.isTextOnly) {
                tvWelcomeTitle.setText(R.string.welcome_title_text)
                tvWelcomeDesc.setText(R.string.welcome_desc_text)
                btnSuggestion1.setText(R.string.suggestion_1_text)
                btnSuggestion2.setText(R.string.suggestion_2_text)
                btnSuggestion1.setIconResource(R.drawable.ic_lightbulb)
                btnSuggestion2.setIconResource(R.drawable.ic_lightbulb)
            } else {
                tvWelcomeTitle.setText(R.string.welcome_title)
                tvWelcomeDesc.setText(R.string.welcome_desc)
                btnSuggestion1.setText(R.string.suggestion_1)
                btnSuggestion2.setText(R.string.suggestion_2)
                btnSuggestion1.setIconResource(R.drawable.ic_lightbulb)
                btnSuggestion2.setIconResource(R.drawable.ic_image)
            }
            val s1 = btnSuggestion1.text.toString()
            val s2 = btnSuggestion2.text.toString()
            btnSuggestion1.setOnClickListener { onSuggestionClick?.invoke(s1) }
            btnSuggestion2.setOnClickListener { onSuggestionClick?.invoke(s2) }
        }
    }

    inner class UserMessageViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val tvText: TextView = itemView.findViewById(R.id.tv_user_text)
        private val flImageContainer: View = itemView.findViewById(R.id.fl_user_image_container)
        private val ivImage: ImageView = itemView.findViewById(R.id.iv_user_image)
        private val ivVideoBadge: ImageView = itemView.findViewById(R.id.iv_video_play_badge)
        private val tvImageInfo: TextView = itemView.findViewById(R.id.tv_image_info)
        private val progressImage: LinearProgressIndicator = itemView.findViewById(R.id.progress_image)

        fun bind(item: ChatMessage.UserMessage) {
            tvText.text = item.text
            tvText.visibility = if (item.text.isNotBlank()) View.VISIBLE else View.GONE

            if (item.imageBitmap != null) {
                ivImage.setImageBitmap(item.imageBitmap)
                flImageContainer.visibility = View.VISIBLE
                ivVideoBadge.visibility = if (item.isVideo) View.VISIBLE else View.GONE
                tvImageInfo.visibility = View.VISIBLE
                tvImageInfo.text = item.imageInfo ?: ""
                progressImage.visibility = if (item.isPrefilling) View.VISIBLE else View.GONE
            } else {
                flImageContainer.visibility = View.GONE
                ivVideoBadge.visibility = View.GONE
                tvImageInfo.visibility = View.GONE
                progressImage.visibility = View.GONE
            }
        }
    }

    inner class AiMessageViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val tvText: TextView = itemView.findViewById(R.id.tv_ai_text)
        private val btnStop: MaterialButton = itemView.findViewById(R.id.btn_stop_generating)
        private val layoutThinking: View = itemView.findViewById(R.id.layout_thinking)
        private val layoutThinkingHeader: View = itemView.findViewById(R.id.layout_thinking_header)
        private val tvThinkingArrow: TextView = itemView.findViewById(R.id.tv_thinking_arrow)
        private val tvThinkingLabel: TextView = itemView.findViewById(R.id.tv_thinking_label)
        private val tvThinkingText: TextView = itemView.findViewById(R.id.tv_thinking_text)
        private val dividerThinking: View = itemView.findViewById(R.id.divider_thinking)

        private var thinkingExpanded = false
        private var streamingMinWidth = 0

        fun bind(item: ChatMessage.AiMessage) {
            if (!item.isGenerating) {
                streamingMinWidth = 0
                (tvText.parent as? ViewGroup)?.minimumWidth = 0
            }
            renderWithThinking(item.text, item.isGenerating)
            btnStop.visibility = if (item.isGenerating) View.VISIBLE else View.GONE
            btnStop.setOnClickListener {
                onStopClick?.invoke()
            }
        }

        fun updateText(text: String) {
            val contentLayout = tvText.parent as? ViewGroup
            if (contentLayout != null && contentLayout.width > streamingMinWidth) {
                streamingMinWidth = contentLayout.width
            }
            contentLayout?.minimumWidth = streamingMinWidth
            renderWithThinking(text, true)
        }

        fun setStopButtonVisible(visible: Boolean) {
            btnStop.visibility = if (visible) View.VISIBLE else View.GONE
        }

        private fun renderWithThinking(raw: String, isGenerating: Boolean) {
            val parsed = parseThinkingBlock(raw, isGenerating)

            if (parsed.thinkingText != null) {
                layoutThinking.visibility = View.VISIBLE

                if (parsed.isThinking) {
                    tvThinkingLabel.text = "思考中…"
                    thinkingExpanded = true
                } else {
                    tvThinkingLabel.text = "思考过程"
                }

                tvThinkingArrow.text = if (thinkingExpanded) "▾" else "▸"
                tvThinkingText.visibility = if (thinkingExpanded) View.VISIBLE else View.GONE
                dividerThinking.visibility = if (!parsed.isThinking) View.VISIBLE else View.GONE

                markwon.setMarkdown(tvThinkingText,
                    MarkdownEscape.normalizeResponseText(parsed.thinkingText))

                layoutThinkingHeader.setOnClickListener {
                    thinkingExpanded = !thinkingExpanded
                    tvThinkingArrow.text = if (thinkingExpanded) "▾" else "▸"
                    tvThinkingText.visibility = if (thinkingExpanded) View.VISIBLE else View.GONE
                }
            } else {
                layoutThinking.visibility = View.GONE
            }

            val display = parsed.responseText
            if (display.isNotEmpty()) {
                tvText.visibility = View.VISIBLE
                markwon.setMarkdown(tvText, MarkdownEscape.normalizeResponseText(display))
            } else {
                tvText.visibility = View.GONE
            }
        }

        private fun parseThinkingBlock(text: String, isGenerating: Boolean): ParsedThinking {
            val thinkStart = text.indexOf("<think>")
            if (thinkStart < 0) {
                return ParsedThinking(null, text, false)
            }

            val contentAfterTag = text.substring(thinkStart + "<think>".length)
            val thinkEnd = contentAfterTag.indexOf("</think>")

            return if (thinkEnd < 0) {
                ParsedThinking(
                    thinkingText = contentAfterTag.trim(),
                    responseText = "",
                    isThinking = true
                )
            } else {
                ParsedThinking(
                    thinkingText = contentAfterTag.substring(0, thinkEnd).trim(),
                    responseText = contentAfterTag.substring(thinkEnd + "</think>".length).trimStart('\n'),
                    isThinking = false
                )
            }
        }
    }

    private data class ParsedThinking(
        val thinkingText: String?,
        val responseText: String,
        val isThinking: Boolean
    )

    class DiffCallback : DiffUtil.ItemCallback<ChatMessage>() {
        override fun areItemsTheSame(oldItem: ChatMessage, newItem: ChatMessage): Boolean {
            return oldItem.id == newItem.id
        }

        override fun areContentsTheSame(oldItem: ChatMessage, newItem: ChatMessage): Boolean {
            return when {
                oldItem is ChatMessage.UserMessage && newItem is ChatMessage.UserMessage ->
                    oldItem.text == newItem.text &&
                            oldItem.imageBitmap == newItem.imageBitmap &&
                            oldItem.imageInfo == newItem.imageInfo &&
                            oldItem.isPrefilling == newItem.isPrefilling &&
                            oldItem.isVideo == newItem.isVideo
                oldItem is ChatMessage.AiMessage && newItem is ChatMessage.AiMessage ->
                    oldItem.isGenerating == newItem.isGenerating &&
                            (oldItem.isGenerating || oldItem.text == newItem.text)
                oldItem is ChatMessage.WelcomeCard && newItem is ChatMessage.WelcomeCard ->
                    oldItem.isTextOnly == newItem.isTextOnly
                else -> false
            }
        }
    }
}
