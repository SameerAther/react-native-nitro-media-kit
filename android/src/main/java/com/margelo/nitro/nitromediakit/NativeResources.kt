package com.margelo.nitro.nitromediakit

import com.facebook.proguard.annotations.DoNotStrip
import com.margelo.nitro.core.Promise
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import java.io.Closeable

internal class ProgressRelay(
  listener: ((ExportProgress) -> Unit)?,
) {
  private var listener = listener
  private var finished = false

  @Synchronized fun set(value: ((ExportProgress) -> Unit)?) {
    if (!finished) listener = value
  }

  @Synchronized fun finish() {
    finished = true
    listener = null
  }

  fun emit(value: ExportProgress) {
    val callback = synchronized(this) { listener }
    runCatching { callback?.invoke(value) }
  }
}

@DoNotStrip
internal class NativeExportJob(
  private val control: PipelineControl,
  private val relay: ProgressRelay,
  work: suspend () -> VideoExportResult,
) : HybridExportJobSpec() {
  override val id: String = control.id

  @Volatile private var currentState = JobState.QUEUED
  override val state: JobState get() = currentState
  private val completion =
    Promise.async(CoroutineScope(Dispatchers.IO)) {
      try {
        currentState = JobState.RUNNING
        work().also { currentState = JobState.COMPLETED }
      } catch (e: Throwable) {
        val failure = MediaErrors.normalize(e)
        currentState = if (failure.code == "CANCELLED") JobState.CANCELLED else JobState.FAILED
        throw failure
      } finally {
        relay.finish()
      }
    }

  override fun result(): Promise<VideoExportResult> = completion

  override fun cancel(): Boolean =
    (currentState == JobState.QUEUED || currentState == JobState.RUNNING) && control.cancel()

  override fun setProgressListener(listener: ((ExportProgress) -> Unit)?) {
    if (currentState == JobState.QUEUED || currentState == JobState.RUNNING) relay.set(listener)
  }

  override fun dispose() {
    relay.finish()
    control.cancel()
    super.dispose()
  }
}

@DoNotStrip
internal class NativeMediaAsset(
  private val kit: NitroMediaKit,
  override val uri: String,
  override val type: MediaType,
  override val info: MediaInfoMedia,
  private val storage: AssetStorage,
  private val source: VideoSource?,
) : HybridMediaAssetSpec() {
  private val cleanup = AssetReaper.watch(this, storage)
  override val memorySize: Long get() = 512 // Metadata only; staged bytes are on disk, not native heap.

  override fun planExport(options: ExportVideoOptions): Promise<ExportPlan> {
    val lease = storage.borrow()
    return Promise.async {
      try {
        storage.validate()
        kit.planCached(storage.file.path, options, source).await()
      } finally {
        lease.close()
      }
    }
  }

  override fun createExport(
    options: ExportVideoOptions,
    onProgress: ((ExportProgress) -> Unit)?,
  ): HybridExportJobSpec {
    val lease = storage.borrow()
    return try {
      kit.startExport(storage.file.path, options, onProgress, source, Closeable { lease.close() }, storage::validate)
    } catch (
      e: Throwable,
    ) {
      lease.close()
      throw e
    }
  }

  override fun extractThumbnails(options: ThumbnailBatchOptions): Promise<ThumbnailBatchResult> {
    val lease = storage.borrow()
    return Promise.async {
      try {
        storage.validate()
        kit.thumbnailBatch(storage.file.path, options, source).await()
      } finally {
        lease.close()
      }
    }
  }

  override fun extractAudio(options: ExtractAudioOptions): Promise<AudioExportResult> {
    val lease = storage.borrow()
    return Promise.async {
      try {
        storage.validate()
        kit.extractAudio(storage.file.path, options).await()
      } finally {
        lease.close()
      }
    }
  }

  override fun analyzeAudio(options: AudioAnalysisOptions): Promise<LoudnessAnalysis> {
    val lease = storage.borrow()
    return Promise.async {
      try {
        storage.validate()
        kit.analyzeAudio(storage.file.path, options).await()
      } finally {
        lease.close()
      }
    }
  }

  override fun normalizeAudio(options: NormalizeAudioOptions): Promise<NormalizedAudioResult> {
    val lease = storage.borrow()
    return Promise.async {
      try {
        storage.validate()
        kit.normalizeAudio(storage.file.path, options).await()
      } finally {
        lease.close()
      }
    }
  }

  override fun dispose() {
    cleanup.close()
    super.dispose()
  }
}
