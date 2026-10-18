package com.k2fsa.sherpa.onnx.vad.asr

import com.k2fsa.sherpa.onnx.BuildConfig

const val LLM_MODEL = "openai/gpt-oss-20b"

val GROQ_API_KEY: String
    get() = BuildConfig.GROQ_API_KEY
