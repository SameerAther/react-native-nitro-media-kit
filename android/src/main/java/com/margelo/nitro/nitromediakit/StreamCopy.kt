package com.margelo.nitro.nitromediakit

import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import java.io.File
import java.nio.ByteBuffer

internal object StreamCopy {
  fun keyframeRange(
    file: File,
    range: VideoSegment,
    durationMs: Double,
  ): VideoSegment {
    require(
      range.startMs.isFinite() &&
        range.endMs.isFinite() &&
        range.startMs >= 0 &&
        range.endMs > range.startMs &&
        range.endMs <= durationMs,
    ) { "Invalid trim range." }
    val extractor = MediaExtractor()
    try {
      extractor.setDataSource(file.path)
      val index =
        (0 until extractor.trackCount).firstOrNull {
          extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("video/") ==
            true
        }
          ?: error("No video track.")
      extractor.selectTrack(index)
      extractor.seekTo((range.startMs * 1000).toLong(), MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
      val start = maxOf(0.0, extractor.sampleTime / 1000.0)
      extractor.seekTo((range.endMs * 1000).toLong(), MediaExtractor.SEEK_TO_NEXT_SYNC)
      val next = extractor.sampleTime / 1000.0
      val end = if (next >= range.endMs && next > start) minOf(durationMs, next) else durationMs
      return VideoSegment(start, end)
    } finally {
      extractor.release()
    }
  }

  fun remux(
    input: File,
    output: File,
    control: PipelineControl,
    range: VideoSegment? = null,
    rotation: Int = 0,
    video: Boolean = true,
    audioIndex: Int? = 0,
  ) {
    val probe = MediaExtractor()
    val muxer = MediaMuxer(output.path, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
    var started = false
    try {
      probe.setDataSource(input.path)
      var audio = 0
      val indices =
        (0 until probe.trackCount).filter { index ->
          val mime = probe.getTrackFormat(index).getString(MediaFormat.KEY_MIME) ?: ""
          if (mime.startsWith("video/")) {
            video &&
              (0 until index).none {
                probe.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("video/") ==
                  true
              }
          } else if (mime.startsWith("audio/")) {
            audio++ == audioIndex
          } else {
            false
          }
        }
      require(indices.isNotEmpty()) { "Requested track does not exist." }
      val targets = indices.associateWith { muxer.addTrack(probe.getTrackFormat(it)) }
      if (video) muxer.setOrientationHint(rotation)
      muxer.start()
      started = true
      val startUs = ((range?.startMs ?: 0.0) * 1000).toLong()
      val endUs = range?.endMs?.times(1000)?.toLong() ?: Long.MAX_VALUE
      val metadata = android.media.MediaCodec.BufferInfo()
      // Copy each compressed stream without retaining an unbounded packet queue.
      for (index in indices) {
        control.check()
        val extractor = MediaExtractor()
        try {
          extractor.setDataSource(input.path)
          extractor.selectTrack(index)
          extractor.seekTo(startUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
          val format = extractor.getTrackFormat(index)
          val requested =
            if (format.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE)) {
              format.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE)
            } else {
              4 *
                1024 *
                1024
            }
          require(requested in 1..32 * 1024 * 1024) { "Compressed packet exceeds the memory budget." }
          val buffer = ByteBuffer.allocateDirect(maxOf(4 * 1024 * 1024, requested))
          while (extractor.sampleTime >= 0 && extractor.sampleTime < endUs) {
            control.check()
            if (extractor.sampleTime < startUs) {
              extractor.advance()
              continue
            }
            buffer.clear()
            val size = extractor.readSampleData(buffer, 0)
            if (size < 0) break
            require(size <= buffer.capacity()) { "Compressed packet exceeds the memory budget." }
            metadata.set(
              0,
              size,
              extractor.sampleTime - startUs,
              extractor.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC,
            )
            muxer.writeSampleData(targets.getValue(index), buffer, metadata)
            if (!extractor.advance()) break
          }
        } finally {
          extractor.release()
        }
      }
      muxer.stop()
      started = false
    } finally {
      probe.release()
      if (started) runCatching { muxer.stop() }
      muxer.release()
    }
  }
}
