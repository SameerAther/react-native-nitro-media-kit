package com.margelo.nitro.nitromediakit

internal object MediaErrors {
  fun normalize(error: Throwable): PipelineFailure {
    if (error is PipelineFailure) return error
    val message = error.message ?: "Media processing failed."
    val explicit = Regex("\\[([A-Z_]+)\\]").find(message)?.groupValues?.get(1)
    val code =
      explicit ?: when (error) {
        is kotlinx.coroutines.CancellationException -> "CANCELLED"
        is IllegalArgumentException -> "INVALID_ARGUMENT"
        is java.io.IOException -> "IO_ERROR"
        else -> "PROCESSING_FAILED"
      }
    return PipelineFailure(code, message)
  }
}
