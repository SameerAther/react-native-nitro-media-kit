package com.margelo.nitro.nitromediakit

import android.content.Context
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.audio.ChannelMixingAudioProcessor
import androidx.media3.common.audio.ChannelMixingMatrix
import androidx.media3.common.audio.SpeedProvider
import androidx.media3.transformer.Composition
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.EditedMediaItemSequence
import androidx.media3.transformer.Effects
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.roundToInt

/** Gain/fades operate on interleaved PCM in output time, with saturation instead of integer wraparound. */
internal class EnvelopeAudioProcessor(
  private val volume: Double,
  private val fadeInMs: Double,
  private val fadeOutMs: Double,
  private val durationMs: Double,
  private val timelineStartMs: Double = 0.0,
  private val globalFadeInMs: Double = 0.0,
  private val globalFadeOutMs: Double = 0.0,
  private val globalDurationMs: Double = durationMs,
) : BaseAudioProcessor() {
  private var frames = 0L

  override fun onConfigure(format: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
    if (format.encoding != C.ENCODING_PCM_16BIT) throw AudioProcessor.UnhandledAudioFormatException(format)
    return format
  }

  override fun onFlush(streamMetadata: AudioProcessor.StreamMetadata) {
    frames = 0
  }

  override fun queueInput(inputBuffer: ByteBuffer) {
    val input = inputBuffer.order(ByteOrder.nativeOrder())
    val output = replaceOutputBuffer(input.remaining()).order(ByteOrder.nativeOrder())
    while (input.remaining() >= inputAudioFormat.bytesPerFrame) {
      val ms = frames * 1000.0 / inputAudioFormat.sampleRate
      val globalTime = timelineStartMs + ms
      val globalGain =
        minOf(
          1.0,
          if (globalFadeInMs >
            0
          ) {
            globalTime / globalFadeInMs
          } else {
            1.0
          },
          if (globalFadeOutMs >
            0
          ) {
            (globalDurationMs - globalTime).coerceAtLeast(0.0) / globalFadeOutMs
          } else {
            1.0
          },
        )
      val gain =
        volume * globalGain *
          minOf(
            1.0,
            if (fadeInMs > 0) ms / fadeInMs else 1.0,
            if (fadeOutMs > 0) (durationMs - ms).coerceAtLeast(0.0) / fadeOutMs else 1.0,
          )
      repeat(inputAudioFormat.channelCount) {
        output.putShort((input.short * gain).roundToInt().coerceIn(-32768, 32767).toShort())
      }
      frames++
    }
    output.flip()
  }
}

@android.annotation.SuppressLint("UnsafeOptInUsageError")
internal object AudioEngine {
  private val extractionPermit = Semaphore(1)

  fun speed(speed: Double): SpeedProvider =
    if (speed == 1.0) {
      SpeedProvider.DEFAULT
    } else {
      object : SpeedProvider {
        override fun getSpeed(timeUs: Long) = speed.toFloat()

        override fun getNextSpeedChangeTimeUs(timeUs: Long) = C.TIME_UNSET
      }
    }

  fun envelope(
    edit: AudioEditOptions?,
    duration: Double,
    music: Boolean = false,
  ): List<AudioProcessor> =
    if (edit ==
      null
    ) {
      emptyList()
    } else {
      listOf(
        EnvelopeAudioProcessor(
          if (music) edit.musicVolume ?: 1.0 else edit.volume ?: 1.0,
          edit.fadeInMs ?: 0.0,
          edit.fadeOutMs ?: 0.0,
          duration,
        ),
      )
    }

  fun selected(
    job: MediaJob,
    input: File,
    index: Double,
  ): File {
    require(index.isFinite() && index >= 0 && index % 1 == 0.0) { "trackIndex must be a nonnegative integer." }
    return job
      .output(
        "m4a",
      ).also { StreamCopy.remux(input, it, job.control, video = false, audioIndex = index.toInt()) }
  }

