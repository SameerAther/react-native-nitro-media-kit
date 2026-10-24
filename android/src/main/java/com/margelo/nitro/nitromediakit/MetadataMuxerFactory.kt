package com.margelo.nitro.nitromediakit

import androidx.media3.common.Metadata
import androidx.media3.muxer.Muxer
import androidx.media3.transformer.DefaultMuxer

/** Codec configuration/orientation remains in Format; optional tags require an explicit opt-in. */
@android.annotation.SuppressLint("UnsafeOptInUsageError")
internal class MetadataMuxerFactory(
  private val preserveMetadata: Boolean,
  private val factory: Muxer.Factory = DefaultMuxer.Factory(),
) : Muxer.Factory by factory {
  override fun create(path: String): Muxer {
    val delegate = factory.create(path)
    return object : Muxer by delegate {
      override fun addMetadataEntry(metadataEntry: Metadata.Entry) {
        if (preserveMetadata) delegate.addMetadataEntry(metadataEntry)
      }
    }
  }
}
