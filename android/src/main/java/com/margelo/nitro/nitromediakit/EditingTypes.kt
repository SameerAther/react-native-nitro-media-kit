package com.margelo.nitro.nitromediakit

internal fun basicOptions(
  trim: VideoSegment? = null,
  watermark: String? = null,
  watermarkOptions: WatermarkOptions? = null,
  preset: CompressionPreset? = null,
  video: CompressVideoOptions? = null,
  audio: AudioPolicy? = null,
  preserveMetadata: Boolean? = null,
  limits: ProcessingLimits? = null,
) = ExportVideoOptions(
  trim,
  null,
  null,
  null,
  null,
  null,
  null,
  null,
  watermark,
  watermarkOptions,
  preset,
  video,
  audio,
  preserveMetadata,
  limits,
)
