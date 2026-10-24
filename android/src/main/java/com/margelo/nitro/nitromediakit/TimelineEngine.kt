package com.margelo.nitro.nitromediakit

import android.content.Context
import android.net.Uri
import android.os.SystemClock
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.OverlaySettings
import androidx.media3.common.VideoCompositorSettings
import androidx.media3.common.util.Size
import androidx.media3.effect.StaticOverlaySettings
import androidx.media3.transformer.Composition
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.EditedMediaItemSequence
import androidx.media3.transformer.Effects
import kotlinx.coroutines.sync.withPermit

@android.annotation.SuppressLint("UnsafeOptInUsageError")
internal object TimelineEngine {
  suspend fun export(
    kit: NitroMediaKit,
    context: Context,
    job: MediaJob,
    clips: Array<TimelineClip>,
    options: ExportVideoOptions,
  ): MediaInfoResult = NitroMediaKit.imagePermits.withPermit { exportOwned(kit, context, job, clips, options) }

  private suspend fun exportOwned(
    kit: NitroMediaKit,
    context: Context,
    job: MediaJob,
    clips: Array<TimelineClip>,
    options: ExportVideoOptions,
  ): MediaInfoResult {
    require(clips.isNotEmpty() && clips.size <= 64) { "A timeline requires 1–64 clips." }
    require(options.trim == null && options.speed == null && options.copyPolicy != CopyPolicy.REQUIRE_COPY) {
      "Timeline trim/speed belong on individual clips; timelines require encoding."
    }
    require(
      options.geometry?.crop == null &&
        (options.geometry?.rotation ?: 0.0) == 0.0 &&
        options.geometry?.flipHorizontal != true &&
        options.geometry?.flipVertical != true,
    ) { "Use per-clip geometry for crop, rotation and flips." }
    val files = clips.map { job.resolve(it.uri) }
    val sources = files.map { kit.videoSource(kit.detectVideoInfo(it.path)) }
    val durations =
      clips.indices.map { i ->
        ExportPlanner
          .plan(
            sources[i],
            basicOptions().copy(trim = clips[i].trim, speed = clips[i].speed, geometry = clips[i].geometry),
          ).durationMs
      }
    val transitions = clips.map { it.transitionMs ?: 0.0 }
    val layout = TimelineLayout.make(durations, transitions)
    val starts = layout.starts
    val total = layout.durationMs
    val hasTransitions = transitions.any { it > 0 }
    require(
      !hasTransitions ||
        sources.none {
          it.hdr || it.bitDepth > 8
        } ||
        options.video?.colorPolicy == ColorPolicy.SDR,
    ) {
      "[UNSUPPORTED_EXPORT] Android overlapping video composition requires SDR; request colorPolicy sdr explicitly."
    }
    require(
      sources.none {
        it.bitDepth > 8 && !it.hdr
      } ||
        options.video?.colorPolicy == ColorPolicy.SDR,
    ) { "High-bit-depth SDR timeline encoding requires explicit SDR conversion." }
    val audioSource = sources.firstOrNull { it.audioTracks > 0 }
    require(
      options.audio == AudioPolicy.STEREO ||
        sources.filter { it.audioTracks > 0 }.all { it.audioChannels == audioSource?.audioChannels },
    ) { "Timeline audio layouts differ; select stereo explicitly." }
    val first = sources.first()
    val aggregate =
      first.copy(
        durationMs = total,
        fps =
          sources.indices.maxOf {
            sources[it].fps *
              (clips[it].speed ?: 1.0)
          },
        hdr = sources.any { it.hdr },
        bitDepth = sources.maxOf { it.bitDepth },
        audioTracks =
          if (audioSource ==
            null
          ) {
            0
          } else {
            1
          },
        audioChannels = audioSource?.audioChannels ?: 0,
      )
    val plan = ExportPlanner.plan(aggregate, options, forceEncode = true)
    DeviceCapabilities.check(plan)
    val prepared = OverlayEngine.prepare(kit, job, options, plan, includeLegacyWatermark = true)
    try {
      val lanes = if (hasTransitions) 2 else 1
      val sequences =
        (0 until lanes)
          .map { lane ->
            val builder =
              EditedMediaItemSequence.Builder(
                if (plan.audio == AudioPolicy.MUTE ||
                  audioSource == null
                ) {
                  setOf(C.TRACK_TYPE_VIDEO)
                } else {
                  setOf(C.TRACK_TYPE_VIDEO, C.TRACK_TYPE_AUDIO)
                },
              )
            var cursor = 0.0
            for (i in clips.indices.filter { it % lanes == lane }) {
              val clip = clips[i]
              val source = sources[i]
              if (starts[i] > cursor + 0.001) builder.addGap(((starts[i] - cursor) * 1000).toLong())
              var file = files[i]
              options.audioEdit?.trackIndex?.takeIf { it > 0 && source.audioTracks > 0 }?.let { index ->
                file =
                  job.output("mp4").also {
                    StreamCopy.remux(
                      files[i],
                      it,
                      job.control,
                      rotation = kit.detectVideoInfo(files[i].path).rotationDegrees,
                      audioIndex = index.toInt(),
                    )
                  }
              }
              val media = MediaItem.Builder().setUri(Uri.fromFile(file))
              clip.trim?.let {
                media.setClippingConfiguration(
                  MediaItem.ClippingConfiguration
                    .Builder()
                    .setStartPositionUs(
                      (
                        it.startMs *
                          1000
                      ).toLong(),
                    ).setEndPositionUs((it.endMs * 1000).toLong())
                    .build(),
                )
              }
              val geometry =
                (
                  clip.geometry ?: VideoGeometry(
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                  )
                ).copy(width = plan.width.toDouble(), height = plan.height.toDouble())
              val effects = OverlayEngine.geometry(source, plan, geometry).toMutableList()
              val audio = mutableListOf<androidx.media3.common.audio.AudioProcessor>()
              if (plan.audio == AudioPolicy.STEREO &&
                source.audioTracks > 0
              ) {
                audio.add(
                  androidx.media3.common.audio.ChannelMixingAudioProcessor().apply {
                    putChannelMixingMatrix(
                      androidx.media3.common.audio.ChannelMixingMatrix
                        .createForConstantPower(source.audioChannels, 2),
                    )
                  },
                )
              }
              audio.add(
                EnvelopeAudioProcessor(
                  options.audioEdit?.volume ?: 1.0,
                  if (i >
                    0
                  ) {
                    transitions[i - 1]
                  } else {
                    0.0
                  },
                  transitions[i],
                  durations[i],
                  starts[i],
                  options.audioEdit?.fadeInMs ?: 0.0,
                  options.audioEdit?.fadeOutMs ?: 0.0,
                  total,
                ),
              )
              builder.addItem(
                EditedMediaItem
                  .Builder(
                    media.build(),
                  ).setSpeed(
                    AudioEngine.speed(clip.speed ?: 1.0),
                  ).setRemoveAudio(plan.audio == AudioPolicy.MUTE)
                  .setEffects(Effects(audio, effects))
                  .build(),
              )
              cursor = starts[i] + durations[i]
            }
            if (cursor < total - 0.001) builder.addGap(((total - cursor) * 1000).toLong())
            builder.build()
          }.toMutableList()
      options.audioEdit?.musicUri?.let { uri ->
        val file = job.resolve(uri)
        val musicInfo = AudioEngine.probe(file)
        val available = musicInfo.first
        val musicChannels = musicInfo.second.getInteger(android.media.MediaFormat.KEY_CHANNEL_COUNT)
        require(plan.audio == AudioPolicy.STEREO || audioSource == null || musicChannels == audioSource.audioChannels) {
          "Music and source channel layouts differ; select stereo explicitly."
        }
        val offset =
          options.audioEdit.musicStartMs ?: 0.0
        val trim = options.audioEdit.musicTrim ?: VideoSegment(0.0, minOf(available, total - offset))
        require(
          trim.startMs >= 0 && trim.endMs > trim.startMs && trim.endMs <= available,
        ) { "Invalid music trim range." }
        val end = minOf(trim.endMs, trim.startMs + total - offset)
        require(end > trim.startMs)
        val seq = EditedMediaItemSequence.Builder(setOf(C.TRACK_TYPE_AUDIO))
        if (offset > 0) seq.addGap((offset * 1000).toLong())
        seq.addItem(
          AudioEngine.item(
            file,
            VideoSegment(trim.startMs, end),
            processors =
              AudioEngine.stereo(musicChannels, plan.audio == AudioPolicy.STEREO) +
                listOf(
                  EnvelopeAudioProcessor(options.audioEdit.musicVolume ?: 1.0, 0.0, 0.0, end - trim.startMs),
                ),
          ),
        )
        sequences.add(seq.build())
      }
      val finalEffects = prepared.effects.toMutableList()
      // A composed timeline has one output clock, including transitions and primary-lane gaps.
      finalEffects.addAll(ConstantFrameRateEffect.create(plan.fps, (total * 1000).toLong()))
      val composition =
        Composition
          .Builder(sequences)
          .setEffects(Effects(emptyList(), finalEffects))
          .setHdrMode(
            if (plan.color ==
              ColorPolicy.SDR
            ) {
              Composition.HDR_MODE_TONE_MAP_HDR_TO_SDR_USING_OPEN_GL
            } else {
              Composition.HDR_MODE_KEEP_HDR
            },
          )
      if (hasTransitions) {
        composition.setVideoCompositorSettings(
          object : VideoCompositorSettings {
            override fun getOutputSize(inputSizes: MutableList<Size>): Size = Size(plan.width, plan.height)

            override fun getOverlaySettings(
              inputId: Int,
              presentationTimeUs: Long,
            ): OverlaySettings {
              if (inputId != 0) return StaticOverlaySettings.Builder().build()
              val ms = presentationTimeUs / 1000.0
              val active = clips.indices.filter { ms >= starts[it] && ms < starts[it] + durations[it] }
              val top = active.firstOrNull { it % 2 == 0 }
              var alpha = if (top == null) 0.0 else 1.0
              if (active.size == 2 && top != null) {
                val incoming = active.last()
                val t = ((ms - starts[incoming]) / transitions[incoming - 1]).coerceIn(0.0, 1.0)
                alpha = if (incoming == top) t else 1 - t
              }
              return StaticOverlaySettings.Builder().setAlphaScale(alpha.toFloat()).build()
            }
          },
        )
      }
      val output = job.output("mp4")
      val exported =
        kit.transform(
          emptyList(),
          output,
          videoKbps = plan.bitrateKbps,
          audioKbps = plan.audioBitrateKbps,
          control = job.control,
          plan = plan,
          compositionOverride = composition.build(),
        )
      val result = kit.outputResult(OperationType.EXPORTVIDEO, clips.first().uri, output)
      require(
        plan.color != ColorPolicy.PRESERVE || !aggregate.hdr || result.media?.hdr == true,
      ) { "Timeline failed to preserve HDR." }
      require(plan.color == ColorPolicy.SDR || aggregate.bitDepth <= (result.media?.bitDepth ?: 0.0)) {
        "Timeline reduced the source bit depth; choose SDR explicitly."
      }
      require(
        plan.audio == AudioPolicy.MUTE ||
          audioSource == null &&
          options.audioEdit?.musicUri == null ||
          (result.media?.audioTracks ?: 0.0) > 0,
      ) { "Timeline unexpectedly lost audio." }
      return result.copy(
        diagnostics =
          ExportDiagnostics(
            (SystemClock.elapsedRealtime() - job.control.started).toDouble(),
            output.length().toDouble(),
            job.control.peakMemoryBytes,
            VideoProcessingPath.ENCODED,
            if ((
                result.media?.audioTracks
                  ?: 0.0
              ) >
              0
            ) {
              AudioProcessingPath.ENCODED
            } else {
              AudioProcessingPath.ABSENT
            },
            exported.videoMimeType,
            plan.estimatedSizeBytes,
            exported.videoFrameCount.toDouble(),
          ),
      )
    } finally {
      prepared.close()
    }
  }
}
