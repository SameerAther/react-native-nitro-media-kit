package com.margelo.nitro.nitromediakit

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import androidx.media3.common.C
import androidx.media3.transformer.Composition
import androidx.media3.transformer.EditedMediaItemSequence
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.io.File
import java.nio.ByteOrder
import kotlin.math.pow

@android.annotation.SuppressLint("UnsafeOptInUsageError")
internal object LoudnessEngine {
  private val analysisGate = Semaphore(1)
  private val normalizationGate = Semaphore(1)

  suspend fun analyze(
    file: File,
    options: AudioAnalysisOptions,
    control: PipelineControl,
  ): LoudnessAnalysis =
    analysisGate.withPermit {
      analyzeOwned(file, options, control)
    }

  private fun analyzeOwned(
    file: File,
    options: AudioAnalysisOptions,
    control: PipelineControl,
  ): LoudnessAnalysis {
    val index = options.trackIndex ?: 0.0
    require(index.isFinite() && index >= 0 && index % 1 == 0.0) { "Invalid audio track index." }
    val extractor = MediaExtractor()
    var decoder: MediaCodec? = null
    try {
      extractor.setDataSource(file.path)
      val tracks =
        (0 until extractor.trackCount).filter {
          extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") ==
            true
        }
      require(index < tracks.size) { "Requested audio track does not exist." }
      val track = tracks[index.toInt()]
      val format = extractor.getTrackFormat(track)
      extractor.selectTrack(track)
      val rate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
      val channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
      val duration =
        if (format.containsKey(
            MediaFormat.KEY_DURATION,
          )
        ) {
          format.getLong(MediaFormat.KEY_DURATION) / 1000.0
        } else {
          Double.POSITIVE_INFINITY
        }
      val range = options.trim
      if (range !=
        null
      ) {
        require(
          range.startMs.isFinite() &&
            range.endMs.isFinite() &&
            range.startMs >= 0 &&
            range.endMs > range.startMs &&
            range.endMs <= duration,
        ) {
          "Invalid audio analysis trim."
        }
      }
      val start = ((range?.startMs ?: 0.0) * 1000).toLong()
      val end = range?.endMs?.times(1000)?.toLong() ?: Long.MAX_VALUE
      extractor.seekTo(start, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
      val meter = LoudnessMeter(rate, channels)
      decoder = MediaCodec.createDecoderByType(requireNotNull(format.getString(MediaFormat.KEY_MIME)))
      format.setInteger(MediaFormat.KEY_PCM_ENCODING, C.ENCODING_PCM_16BIT)
      decoder.configure(format, null, null, 0)
      decoder.start()
      var inputEnded = false
      var outputEnded = false
      val info = MediaCodec.BufferInfo()
      val scratch = FloatArray(16384)
      var encoding = C.ENCODING_PCM_16BIT
      while (!outputEnded) {
        control.check()
        if (!inputEnded) {
          val slot = decoder.dequeueInputBuffer(10_000)
          if (slot >= 0) {
            val buffer = requireNotNull(decoder.getInputBuffer(slot))
            buffer.clear()
            val time = extractor.sampleTime
            val size = if (time >= 0 && time < end) extractor.readSampleData(buffer, 0) else -1
            if (size < 0) {
              decoder.queueInputBuffer(slot, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
              inputEnded = true
            } else {
              decoder.queueInputBuffer(slot, 0, size, time, 0)
              extractor.advance()
            }
          }
        }
        val slot = decoder.dequeueOutputBuffer(info, 10_000)
        if (slot == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
          val output = decoder.outputFormat
          require(
            output.getInteger(MediaFormat.KEY_CHANNEL_COUNT) == channels &&
              output.getInteger(MediaFormat.KEY_SAMPLE_RATE) == rate,
          ) {
            "Decoder changed the audio layout during analysis."
          }
          encoding =
            if (output.containsKey(
                MediaFormat.KEY_PCM_ENCODING,
              )
            ) {
              output.getInteger(MediaFormat.KEY_PCM_ENCODING)
            } else {
              C.ENCODING_PCM_16BIT
            }
          require(encoding == C.ENCODING_PCM_16BIT || encoding == C.ENCODING_PCM_FLOAT) { "Unsupported PCM encoding." }
        } else if (slot >= 0) {
          try {
            if (info.size == 0) {
              outputEnded = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
              continue
            }
            val buffer = requireNotNull(decoder.getOutputBuffer(slot)).order(ByteOrder.nativeOrder())
            buffer.position(info.offset)
            buffer.limit(info.offset + info.size)
            val bytes = if (encoding == C.ENCODING_PCM_FLOAT) 4 else 2
            var frame = 0L
            var used = 0
            while (buffer.remaining() >= bytes * channels) {
              val time = info.presentationTimeUs + frame * 1_000_000L / rate
              for (c in 0 until channels) {
                val sample = if (encoding == C.ENCODING_PCM_FLOAT) buffer.float else buffer.short / 32768f
                if (time >= start && time < end) scratch[used++] = sample
              }
              frame++
              if (used + channels > scratch.size) {
                meter.add(scratch, used)
                used = 0
              }
            }
            if (used > 0) meter.add(scratch, used)
            outputEnded = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
          } finally {
            decoder.releaseOutputBuffer(slot, false)
          }
        }
      }
      val result = meter.finish()
      return LoudnessAnalysis(
        result.lufs,
        result.samplePeakDb,
        result.truePeakDb,
        result.durationMs,
        channels.toDouble(),
        rate.toDouble(),
      )
    } finally {
      decoder?.let {
        runCatching { it.stop() }
        it.release()
      }
      extractor.release()
    }
  }

  suspend fun normalize(
    kit: NitroMediaKit,
    job: MediaJob,
    input: File,
    options: NormalizeAudioOptions,
  ): NormalizedAudioResult =
    normalizationGate.withPermit {
      normalizeOwned(kit, job, input, options)
    }

  private suspend fun normalizeOwned(
    kit: NitroMediaKit,
    job: MediaJob,
    input: File,
    options: NormalizeAudioOptions,
  ): NormalizedAudioResult {
    val target = options.targetLufs ?: -16.0
    val ceiling = options.maxTruePeakDbtp ?: -1.0
    ExportPlanner.finite(target, -36.0, -5.0, "targetLufs")
    ExportPlanner.finite(ceiling, -12.0, 0.0, "maxTruePeakDbtp")
    val bitrate = options.bitrateKbps ?: 192.0
    ExportPlanner.finite(bitrate, 32.0, 512.0, "bitrateKbps")
    val before = analyze(input, AudioAnalysisOptions(options.trackIndex, options.trim, options.limits), job.control)
    require(before.durationMs >= 400) { "Loudness normalization requires at least 400ms of audio." }
    val desired = before.integratedLufs?.let { target - it } ?: 0.0
    val peakBound = before.truePeakDbtp?.let { ceiling - it } ?: desired
    var gain = minOf(desired, peakBound)
    var after: LoudnessAnalysis? = null
    var output: File? = null
    val selected =
      if ((options.trackIndex ?: 0.0) ==
        0.0
      ) {
        input
      } else {
        AudioEngine.selected(job, input, options.trackIndex!!)
      }
    for (attempt in 0..2) {
      job.control.check()
      val file = job.output("m4a")
      val item =
        AudioEngine.item(
          selected,
          options.trim,
          processors = listOf(EnvelopeAudioProcessor(10.0.pow(gain / 20), 0.0, 0.0, before.durationMs)),
        )
      val composition = Composition.Builder(EditedMediaItemSequence.withAudioFrom(listOf(item))).build()
      kit.transform(
        emptyList(),
        file,
        audioKbps = bitrate.toInt(),
        control = job.control,
        compositionOverride = composition,
      )
      val measured = analyze(file, AudioAnalysisOptions(null, null, null), job.control)
      require(measured.channels == before.channels) { "Encoder changed the normalized audio channel count." }
      if ((measured.truePeakDbtp ?: Double.NEGATIVE_INFINITY) <= ceiling + 0.05) {
        output = file
        after = measured
        break
      }
      file.delete()
      gain -= (measured.truePeakDbtp!! - ceiling) + 0.5
    }
    val file = requireNotNull(output) { "Cannot meet the requested encoded peak ceiling." }
    val measured = requireNotNull(after)
    val bytes = file.length().toDouble()
    val mapping = job.commitPaths(listOf(file.path))
    val audio =
      AudioExportResult(
        mapping.getValue(file.path),
        measured.durationMs,
        bytes,
        measured.channels,
        measured.sampleRate,
        "audio/mp4a-latm",
      )
    return NormalizedAudioResult(audio, before, measured, gain, gain < desired - 0.01)
  }
}