  fun item(
    file: File,
    trim: VideoSegment?,
    speed: Double = 1.0,
    processors: List<AudioProcessor> = emptyList(),
  ): EditedMediaItem {
    val media = MediaItem.Builder().setUri(Uri.fromFile(file))
    trim?.let {
      media.setClippingConfiguration(
        MediaItem.ClippingConfiguration
          .Builder()
          .setStartPositionUs((it.startMs * 1000).toLong())
          .setEndPositionUs(
            (
              it.endMs *
                1000
            ).toLong(),
          ).build(),
      )
    }
    return EditedMediaItem
      .Builder(
        media.build(),
      ).setRemoveVideo(true)
      .setSpeed(speed(speed))
      .setEffects(Effects(processors, emptyList()))
      .build()
  }

  suspend fun composition(
    job: MediaJob,
    base: EditedMediaItem,
    input: File,
    source: VideoSource,
    options: ExportVideoOptions,
    plan: VideoPlan,
  ): Composition? {
    val edit = options.audioEdit ?: return null
    val sequences = mutableListOf<EditedMediaItemSequence>()
    val selectedIndex = edit.trackIndex ?: 0.0
    require(source.audioTracks == 0 && edit.trackIndex == null || selectedIndex < source.audioTracks) {
      "Requested audio track does not exist."
    }
    val separate = selectedIndex > 0
    sequences.add(
      if (separate ||
        source.audioTracks == 0
      ) {
        EditedMediaItemSequence.withVideoFrom(listOf(base))
      } else {
        EditedMediaItemSequence.withAudioAndVideoFrom(listOf(base))
      },
    )
    if (separate) {
      val selected = selected(job, input, selectedIndex)
      sequences.add(
        EditedMediaItemSequence.withAudioFrom(
          listOf(
            item(
              selected,
              options.trim,
              options.speed ?: 1.0,
              stereo(
                probe(selected).second.getInteger(MediaFormat.KEY_CHANNEL_COUNT),
                plan.audio == AudioPolicy.STEREO,
              ) +
                envelope(edit, plan.durationMs),
            ),
          ),
        ),
      )
    }
    edit.musicUri?.let { uri ->
      val file = job.resolve(uri)
      val format = probe(file)
      val available = format.first
      val musicChannels = format.second.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
      val sourceChannels =
        if (source.audioTracks ==
          0
        ) {
          0
        } else {
          trackFormat(input, selectedIndex.toInt()).getInteger(MediaFormat.KEY_CHANNEL_COUNT)
        }
      require(plan.audio == AudioPolicy.STEREO || sourceChannels == 0 || sourceChannels == musicChannels) {
        "Music and source channel layouts differ; select stereo explicitly."
      }
      val trim = edit.musicTrim ?: VideoSegment(0.0, minOf(available, plan.durationMs - (edit.musicStartMs ?: 0.0)))
      require(
        trim.startMs.isFinite() &&
          trim.endMs.isFinite() &&
          trim.startMs >= 0 &&
          trim.endMs > trim.startMs &&
          trim.endMs <= available,
      ) {
        "Invalid music trim range."
      }
      val remaining = plan.durationMs - (edit.musicStartMs ?: 0.0)
      val bounded = VideoSegment(trim.startMs, minOf(trim.endMs, trim.startMs + remaining))
      require(bounded.endMs > bounded.startMs) { "Music starts after the output ends." }
      val seq = EditedMediaItemSequence.Builder(setOf(C.TRACK_TYPE_AUDIO))
      if ((edit.musicStartMs ?: 0.0) > 0) seq.addGap((edit.musicStartMs!! * 1000).toLong())
      seq.addItem(
        item(
          file,
          bounded,
          processors =
            stereo(musicChannels, plan.audio == AudioPolicy.STEREO) +
              listOf(
                EnvelopeAudioProcessor(
                  edit.musicVolume ?: 1.0,
                  0.0,
                  0.0,
                  bounded.endMs - bounded.startMs,
                ),
              ),
        ),
      )
      sequences.add(seq.build())
    }
    return Composition
      .Builder(sequences)
      .setHdrMode(
        if (plan.color ==
          ColorPolicy.SDR
        ) {
          Composition.HDR_MODE_TONE_MAP_HDR_TO_SDR_USING_OPEN_GL
        } else {
          Composition.HDR_MODE_KEEP_HDR
        },
      ).build()
  }

