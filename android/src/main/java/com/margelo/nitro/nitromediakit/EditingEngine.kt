package com.margelo.nitro.nitromediakit

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.effect.Presentation
import androidx.media3.exoplayer.SeekParameters
import androidx.media3.inspector.frame.FrameExtractor
import com.google.common.util.concurrent.MoreExecutors
import com.margelo.nitro.core.Promise
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.io.Closeable
import java.io.File
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.ceil
import kotlin.math.roundToInt

@android.annotation.SuppressLint("UnsafeOptInUsageError")
internal class EditingEngine(
  private val kit: NitroMediaKit,
  private val context: Context,
) {
  fun openMedia(
    uri: String,
    limits: ProcessingLimits?,
  ): Promise<HybridMediaAssetSpec> =
    Promise.async(NitroMediaKit.ioScope) {
      val control = PipelineControl(limits = limits)
      var owner: MediaJob? = null
      try {
        PipelineControl.register(control)
        owner = MediaJob(context, control)
        val file = owner.resolve(uri)
        val (type, info) = kit.inspect(file)
        val source =
          if (type ==
            MediaType.VIDEO
          ) {
            VideoSource(
              info.width!!.toInt(),
              info.height!!.toInt(),
              info.fps ?: 30.0,
              info.durationMs!!,
              info.codec!!,
              info.hdr ?: false,
              info.bitrateKbps,
              info.audioTracks!!.toInt(),
              info.audioChannels?.toInt() ?: 0,
              info.audioSampleRate?.toInt() ?: 0,
              info.bitDepth?.toInt() ?: 0,
            )
          } else {
            null
          }
        control.check()
        control.close()
        control.resourceCheck = null
        NativeMediaAsset(kit, uri, type, info, AssetStorage(file) { owner.finish(true) }, source)
      } catch (
        e: Throwable,
      ) {
        owner?.finish(false) ?: control.close()
        throw MediaErrors.normalize(e)
      } finally {
        PipelineControl.jobs.remove(control.id, control)
      }
    }

  fun plan(
    uri: String,
    options: ExportVideoOptions,
    source: VideoSource? = null,
  ): Promise<ExportPlan> =
    owned(options.limits) { job ->
      planLocal(job.resolve(uri), options, source).also { job.commitPaths(emptyList()) }
    }

  fun planLocal(
    file: File,
    options: ExportVideoOptions,
    source: VideoSource? = null,
  ): ExportPlan {
    val input = source ?: kit.videoSource(kit.detectVideoInfo(file.path))
    val effective =
      if (options.trim != null &&
        options.trimMode == TrimMode.KEYFRAME
      ) {
        options.copy(trim = StreamCopy.keyframeRange(file, options.trim, input.durationMs))
      } else {
        options
      }
    val p = ExportPlanner.plan(input, effective)
    DeviceCapabilities.check(p)
    val reasons = mutableListOf<String>()
    if (options.geometry != null) reasons.add("Geometry edits require video encoding.")
    if (!options.overlays.isNullOrEmpty() ||
      options.watermark != null ||
      options.subtitles != null
    ) {
      reasons.add("Overlays require video encoding.")
    }
    if (options.trim !=
      null
    ) {
      reasons.add(
        if (options.trimMode ==
          TrimMode.KEYFRAME
        ) {
          "Trim expands to surrounding keyframes."
        } else {
          "Accurate trim requires video encoding."
        },
      )
    }
    if ((options.speed ?: 1.0) != 1.0) reasons.add("Playback speed changes require encoding.")
    if (options.audioEdit != null) reasons.add("Audio edits require audio encoding.")
    if (p.width != input.width || p.height != input.height) reasons.add("Output dimensions differ from the source.")
    if (p.timing != FrameRatePolicy.PRESERVE) reasons.add("Frame-rate policy requires encoding.")
    if (p.mime != input.mime) reasons.add("Output codec differs from the source.")
    if (p.color == ColorPolicy.SDR) reasons.add("Explicit SDR conversion requires encoding.")
    if (options.copyPolicy == CopyPolicy.ENCODE) reasons.add("Encoding was explicitly requested.")
    if (options.video?.videoBitrateKbps != null ||
      options.video?.targetSizeBytes != null ||
      options.preset != null &&
      options.preset != CompressionPreset.PRESERVE
    ) {
      reasons.add("Compression settings require encoding.")
    }
    if (reasons.isEmpty()) {
      reasons.add(
        if (p.canCopy) {
          "Compatible video can be stream-copied."
        } else {
          "Requested codec, size, bitrate or timing requires encoding."
        },
      )
    }
    val warnings = mutableListOf<MediaInfoWarning>()
    if (input.bitDepth > 8 &&
      !input.hdr &&
      p.color != ColorPolicy.SDR &&
      !p.canCopy
    ) {
      throw PipelineFailure("UNSUPPORTED_EXPORT", "High-bit-depth SDR re-encoding requires explicit SDR conversion.")
    }
    if (options.video?.targetSizeBytes !=
      null
    ) {
      warnings.add(MediaInfoWarning("SIZE_ESTIMATE", "Target size is an estimate, not a hard output guarantee."))
    }
    options.audioEdit?.let {
      if (it.musicUri != null &&
        (it.volume ?: 1.0) + (it.musicVolume ?: 1.0) > 1.0
      ) {
        warnings.add(
          MediaInfoWarning("AUDIO_MIX_HEADROOM", "Combined gains exceed unity; lower levels to avoid clipping."),
        )
      }
    }
    return ExportPlan(
      p.width.toDouble(),
      p.height.toDouble(),
      p.fps,
      p.durationMs,
      if (p.mime ==
        "video/hevc"
      ) {
        VideoCodec.HEVC
      } else {
        VideoCodec.H264
      },
      p.color,
      if (p.canCopy) VideoProcessingPath.COPIED else VideoProcessingPath.ENCODED,
      p.estimatedSizeBytes,
      reasons.toTypedArray(),
      warnings.toTypedArray(),
    )
  }

  fun startExport(
    uri: String,
    options: ExportVideoOptions,
    onProgress: ((ExportProgress) -> Unit)?,
    source: VideoSource?,
    lease: Closeable?,
    validate: (() -> Unit)? = null,
  ): HybridExportJobSpec {
    val relay = ProgressRelay(onProgress)
    val control = PipelineControl(limits = options.limits, progress = relay::emit)
    try {
      PipelineControl.register(control)
    } catch (e: Throwable) {
      control.close()
      throw e
    }
    control.emit(ExportStage.QUEUED)
    return NativeExportJob(control, relay) {
      try {
        val legacy =
          kit
            .execute(OperationType.EXPORTVIDEO, uri, control = control) { job ->
              validate?.invoke()
              kit.exportResolved(job, job.resolve(uri), uri, options, OperationType.EXPORTVIDEO, source)
            }.await()
        typed(legacy)
      } finally {
        lease?.close()
      }
    }
  }

  fun startTimeline(
    clips: Array<TimelineClip>,
    options: ExportVideoOptions,
    onProgress: ((ExportProgress) -> Unit)?,
  ): HybridExportJobSpec {
    val relay = ProgressRelay(onProgress)
    val control = PipelineControl(limits = options.limits, progress = relay::emit)
    try {
      PipelineControl.register(control)
    } catch (e: Throwable) {
      control.close()
      throw e
    }
    control.emit(ExportStage.QUEUED)
    return NativeExportJob(control, relay) {
      typed(
        kit
          .execute(
            OperationType.EXPORTVIDEO,
            clips.firstOrNull()?.uri,
            control = control,
          ) { job -> TimelineEngine.export(kit, context, job, clips, options) }
          .await(),
      )
    }
  }

  private fun typed(result: MediaInfoResult): VideoExportResult {
    if (!result.ok) {
      throw PipelineFailure(
        result.error?.code ?: "EXPORT_FAILED",
        result.error?.message ?: "Export failed.",
      )
    }
    return VideoExportResult(
      requireNotNull(result.outputUri),
      requireNotNull(result.media),
      result.warnings ?: emptyArray(),
      result.diagnostics,
      result.actualTrim,
    )
  }

  fun thumbnails(
    uri: String,
    options: ThumbnailBatchOptions,
    cachedSource: VideoSource? = null,
  ): Promise<ThumbnailBatchResult> =
    owned(null) { job ->
      require(options.timesMs.isNotEmpty() && options.timesMs.size <= 256) { "Request 1–256 thumbnails." }
      val file = job.resolve(uri)
      val info = cachedSource ?: kit.videoSource(kit.detectVideoInfo(file.path))
      require(
        options.timesMs.all {
          it.isFinite() && it >= 0 && it < info.durationMs
        },
      ) { "Thumbnail timestamps must fit the video." }
      val edge = options.maxLongEdge ?: 320.0
      ExportPlanner.finite(edge, 2.0, 4096.0, "maxLongEdge")
      val quality = options.quality ?: 0.9
      ExportPlanner.finite(quality, 0.0, 1.0, "quality")
      val scale = minOf(1.0, edge / maxOf(info.width, info.height))
      val w = maxOf(1, (info.width * scale).roundToInt())
      val h =
        maxOf(
          1,
          (
            info.height *
              scale
          ).roundToInt(),
        )
      val columns =
        options.storyboardColumns
          ?.also {
            require(
              it.isFinite() && it % 1 == 0.0 && it >= 1 && it <= 32,
            ) { "storyboardColumns must be an integer in [1,32]." }
          }?.toInt()
      val rows = columns?.let { ceil(options.timesMs.size.toDouble() / it).toInt() }
      if (columns !=
        null
      ) {
        require(
          w.toLong() * columns * h *
            requireNotNull(
              rows,
            ) <= minOf(16_777_216L, Runtime.getRuntime().maxMemory() / 32) &&
            w * columns <= 8192 &&
            h * rows <= 8192,
        ) { "Storyboard exceeds the image memory budget; reduce cell size." }
      }
      NitroMediaKit.imagePermits.withPermit {
        withContext(NitroMediaKit.frameDispatcher) {
          val sprite = columns?.let { Bitmap.createBitmap(w * it, h * requireNotNull(rows), Bitmap.Config.ARGB_8888) }
          val canvas = sprite?.let { Canvas(it) }
          canvas?.drawColor(android.graphics.Color.BLACK)
          val extractor =
            FrameExtractor
              .Builder(context, MediaItem.fromUri(Uri.fromFile(file)))
              .setSeekParameters(
                if (options.precision ==
                  ThumbnailPrecision.FAST
                ) {
                  SeekParameters.CLOSEST_SYNC
                } else {
                  SeekParameters.EXACT
                },
              ).setEffects(listOf(Presentation.createForWidthAndHeight(w, h, Presentation.LAYOUT_SCALE_TO_FIT)))
              .build()
          try {
            val results = arrayOfNulls<ThumbnailResult>(options.timesMs.size)
            val cells = arrayOfNulls<StoryboardCell>(options.timesMs.size)
            // Decode in temporal order while preserving the caller's result order.
            options.timesMs.indices.sortedBy { options.timesMs[it] }.forEach { index ->
              job.control.check()
              val requested = options.timesMs[index]
              val future = extractor.getFrame(requested.toLong())
              val hook = job.control.onCancel { future.cancel(true) }
              val frame =
                try {
                  suspendCancellableCoroutine<FrameExtractor.Frame> { c ->
                    future.addListener({
                      runCatching {
                        future.get()
                      }.onSuccess {
                        if (c.isActive) {
                          c.resume(
                            it,
                          )
                        }
                      }.onFailure { if (c.isActive) c.resumeWithException(it) }
                    }, MoreExecutors.directExecutor())
                    c.invokeOnCancellation { future.cancel(true) }
                  }
                } finally {
                  hook.close()
                }
              // Media3 may return this bitmap again for a deduplicated seek; it owns recycling.
              run {
                val output = job.output("jpg")
                output.outputStream().use {
                  check(
                    frame.bitmap.compress(
                      Bitmap.CompressFormat.JPEG,
                      (
                        quality *
                          100
                      ).roundToInt(),
                      it,
                    ),
                  )
                }
                results[index] =
                  ThumbnailResult(
                    output.path,
                    requested,
                    frame.presentationTimeMs.toDouble(),
                    w.toDouble(),
                    h.toDouble(),
                  )
                if (columns != null) {
                  val x = index % columns * w
                  val y = index / columns * h
                  canvas!!.drawBitmap(
                    frame.bitmap,
                    null,
                    android.graphics.Rect(x, y, x + w, y + h),
                    Paint(Paint.FILTER_BITMAP_FLAG),
                  )
                  cells[index] =
                    StoryboardCell(
                      index.toDouble(),
                      x.toDouble(),
                      y.toDouble(),
                      w.toDouble(),
                      h.toDouble(),
                      frame.presentationTimeMs.toDouble(),
                    )
                }
              }
              job.control.emit(ExportStage.PROCESSING, (results.count { it != null }).toDouble() / results.size)
            }
            val board =
              sprite?.let { bitmap ->
                val output = job.output("jpg")
                output.outputStream().use {
                  check(
                    bitmap.compress(
                      Bitmap.CompressFormat.JPEG,
                      (
                        quality *
                          100
                      ).roundToInt(),
                      it,
                    ),
                  )
                }
                StoryboardResult(
                  output.path,
                  bitmap.width.toDouble(),
                  bitmap.height.toDouble(),
                  cells.map { requireNotNull(it) }.toTypedArray(),
                )
              }
            val frames = results.map { requireNotNull(it) }
            val mapping = job.commitPaths(frames.map { it.outputUri } + listOfNotNull(board?.outputUri))
            ThumbnailBatchResult(
              frames
                .map {
                  it.copy(outputUri = mapping.getValue(it.outputUri))
                }.toTypedArray(),
              board?.copy(outputUri = mapping.getValue(board.outputUri)),
            )
          } finally {
            extractor.close()
            sprite?.recycle()
          }
        }
      }
    }

  fun audio(
    uri: String,
    options: ExtractAudioOptions,
  ): Promise<AudioExportResult> =
    owned(null) { job -> AudioEngine.extract(kit, context, job, job.resolve(uri), options) }

  fun analyzeAudio(
    uri: String,
    options: AudioAnalysisOptions,
  ): Promise<LoudnessAnalysis> =
    owned(options.limits) { job ->
      LoudnessEngine.analyze(job.resolve(uri), options, job.control).also { job.commitPaths(emptyList()) }
    }

  fun normalizeAudio(
    uri: String,
    options: NormalizeAudioOptions,
  ): Promise<NormalizedAudioResult> =
    owned(options.limits) { job -> LoudnessEngine.normalize(kit, job, job.resolve(uri), options) }

  internal fun <T> owned(
    limits: ProcessingLimits?,
    body: suspend (MediaJob) -> T,
  ): Promise<T> =
    Promise.async(NitroMediaKit.ioScope) {
      val control = PipelineControl(limits = limits)
      var job: MediaJob? = null
      var success = false
      val coroutine = kotlinx.coroutines.currentCoroutineContext()[kotlinx.coroutines.Job]!!
      val hook = control.onCancel { coroutine.cancel() }
      try {
        PipelineControl.register(control)
        job = MediaJob(context, control)
        body(job).also { success = true }
      } catch (
        e: Throwable,
      ) {
        runCatching { control.check() }.exceptionOrNull()?.let { throw MediaErrors.normalize(it) }
        throw MediaErrors.normalize(e)
      } finally {
        hook.close()
        job?.finish(success) ?: control.close()
        PipelineControl.jobs.remove(control.id, control)
      }
    }
}
