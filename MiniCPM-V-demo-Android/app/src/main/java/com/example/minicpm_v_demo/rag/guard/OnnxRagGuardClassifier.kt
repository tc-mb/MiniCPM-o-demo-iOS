package com.example.minicpm_v_demo.rag.guard

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import com.example.minicpm_v_demo.rag.embed.E5Embedder
import com.example.minicpm_v_demo.rag.retrieval.AnswerabilityClassifier
import com.example.minicpm_v_demo.rag.retrieval.AnswerabilityLabel
import com.example.minicpm_v_demo.rag.retrieval.AnswerabilityVerdict
import com.example.minicpm_v_demo.rag.retrieval.RetrievedChunk
import java.io.File
import kotlin.math.exp

class OnnxRagGuardClassifier private constructor(
    private val manifest: RagGuardModelManifest,
    private val encode: (String) -> LongArray,
    private val infer: (LongArray, LongArray, Int) -> FloatArray,
    private val closeAction: () -> Unit,
) : RagGuardClassifier, AnswerabilityClassifier, AutoCloseable {
    override suspend fun classify(
        question: String,
        sources: List<RetrievedChunk>,
    ): AnswerabilityVerdict = classifyAnswerability(question, sources)

    override suspend fun classifyAnswerability(
        question: String,
        sources: List<RetrievedChunk>,
    ): AnswerabilityVerdict {
        val probabilities = runTask(
            RagGuardInput.answerabilityPair(question, sources),
            manifest.answerabilityTaskId,
            manifest.answerabilityClassCount,
        )
        return AnswerabilityVerdict(
            label = AnswerabilityLabel.entries[probabilities.maxIndex()],
            supportedProbability = probabilities[AnswerabilityLabel.SUPPORTED.ordinal],
            modelSha256 = manifest.model.sha256,
        )
    }

    override suspend fun classifyGroundedness(
        question: String,
        sources: List<RetrievedChunk>,
        answer: String,
    ): GroundednessVerdict {
        val probabilities = runTask(
            RagGuardInput.groundednessPair(question, sources, answer),
            manifest.groundednessTaskId,
            manifest.groundednessClassCount,
        )
        return GroundednessVerdict(
            label = GroundednessLabel.entries[probabilities.maxIndex()],
            groundedProbability = probabilities[GroundednessLabel.GROUNDED.ordinal],
            modelSha256 = manifest.model.sha256,
        )
    }

    @Synchronized
    private fun runTask(pair: RagGuardTextPair, taskId: Int, classCount: Int): FloatArray {
        val ids = RagGuardInput.assembleXlmrPair(
            protectedIds = encode(pair.protectedText),
            evidenceIds = encode(pair.evidenceText),
            maxTokens = manifest.maxTokens,
        )
        val attention = LongArray(ids.size) { 1L }
        val logits = infer(ids, attention, taskId)
        require(logits.size == manifest.groundednessClassCount)
        if (taskId == manifest.answerabilityTaskId) {
            require(logits.last() == manifest.answerabilityPaddingLogit)
        }
        return softmax(logits.copyOf(classCount))
    }

    override fun close() = closeAction()

    companion object {
        fun open(
            directory: File,
            tokenizer: E5Embedder,
            manifest: RagGuardModelManifest = CurrentRagGuardModel.PINNED,
        ): OnnxRagGuardClassifier {
            require(tokenizer.tokenizerSha256 == manifest.externalTokenizerSha256) {
                "RAG guard tokenizer hash mismatch"
            }
            val root = RagGuardModelPackageVerifier.verify(directory, manifest)
            val environment = OrtEnvironment.getEnvironment("minicpm-rag-guard")
            val options = OrtSession.SessionOptions().apply { setIntraOpNumThreads(2) }
            val session = try {
                environment.createSession(root.resolve(manifest.model.name).absolutePath, options)
            } finally {
                options.close()
            }
            try {
                require(session.inputNames == setOf("input_ids", "attention_mask", "task_ids"))
                require(session.outputNames == setOf("logits"))
                return OnnxRagGuardClassifier(
                    manifest = manifest,
                    encode = tokenizer::tokenIds,
                    infer = { ids, attention, taskId ->
                        OnnxTensor.createTensor(environment, arrayOf(ids)).use { idsTensor ->
                            OnnxTensor.createTensor(environment, arrayOf(attention)).use { maskTensor ->
                                OnnxTensor.createTensor(environment, longArrayOf(taskId.toLong())).use { taskTensor ->
                                    session.run(
                                        mapOf(
                                            "input_ids" to idsTensor,
                                            "attention_mask" to maskTensor,
                                            "task_ids" to taskTensor,
                                        ),
                                    ).use { result ->
                                        val buffer = (result[0] as OnnxTensor).floatBuffer
                                        FloatArray(buffer.remaining()).also(buffer::get)
                                    }
                                }
                            }
                        }
                    },
                    closeAction = session::close,
                )
            } catch (error: Exception) {
                session.close()
                throw error
            }
        }

        internal fun forTest(
            manifest: RagGuardModelManifest,
            encode: (String) -> LongArray,
            infer: (LongArray, LongArray, Int) -> FloatArray,
            closeAction: () -> Unit = {},
        ) = OnnxRagGuardClassifier(manifest, encode, infer, closeAction)

        internal fun softmax(logits: FloatArray): FloatArray {
            require(logits.size in 3..4 && logits.all(Float::isFinite))
            val maximum = logits.max()
            val exponentials = DoubleArray(logits.size) { index ->
                exp((logits[index] - maximum).toDouble())
            }
            val denominator = exponentials.sum()
            return FloatArray(logits.size) { index -> (exponentials[index] / denominator).toFloat() }
        }

        private fun FloatArray.maxIndex(): Int = indices.maxBy { this[it] }
    }
}