  fun trackFormat(
    file: File,
    index: Int = 0,
  ): MediaFormat {
    val extractor = MediaExtractor()
    try {
      extractor.setDataSource(file.path)
      val tracks =
        (0 until extractor.trackCount).map { extractor.getTrackFormat(it) }.filter {
          it.getString(MediaFormat.KEY_MIME)?.startsWith("audio/") ==
            true
        }
      require(index in tracks.indices) { "Requested audio track does not exist." }
      return tracks[index]
    } finally {
      extractor.release()
    }
  }

  fun stereo(
    channels: Int,
    enabled: Boolean,
  ): List<AudioProcessor> =
    if (!enabled ||
      channels == 2
    ) {
      emptyList()
    } else {
      listOf(
        ChannelMixingAudioProcessor().apply {
          putChannelMixingMatrix(ChannelMixingMatrix.createForConstantPower(channels, 2))
        },
      )
    }

  fun probe(file: File): Pair<Double, MediaFormat> {
    val e = MediaExtractor()
    try {
      e.setDataSource(file.path)
      val f =
        (0 until e.trackCount)
          .map {
            e.getTrackFormat(
              it,
            )
          }.firstOrNull { it.getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true }
          ?: error("No audio track.")
      return (if (f.containsKey(MediaFormat.KEY_DURATION)) f.getLong(MediaFormat.KEY_DURATION) / 1000.0 else 0.0) to f
    } finally {
      e.release()
    }
  }

  suspend fun extract(
    kit: NitroMediaKit,
    context: Context,
    job: MediaJob,
    input: File,
    options: ExtractAudioOptions,
  ): AudioExportResult = extractionPermit.withPermit { extractOwned(kit, context, job, input, options) }

  private suspend fun extractOwned(
    kit: NitroMediaKit,
    context: Context,
    job: MediaJob,
    input: File,
    options: ExtractAudioOptions,
  ): AudioExportResult {
    val selected = if ((options.trackIndex ?: 0.0) == 0.0) input else selected(job, input, options.trackIndex!!)
    val duration = probe(selected).first
    options.trim?.let {
      require(
        it.startMs.isFinite() &&
          it.endMs.isFinite() &&
          it.startMs >= 0 &&
          it.endMs > it.startMs &&
          it.endMs <= duration,
      ) {
        "Invalid audio trim."
      }
    }
    val kbps = options.bitrateKbps ?: 192.0
    ExportPlanner.finite(kbps, 32.0, 512.0, "bitrateKbps")
    val output = job.output("m4a")
    val composition =
      Composition
        .Builder(
          EditedMediaItemSequence.withAudioFrom(listOf(item(selected, options.trim))),
        ).build()
    kit.transform(
      emptyList(),
      output,
      audioKbps = kbps.toInt(),
      control = job.control,
      compositionOverride = composition,
    )
    val (actual, format) = probe(output)
    val bytes = output.length().toDouble()
    val mapping = job.commitPaths(listOf(output.path))
    return AudioExportResult(
      mapping.getValue(output.path),
      actual,
      bytes,
      format.getInteger(MediaFormat.KEY_CHANNEL_COUNT).toDouble(),
      format.getInteger(MediaFormat.KEY_SAMPLE_RATE).toDouble(),
      format.getString(MediaFormat.KEY_MIME) ?: "audio/mp4a-latm",
    )
  }
}
