package com.example.minicpm_v_demo.rag.embed

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import ai.onnxruntime.extensions.OrtxPackage
import ai.onnxruntime.providers.NNAPIFlags
import java.io.File
import java.util.EnumSet
import kotlin.math.min

enum class E5InputKind(val prefix: String) { QUERY("query: "), PASSAGE("passage: ") }

enum class E5ExecutionProfile(val nnapiFlags: Set<NNAPIFlags>) {
    CPU(emptySet()),
    NNAPI(setOf(NNAPIFlags.CPU_DISABLED)),
    NNAPI_FP16(setOf(NNAPIFlags.CPU_DISABLED, NNAPIFlags.USE_FP16)),
}

object E5ExecutionSelection {
    val SELECTED = E5ExecutionProfile.CPU
}

class E5Embedder private constructor(
    private val environment: OrtEnvironment,
    private val tokenizerSession: OrtSession,
    private val modelSession: OrtSession,
    private val spec: EmbeddingModelManifest,
) : AutoCloseable, E5Tokenizer {
    override val modelId: String = spec.modelId
    override val modelSha256: String = spec.files.getValue("model.int8.onnx")
    override val tokenizerSha256: String = spec.files.getValue("tokenizer.onnx")

    @Synchronized
    fun tokenIds(text: String): LongArray = tokenize(text).ids

    override fun tokenSpans(text: String): List<TokenSpan> {
        val encoded = tokenize(text)
        val utf16Offsets = Utf8TokenOffsets.toUtf16Boundaries(text, encoded.offsets)
        return (0 until utf16Offsets.lastIndex).mapNotNull { index ->
            val start = utf16Offsets[index]
            val end = utf16Offsets[index + 1]
            if (end > start && start >= 0 && end <= text.length) TokenSpan(start, end) else null
        }
    }

    @Synchronized
    fun embed(texts: List<String>, kind: E5InputKind): List<FloatArray> {
        require(texts.isNotEmpty())
        return texts.map { text -> embedOne(kind.prefix + text) }
    }

    private fun embedOne(text: String): FloatArray {
        val encoded = tokenize(text)
        val size = min(encoded.ids.size, spec.maxTokens)
        require(size > 0)
        val ids = encoded.ids.copyOf(size)
        val attention = LongArray(size) { 1L }
        val tokenTypes = LongArray(size)
        OnnxTensor.createTensor(environment, arrayOf(ids)).use { idsTensor ->
            OnnxTensor.createTensor(environment, arrayOf(attention)).use { maskTensor ->
                OnnxTensor.createTensor(environment, arrayOf(tokenTypes)).use { typesTensor ->
                    modelSession.run(mapOf(
                        "input_ids" to idsTensor,
                        "attention_mask" to maskTensor,
                        "token_type_ids" to typesTensor,
                    )).use { result ->
                        @Suppress("UNCHECKED_CAST")
                        val output = result[0].value as Array<Array<FloatArray>>
                        require(output.size == 1 && output[0].size == size)
                        return E5Pooling.maskedMeanAndNormalize(output[0], attention)
                    }
                }
            }
        }
    }

    private fun tokenize(text: String): Encoded {
        OnnxTensor.createTensor(environment, arrayOf(text), longArrayOf(1)).use { input ->
            tokenizerSession.run(mapOf("inputs" to input)).use { result ->
                val ids = (result[0] as OnnxTensor).longBuffer.run { LongArray(remaining()).also(::get) }
                val offsets = (result[2] as OnnxTensor).intBuffer.run { IntArray(remaining()).also(::get) }
                require(ids.isNotEmpty() && offsets.size == ids.size)
                return Encoded(ids, offsets)
            }
        }
    }

    override fun close() {
        tokenizerSession.close()
        modelSession.close()
    }

    private data class Encoded(val ids: LongArray, val offsets: IntArray)

    companion object {
        fun open(
            directory: File,
            spec: EmbeddingModelManifest,
            executionProfile: E5ExecutionProfile = E5ExecutionProfile.CPU,
        ): E5Embedder {
            val root = EmbeddingModelPackageVerifier.verify(directory, spec)
            val environment = OrtEnvironment.getEnvironment("minicpm-rag-e5")
            val tokenizerOptions = OrtSession.SessionOptions().apply {
                registerCustomOpLibrary(OrtxPackage.getLibraryPath())
                setIntraOpNumThreads(1)
            }
            val modelOptions = OrtSession.SessionOptions().apply {
                setIntraOpNumThreads(2)
                if (executionProfile.nnapiFlags.isNotEmpty()) {
                    addNnapi(EnumSet.copyOf(executionProfile.nnapiFlags))
                }
            }
            try {
                val tokenizer = environment.createSession(root.resolve("tokenizer.onnx").absolutePath, tokenizerOptions)
                val model = environment.createSession(root.resolve("model.int8.onnx").absolutePath, modelOptions)
                require(model.inputNames == setOf("input_ids", "attention_mask", "token_type_ids"))
                require(model.outputNames.contains("last_hidden_state"))
                return E5Embedder(environment, tokenizer, model, spec)
            } finally {
                tokenizerOptions.close()
                modelOptions.close()
            }
        }

        fun cosine(left: FloatArray, right: FloatArray): Float {
            require(left.size == right.size && left.isNotEmpty())
            return left.indices.sumOf { (left[it] * right[it]).toDouble() }.toFloat()
        }
    }
}
