package com.example.minicpm_v_demo

import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.example.minicpm_v_demo.rag.crypto.RagTempFileCleaner
import com.example.minicpm_v_demo.rag.db.DocumentEntity
import com.example.minicpm_v_demo.rag.db.DocumentStatus
import com.example.minicpm_v_demo.rag.db.KnowledgeBaseEntity
import com.example.minicpm_v_demo.rag.importer.DocumentImportQueue
import com.example.minicpm_v_demo.rag.naming.KnowledgeBaseNamePolicy
import com.example.minicpm_v_demo.rag.storage.RagDocumentRemovalService
import com.example.minicpm_v_demo.rag.ui.FailedImportNotice
import com.example.minicpm_v_demo.rag.ui.KnowledgeBaseDocumentPresentation
import com.example.minicpm_v_demo.rag.ui.KnowledgeBaseEntityFactory
import com.example.minicpm_v_demo.rag.work.RagImportFailureClassifier
import com.example.minicpm_v_demo.rag.work.RagWorkContract
import com.example.minicpm_v_demo.rag.work.WorkManagerRagWorkCoordinator
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.materialswitch.MaterialSwitch
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first

class KnowledgeBaseActivity : StatusBarVisibleActivity() {
    private lateinit var listView: ListView
    private lateinit var emptyView: TextView
    private lateinit var adapter: KnowledgeBaseAdapter
    private lateinit var importButton: Button
    private lateinit var createButton: Button
    private lateinit var ragSwitch: MaterialSwitch
    private var knowledgeBases = emptyList<KnowledgeBaseEntity>()
    private var selectedKnowledgeBaseId: String? = null
    private val selectedConversationKnowledgeBaseIds = linkedSetOf<String>()
    private val failedImportNotices = linkedMapOf<String, FailedImportNotice>()
    private val observedImportIds = mutableSetOf<String>()
    private var conversationId: Long = -1L
    private val conversationSelectionMode: Boolean get() = conversationId > 0
    private val workManager by lazy { WorkManager.getInstance(this) }
    private val workCoordinator by lazy { WorkManagerRagWorkCoordinator(workManager) }

