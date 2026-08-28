package com.example.minicpm_v_demo

import android.content.Context
import android.content.res.ColorStateList
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.example.minicpm_v_demo.rag.db.DocumentEntity
import com.example.minicpm_v_demo.rag.db.KnowledgeBaseEntity
import com.example.minicpm_v_demo.rag.ui.KnowledgeBaseDocumentPresentation
import com.example.minicpm_v_demo.rag.ui.FailedImportNotice
import com.example.minicpm_v_demo.rag.ui.HorizontalSwipeDismissPolicy
import com.example.minicpm_v_demo.rag.ui.KnowledgeBaseDocumentInteractionPolicy
import com.example.minicpm_v_demo.rag.work.RagDocumentStageResources
import com.google.android.material.card.MaterialCardView

data class KnowledgeBaseListItem(
    val knowledgeBase: KnowledgeBaseEntity,
    val documents: List<DocumentEntity>,
    val failedImports: List<FailedImportNotice>,
    val selected: Boolean,
)

class KnowledgeBaseAdapter(
    context: Context,
    private val onSelect: (KnowledgeBaseEntity) -> Unit,
    private val onDelete: (KnowledgeBaseEntity) -> Unit,
    private val onDocumentLongPress: (DocumentEntity) -> Unit,
    private val onDismissFailedImport: (FailedImportNotice) -> Unit,
    private val showDelete: Boolean = true,
) : BaseAdapter() {
    private val inflater = LayoutInflater.from(context)
    private var items = emptyList<KnowledgeBaseListItem>()

    fun submitItems(newItems: List<KnowledgeBaseListItem>) {
        items = newItems
        notifyDataSetChanged()
    }

    override fun getCount(): Int = items.size
    override fun getItem(position: Int): KnowledgeBaseListItem = items[position]
    override fun getItemId(position: Int): Long = items[position].knowledgeBase.id.hashCode().toLong()

    override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
        val view = convertView ?: inflater.inflate(R.layout.item_knowledge_base, parent, false)
        val item = getItem(position)
        val card = view.findViewById<MaterialCardView>(R.id.card_knowledge_base)
        val title = view.findViewById<TextView>(R.id.tv_knowledge_base_name)
        val statusContainer = view.findViewById<LinearLayout>(R.id.container_document_status)
        title.text = item.knowledgeBase.name
        card.setCardBackgroundColor(
            ContextCompat.getColor(view.context, if (item.selected) R.color.rag_selected_surface else R.color.surface),
        )
        card.strokeColor = ContextCompat.getColor(
            view.context,
            if (item.selected) R.color.rag_selected_outline else R.color.rag_card_outline,
        )
        card.strokeWidth = view.context.resources.getDimensionPixelSize(
            if (item.selected) R.dimen.rag_selected_stroke else R.dimen.rag_card_stroke,
        )
        card.setOnClickListener { onSelect(item.knowledgeBase) }
        val deleteButton = view.findViewById<ImageButton>(R.id.btn_delete_knowledge_base)
        deleteButton.visibility = if (showDelete) View.VISIBLE else View.GONE
        deleteButton.setOnClickListener {
            onDelete(item.knowledgeBase)
        }

        statusContainer.removeAllViews()
        item.documents.forEach { document ->
            val presentation = KnowledgeBaseDocumentPresentation.from(document.status, document.lastErrorCode)
                ?: return@forEach
            val status = inflater.inflate(R.layout.item_knowledge_base_document_status, statusContainer, false) as TextView
            resetStatusView(status)
            status.text = when (presentation) {
                is KnowledgeBaseDocumentPresentation.Processing ->
                    view.context.getString(
                        R.string.rag_document_stage_status,
                        document.displayName,
                        view.context.getString(RagDocumentStageResources.bodyFor(presentation.status)),
                    )
                KnowledgeBaseDocumentPresentation.Uploaded ->
                    view.context.getString(R.string.rag_document_uploaded_action, document.displayName)
                is KnowledgeBaseDocumentPresentation.Failure ->
                    view.context.getString(R.string.rag_document_failed, document.displayName, presentation.reason)
            }
            val color = when (presentation) {
                is KnowledgeBaseDocumentPresentation.Processing -> R.color.rag_status_neutral
                KnowledgeBaseDocumentPresentation.Uploaded -> R.color.rag_status_success
                is KnowledgeBaseDocumentPresentation.Failure -> R.color.rag_status_error
            }
            status.setTextColor(ContextCompat.getColor(view.context, color))
            status.backgroundTintList = ColorStateList.valueOf(
                ContextCompat.getColor(
                    view.context,
                    when (presentation) {
                        is KnowledgeBaseDocumentPresentation.Processing -> R.color.rag_status_neutral_surface
                        KnowledgeBaseDocumentPresentation.Uploaded -> R.color.rag_status_success_surface
                        is KnowledgeBaseDocumentPresentation.Failure -> R.color.rag_status_error_surface
                    },
                ),
            )
            if (KnowledgeBaseDocumentInteractionPolicy.canDeleteByLongPress(document.status)) {
                status.isLongClickable = true
                status.setOnLongClickListener {
                    onDocumentLongPress(document)
                    true
                }
            }
            statusContainer.addView(status)
        }
        item.failedImports.forEach { failure ->
            val status = inflater.inflate(R.layout.item_knowledge_base_document_status, statusContainer, false) as TextView
            resetStatusView(status)
            status.text = view.context.getString(
                R.string.rag_document_failed_dismiss,
                failure.displayName,
                failure.reason,
            )
            status.setTextColor(ContextCompat.getColor(view.context, R.color.rag_status_error))
            status.backgroundTintList = ColorStateList.valueOf(
                ContextCompat.getColor(view.context, R.color.rag_status_error_surface),
            )
            bindSwipeToDismiss(status, failure)
            statusContainer.addView(status)
        }
        statusContainer.visibility = if (statusContainer.childCount == 0) View.GONE else View.VISIBLE
        return view
    }

    private fun resetStatusView(view: TextView) {
        view.animate().cancel()
        view.translationX = 0f
        view.alpha = 1f
        view.isClickable = false
        view.isLongClickable = false
        view.setOnClickListener(null)
        view.setOnLongClickListener(null)
        view.setOnTouchListener(null)
    }

    @Suppress("ClickableViewAccessibility")
    private fun bindSwipeToDismiss(view: TextView, failure: FailedImportNotice) {
        var startX = 0f
        var startY = 0f
        view.isClickable = true
        view.contentDescription = view.context.getString(
            R.string.rag_failed_import_swipe_description,
            failure.displayName,
            failure.reason,
        )
        view.setOnTouchListener { touched, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    startX = event.rawX
                    startY = event.rawY
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val horizontal = (event.rawX - startX).coerceAtMost(0f)
                    val vertical = kotlin.math.abs(event.rawY - startY)
                    if (-horizontal > vertical) {
                        touched.translationX = horizontal
                        touched.alpha = (1f - (-horizontal / touched.width.coerceAtLeast(1))).coerceAtLeast(0.45f)
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    val dismiss = HorizontalSwipeDismissPolicy.shouldDismiss(
                        startX,
                        startY,
                        event.rawX,
                        event.rawY,
                        touched.resources.displayMetrics.density,
                    )
                    if (dismiss) {
                        touched.animate()
                            .translationX(-touched.width.toFloat())
                            .alpha(0f)
                            .setDuration(SWIPE_ANIMATION_MS)
                            .withEndAction { onDismissFailedImport(failure) }
                            .start()
                    } else {
                        touched.animate().translationX(0f).alpha(1f).setDuration(SWIPE_ANIMATION_MS).start()
                        touched.performClick()
                    }
                    true
                }
                MotionEvent.ACTION_CANCEL -> {
                    touched.animate().translationX(0f).alpha(1f).setDuration(SWIPE_ANIMATION_MS).start()
                    true
                }
                else -> false
            }
        }
    }

    companion object {
        private const val SWIPE_ANIMATION_MS = 160L
    }
}
