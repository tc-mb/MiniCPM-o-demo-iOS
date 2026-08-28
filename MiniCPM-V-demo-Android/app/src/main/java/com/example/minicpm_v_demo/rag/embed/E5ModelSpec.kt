package com.example.minicpm_v_demo.rag.embed

object E5ModelSpec {
    val PINNED = EmbeddingModelManifest(
        modelId = "intfloat/multilingual-e5-small",
        revision = "132949c958b5e9a03bbf6cfb3f5f71430c2a3cf6",
        dimension = 384,
        maxTokens = 512,
        files = mapOf(
            "model.int8.onnx" to "739c8f25bbe6d8a6001cd2f048701da9879140cc67d4e9327716111e869dd717",
            "tokenizer.onnx" to "3396f311d68a8ee4351c0949ab2626543334c5566d7f8ea17b026952ac14d0fe",
            "tokenizer.json" to "0b44a9d7b51c3c62626640cda0e2c2f70fdacdc25bbbd68038369d14ebdf4c39",
            "sentencepiece.bpe.model" to "cfc8146abe2a0488e9e2a0c56de7952f7c11ab059eca145a0a727afce0db2865",
            "config.json" to "bbb7c1333fc4b3e27fbc9cd5d2070aabcc1d4dfb99917c3633e772f97545a6b6",
            "tokenizer_config.json" to "a1d6bc8734a6f635dc158508bef000f8e2e5a759c7d92f984b2c86e5ff53425b",
            "special_tokens_map.json" to "d05497f1da52c5e09554c0cd874037a083e1dc1b9cfd48034d1c717f1afc07a7",
        ),
    )
}