    private val openDocuments = registerForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments(),
    ) { uris ->
        val knowledgeBaseId = selectedKnowledgeBaseId ?: return@registerForActivityResult
        if (uris.isEmpty()) return@registerForActivityResult
        lifecycleScope.launch {
            val outcomes = withContext(Dispatchers.IO) {
                val app = application as MiniCPMApplication
                val queue = DocumentImportQueue(
                    contentResolver = contentResolver,
                    documentDao = app.ragDatabase.documentDao(),
                    workCoordinator = workCoordinator,
                )
                uris.map { uri ->
                    runCatching { queue.enqueue(uri, knowledgeBaseId) }
                        .fold(
                            onSuccess = { ImportEnqueueOutcome.Queued(it) },
                            onFailure = { error ->
                                ImportEnqueueOutcome.Failed(
                                    FailedImportNotice(
                                        id = "enqueue-${UUID.randomUUID()}",
                                        knowledgeBaseId = knowledgeBaseId,
                                        displayName = uri.lastPathSegment?.substringAfterLast('/')
                                            ?.takeIf(String::isNotBlank) ?: getString(R.string.rag_document_default_name),
                                        reason = KnowledgeBaseDocumentPresentation.failureReason(
                                            RagImportFailureClassifier.code(error),
                                        ),
                                    ),
                                )
                            },
                        )
                }
            }
            outcomes.forEach { outcome ->
                when (outcome) {
                    is ImportEnqueueOutcome.Queued -> observeImport(outcome.documentId)
                    is ImportEnqueueOutcome.Failed -> failedImportNotices[outcome.notice.id] = outcome.notice
                }
            }
            requestRefresh()
            val imported = outcomes.count { it is ImportEnqueueOutcome.Queued }
            Toast.makeText(
                this@KnowledgeBaseActivity,
                getString(R.string.rag_documents_queued, imported, uris.size),
                Toast.LENGTH_LONG,
            ).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_knowledge_base)
        listView = findViewById(R.id.list_knowledge_bases)
        emptyView = findViewById(R.id.tv_empty_knowledge_bases)
        importButton = findViewById(R.id.btn_import_documents)
        createButton = findViewById(R.id.btn_create_knowledge_base)
        ragSwitch = findViewById(R.id.switch_conversation_rag)
        conversationId = intent.getLongExtra(EXTRA_CONVERSATION_ID, -1L)
        findViewById<android.view.View>(R.id.btn_back).setOnClickListener { finish() }
        adapter = KnowledgeBaseAdapter(
            this,
            onSelect = { knowledgeBase ->
                if (conversationSelectionMode) {
                    if (!selectedConversationKnowledgeBaseIds.add(knowledgeBase.id)) {
                        selectedConversationKnowledgeBaseIds.remove(knowledgeBase.id)
                    }
                } else {
                    selectedKnowledgeBaseId = knowledgeBase.id
                }
                requestRefresh()
            },
            onDelete = ::showDeleteConfirmation,
            onDocumentLongPress = ::showDocumentDeleteConfirmation,
            onDismissFailedImport = ::dismissFailedImport,
            showDelete = !conversationSelectionMode,
        )
        listView.adapter = adapter
        createButton.setOnClickListener { showCreateDialog() }
        importButton.setOnClickListener {
            if (conversationSelectionMode) {
                saveConversationSelection()
            } else if (selectedKnowledgeBaseId == null) {
                Toast.makeText(this, R.string.rag_select_knowledge_base_first, Toast.LENGTH_SHORT).show()
            } else {
                openDocuments.launch(SUPPORTED_MIME_TYPES)
            }
        }
        configureMode()
        loadKnowledgeBases()
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                while (true) {
                    refreshList()
                    delay(PROGRESS_REFRESH_MS)
                }
            }
        }
    }

    private fun loadKnowledgeBases() {
        lifecycleScope.launch {
            val loaded = withContext(Dispatchers.IO) {
                val database = (application as MiniCPMApplication).ragDatabase
                Triple(
                    database.knowledgeBaseDao().findAll(),
                    if (conversationSelectionMode) database.conversationRagDao()
                        .findBoundKnowledgeBaseIds(conversationId) else emptyList(),
                    if (conversationSelectionMode) database.conversationRagDao()
                        .findState(conversationId)?.ragEnabled == true else false,
                )
            }
            knowledgeBases = loaded.first
            if (conversationSelectionMode) {
                selectedConversationKnowledgeBaseIds.clear()
                selectedConversationKnowledgeBaseIds.addAll(loaded.second)
                ragSwitch.isChecked = loaded.third
            } else if (selectedKnowledgeBaseId !in knowledgeBases.map { it.id }) {
                selectedKnowledgeBaseId = knowledgeBases.firstOrNull()?.id
            }
            refreshList()
        }
    }

    private fun requestRefresh() {
        lifecycleScope.launch {
            refreshList()
        }
    }

    private suspend fun refreshList() {
        val app = application as MiniCPMApplication
        val knowledgeBaseSnapshot = knowledgeBases
        val selectedConversationSnapshot = selectedConversationKnowledgeBaseIds.toSet()
        val selectedKnowledgeBaseSnapshot = selectedKnowledgeBaseId
        val refreshed = withContext(Dispatchers.IO) {
            val recoveredFailures = mutableListOf<FailedImportNotice>()
            val items = knowledgeBaseSnapshot.map { kb ->
                val documents = app.ragDatabase.documentDao().findByKnowledgeBase(kb.id)
                val visibleDocuments = documents.filterNot { document ->
                    if (document.status != DocumentStatus.FAILED) return@filterNot false
                    val removed = runCatching {
                        documentRemovalService(app).remove(document)
                    }.isSuccess
                    if (removed) {
                        recoveredFailures += document.toFailureNotice()
                    }
                    removed
                }
                KnowledgeBaseListItem(
                    kb,
                    visibleDocuments,
                    emptyList(),
                    if (conversationSelectionMode) kb.id in selectedConversationSnapshot
                    else kb.id == selectedKnowledgeBaseSnapshot,
                )
            }
            items to recoveredFailures
        }
        refreshed.second.forEach { failedImportNotices[it.id] = it }
        val items = refreshed.first.map { item ->
            item.copy(failedImports = failedImportNotices.values.filter { it.knowledgeBaseId == item.knowledgeBase.id })
        }
        adapter.submitItems(items)
        val empty = items.isEmpty()
        emptyView.visibility = if (empty) android.view.View.VISIBLE else android.view.View.GONE
        listView.visibility = if (empty) android.view.View.GONE else android.view.View.VISIBLE
        importButton.isEnabled = if (conversationSelectionMode) {
            !ragSwitch.isChecked || selectedConversationKnowledgeBaseIds.isNotEmpty()
        } else selectedKnowledgeBaseId != null
    }

    private fun observeImport(documentId: String) {
        if (!observedImportIds.add(documentId)) return
        lifecycleScope.launch {
            val state = workCoordinator.observe(documentId)
                .filterNotNull()
                .first { it.state in setOf(WorkInfo.State.SUCCEEDED, WorkInfo.State.FAILED, WorkInfo.State.CANCELLED) }
            if (state.state == WorkInfo.State.FAILED) {
                val failureId = state.failureDocumentId
                val knowledgeBaseId = state.failureKnowledgeBaseId
                val displayName = state.failureDisplayName
                if (failureId != null && knowledgeBaseId != null && displayName != null) {
                    failedImportNotices[failureId] = FailedImportNotice(
                        id = failureId,
                        knowledgeBaseId = knowledgeBaseId,
                        displayName = displayName,
                        reason = KnowledgeBaseDocumentPresentation.failureReason(state.failureErrorCode),
                    )
                    requestRefresh()
                }
            }
            observedImportIds.remove(documentId)
        }
    }

    private fun dismissFailedImport(notice: FailedImportNotice) {
        failedImportNotices.remove(notice.id)
        requestRefresh()
    }

    private fun configureMode() {
        if (!conversationSelectionMode) return
        findViewById<TextView>(R.id.tv_knowledge_base_title).setText(R.string.rag_conversation_title)
        findViewById<TextView>(R.id.tv_knowledge_base_summary).setText(R.string.rag_conversation_summary)
        ragSwitch.visibility = android.view.View.VISIBLE
        ragSwitch.setOnCheckedChangeListener { _, _ -> requestRefresh() }
        createButton.visibility = android.view.View.GONE
        importButton.setText(R.string.rag_save_conversation_selection)
    }

    private fun saveConversationSelection() {
        if (ragSwitch.isChecked && selectedConversationKnowledgeBaseIds.isEmpty()) {
            Toast.makeText(this, R.string.rag_select_knowledge_base_first, Toast.LENGTH_SHORT).show()
            return
        }
        lifecycleScope.launch {
            withContext(Dispatchers.IO) {
                (application as MiniCPMApplication).ragDatabase.conversationRagDao().replaceSelection(
                    conversationId = conversationId,
                    knowledgeBaseIds = selectedConversationKnowledgeBaseIds.toList(),
                    enabled = ragSwitch.isChecked,
                    updatedAt = System.currentTimeMillis(),
                )
            }
            Toast.makeText(
                this@KnowledgeBaseActivity,
                if (ragSwitch.isChecked) R.string.rag_conversation_enabled else R.string.rag_conversation_disabled,
                Toast.LENGTH_SHORT,
            ).show()
            finish()
        }
    }

    private fun showDeleteConfirmation(knowledgeBase: KnowledgeBaseEntity) {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.rag_delete_knowledge_base)
            .setMessage(getString(R.string.rag_delete_knowledge_base_confirm, knowledgeBase.name))
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.delete) { _, _ -> deleteKnowledgeBase(knowledgeBase) }
            .show()
    }

    private fun showDocumentDeleteConfirmation(document: DocumentEntity) {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.rag_delete_document)
            .setMessage(getString(R.string.rag_delete_document_confirm, document.displayName))
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.delete) { _, _ -> deleteDocument(document) }
            .show()
    }

    private fun deleteDocument(document: DocumentEntity) {
        lifecycleScope.launch {
            val removed = withContext(Dispatchers.IO) {
                runCatching {
                    workManager.cancelUniqueWork(RagWorkContract.uniqueWorkName(document.id)).result.get()
                    val app = application as MiniCPMApplication
                    val current = app.ragDatabase.documentDao().findById(document.id)
                    if (current != null) documentRemovalService(app).remove(current)
                }.isSuccess
            }
            Toast.makeText(
                this@KnowledgeBaseActivity,
                if (removed) R.string.rag_document_deleted else R.string.rag_document_delete_failed,
                Toast.LENGTH_SHORT,
            ).show()
            if (removed) requestRefresh()
        }
    }

    private fun deleteKnowledgeBase(knowledgeBase: KnowledgeBaseEntity) {
        lifecycleScope.launch {
            val app = application as MiniCPMApplication
            val deleted = withContext(Dispatchers.IO) {
                runCatching {
                    val documents = app.ragDatabase.documentDao().findByKnowledgeBase(knowledgeBase.id)
                    documents.forEach { document ->
                        workManager.cancelUniqueWork(RagWorkContract.uniqueWorkName(document.id)).result.get()
                        documentRemovalService(app).remove(document)
                    }
                    check(app.ragDatabase.knowledgeBaseDao().deleteById(knowledgeBase.id) == 1)
                }.isSuccess
            }
            if (!deleted) {
                Toast.makeText(this@KnowledgeBaseActivity, R.string.rag_knowledge_base_delete_failed, Toast.LENGTH_SHORT)
                    .show()
                return@launch
            }
            if (selectedKnowledgeBaseId == knowledgeBase.id) selectedKnowledgeBaseId = null
            failedImportNotices.entries.removeAll { it.value.knowledgeBaseId == knowledgeBase.id }
            Toast.makeText(this@KnowledgeBaseActivity, R.string.rag_knowledge_base_deleted, Toast.LENGTH_SHORT).show()
            loadKnowledgeBases()
        }
    }

    private fun documentRemovalService(app: MiniCPMApplication): RagDocumentRemovalService =
        RagDocumentRemovalService(
            stagingDirectory = RagTempFileCleaner.stagingDirectory(noBackupFilesDir),
            deleteRecord = app.ragDatabase.documentDao()::deleteById,
        )

    private fun DocumentEntity.toFailureNotice(): FailedImportNotice = FailedImportNotice(
        id = id,
        knowledgeBaseId = knowledgeBaseId,
        displayName = displayName,
        reason = KnowledgeBaseDocumentPresentation.failureReason(lastErrorCode),
    )

    private fun showCreateDialog() {
        val input = EditText(this).apply { maxLines = 1; hint = getString(R.string.rag_knowledge_base_name_hint) }
        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle(R.string.rag_create_knowledge_base)
            .setView(input)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.confirm, null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(android.content.DialogInterface.BUTTON_POSITIVE).setOnClickListener {
                val validated = runCatching { KnowledgeBaseNamePolicy.validateAndNormalize(input.text.toString()) }
                    .getOrElse {
                        input.error = getString(R.string.rag_invalid_knowledge_base_name)
                        return@setOnClickListener
                    }
                lifecycleScope.launch {
                    val id = UUID.randomUUID().toString()
                    val timestamp = System.currentTimeMillis()
                    val inserted = runCatching {
                        withContext(Dispatchers.IO) {
                            val app = application as MiniCPMApplication
                            val verifiedTokenizer = app.embeddingModelManager.openInstalled()
                            app.ragDatabase.knowledgeBaseDao().insert(
                                KnowledgeBaseEntityFactory.create(
                                    id = id,
                                    displayName = validated.displayName,
                                    normalizedName = validated.normalizedName,
                                    timestamp = timestamp,
                                    verifiedTokenizer = verifiedTokenizer,
                                ),
                            )
                        }
                    }.isSuccess
                    if (inserted) {
                        selectedKnowledgeBaseId = id
                        dialog.dismiss()
                        loadKnowledgeBases()
                    } else {
                        input.error = getString(R.string.rag_duplicate_knowledge_base_name)
                    }
                }
            }
        }
        dialog.show()
    }

    companion object {
        const val EXTRA_CONVERSATION_ID = "conversationId"
        private const val PROGRESS_REFRESH_MS = 1_000L
        private val SUPPORTED_MIME_TYPES = arrayOf(
            "text/*", "application/pdf", "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
            "application/vnd.openxmlformats-officedocument.presentationml.presentation",
        )

    }

    private sealed interface ImportEnqueueOutcome {
        data class Queued(val documentId: String) : ImportEnqueueOutcome
        data class Failed(val notice: FailedImportNotice) : ImportEnqueueOutcome
    }
}
