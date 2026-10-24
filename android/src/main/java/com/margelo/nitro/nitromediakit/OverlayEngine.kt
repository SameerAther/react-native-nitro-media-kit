package com.margelo.nitro.nitromediakit

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.text.SpannableString
import androidx.media3.common.Effect
import androidx.media3.common.OverlaySettings
import androidx.media3.effect.Crop
import androidx.media3.effect.OverlayEffect
import androidx.media3.effect.Presentation
import androidx.media3.effect.ScaleAndRotateTransformation
import androidx.media3.effect.StaticOverlaySettings
import androidx.media3.effect.TextOverlay
import androidx.media3.effect.TextureOverlay
import java.io.Closeable

@android.annotation.SuppressLint("UnsafeOptInUsageError")
internal object OverlayEngine {
  /** Media3's TextOverlay shader path converts SDR raster colors to linear BT.2020 on HDR input.
   * Override its rasterization to reuse prepared text/logo bitmaps, including on API 24–33.
   * Plain BitmapOverlay uses the Ultra HDR gain-map path and rejects these SDR rasters.
   */
  private abstract class SdrRasterOverlay : TextOverlay() {
    final override fun getText(presentationTimeUs: Long): SpannableString = SpannableString("")
  }

  fun geometry(
    source: VideoSource,
    plan: VideoPlan,
    options: VideoGeometry?,
  ): List<Effect> {
    val g = GeometryPlan.make(source.width, source.height, options)
    val effects = mutableListOf<Effect>()
    if (options?.crop !=
      null
    ) {
      effects.add(
        Crop(
          (2 * g.x / source.width - 1).toFloat(),
          (2 * (g.x + g.cropWidth) / source.width - 1).toFloat(),
          (
            1 -
              2 * (g.y + g.cropHeight) / source.height
          ).toFloat(),
          (1 - 2 * g.y / source.height).toFloat(),
        ),
      )
    }
    if (g.rotation !=
      0
    ) {
      effects.add(ScaleAndRotateTransformation.Builder().setRotationDegrees(-g.rotation.toFloat()).build())
    }
    if (g.flipX ||
      g.flipY
    ) {
      effects.add(
        ScaleAndRotateTransformation.Builder().setScale(if (g.flipX) -1f else 1f, if (g.flipY) -1f else 1f).build(),
      )
    }
    if (options != null ||
      plan.width != source.width ||
      plan.height != source.height
    ) {
      effects.add(
        Presentation.createForWidthAndHeight(
          plan.width,
          plan.height,
          if (g.cover) Presentation.LAYOUT_SCALE_TO_FIT_WITH_CROP else Presentation.LAYOUT_SCALE_TO_FIT,
        ),
      )
    }
    return effects
  }

  class Prepared(
    val effects: List<Effect>,
    private val cleanup: () -> Unit,
  ) : Closeable {
    override fun close() = cleanup()
  }

  suspend fun prepare(
    kit: NitroMediaKit,
    job: MediaJob,
    options: ExportVideoOptions,
    plan: VideoPlan,
    includeLegacyWatermark: Boolean = false,
  ): Prepared {
    val bitmaps = mutableListOf<Bitmap>()
    val dynamic = mutableListOf<SubtitleOverlay>()
    try {
      val overlays = mutableListOf<TextureOverlay>()
      if (includeLegacyWatermark && options.watermark != null) {
        val opt = options.watermarkOptions
        val text = options.watermark
        require(text.isNotBlank() && text.length <= 4096) { "Watermark must contain 1–4096 characters." }
        val font = opt?.fontSize ?: minOf(plan.width, plan.height) * 0.05
        val margin = opt?.margin ?: minOf(plan.width, plan.height) * 0.025
        val opacity = opt?.opacity ?: 1.0
        ExportPlanner.finite(font, 1.0, 512.0, "fontSize")
        ExportPlanner.finite(opacity, 0.0, 1.0, "opacity")
        require(
          margin.isFinite() && margin >= 0 && margin * 2 < minOf(plan.width, plan.height),
        ) { "Invalid watermark margin." }
        val bitmap =
          kit.textBitmap(
            text,
            font.toFloat(),
            kit.parseColor(opt?.color ?: "#FFFFFFFF"),
            (plan.width - 2 * margin).toInt(),
            (
              plan.height -
                2 * margin
            ).toInt(),
          )
        bitmaps.add(bitmap)
        val position = opt?.position ?: WatermarkPosition.BOTTOM_RIGHT
        val x =
          when (position) {
            WatermarkPosition.TOP_LEFT, WatermarkPosition.BOTTOM_LEFT -> margin
            WatermarkPosition.CENTER ->
              (
                plan.width -
                  bitmap.width
              ) /
                2.0
            ; else -> plan.width - margin - bitmap.width
          }
        val y =
          when (position) {
            WatermarkPosition.TOP_LEFT, WatermarkPosition.TOP_RIGHT -> margin
            WatermarkPosition.CENTER ->
              (
                plan.height -
                  bitmap.height
              ) /
                2.0
            ; else -> plan.height - margin - bitmap.height
          }
        val settings =
          StaticOverlaySettings
            .Builder()
            .setAlphaScale(opacity.toFloat())
            .setOverlayFrameAnchor(-1f, 1f)
            .setBackgroundFrameAnchor(
              (
                2 *
                  x /
                  plan.width -
                  1
              ).toFloat(),
              (1 - 2 * y / plan.height).toFloat(),
            ).build()
        overlays.add(
          object : SdrRasterOverlay() {
            override fun getBitmap(presentationTimeUs: Long) = bitmap

            override fun getOverlaySettings(presentationTimeUs: Long): OverlaySettings = settings
          },
        )
      }
      var pixels = bitmaps.sumOf { it.width.toLong() * it.height }

      for (o in options.overlays ?: emptyArray()) {
        require(o.x + o.width <= plan.width && o.y + o.height <= plan.height) { "Overlay must fit the output canvas." }
        pixels += kotlin.math.ceil(o.width).toLong() * kotlin.math.ceil(o.height).toLong()
        require(
          pixels <= minOf(16_777_216L, Runtime.getRuntime().maxMemory() / 32),
        ) { "Combined overlays exceed the image memory budget." }
        val bitmap =
          if (o.kind == OverlayKind.TEXT) {
            val font = o.fontSize ?: minOf(512.0, maxOf(1.0, minOf(o.width, o.height) * 0.4))
            ExportPlanner.finite(font, 1.0, 512.0, "fontSize")
            kit.textBitmap(
              requireNotNull(o.text),
              font.toFloat(),
              kit.parseColor(o.color ?: "#FFFFFFFF"),
              o.width.toInt(),
              o.height.toInt(),
            )
          } else {
            val file = job.resolve(requireNotNull(o.uri))
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.path, bounds)
            require(bounds.outWidth > 0 && bounds.outHeight > 0) { "Cannot decode overlay image." }
            val decode =
              BitmapFactory.Options().apply {
                inSampleSize =
                  ExportPlanner.sampleSize(bounds.outWidth, bounds.outHeight, maxOf(o.width, o.height).toInt())
                ; inPreferredConfig =
                  Bitmap.Config.ARGB_8888
              }
            val decodedPixels =
              (bounds.outWidth / decode.inSampleSize).toLong() * (bounds.outHeight / decode.inSampleSize)
            require(
              decodedPixels <= minOf(16_777_216L, Runtime.getRuntime().maxMemory() / 32),
            ) { "Decoded overlay exceeds the memory budget." }
            val raw = requireNotNull(BitmapFactory.decodeFile(file.path, decode)) { "Cannot decode overlay image." }
            val oriented = kit.applyExifOrientation(raw, kit.readImageExifOrientation(file.path))
            if (oriented !== raw) raw.recycle()
            val target =
              Bitmap.createBitmap(
                o.width.toInt().coerceAtLeast(1),
                o.height.toInt().coerceAtLeast(1),
                Bitmap.Config.ARGB_8888,
              )
            val scale = minOf(o.width / oriented.width, o.height / oriented.height)
            val w = oriented.width * scale
            val h = oriented.height * scale
            android.graphics.Canvas(target).drawBitmap(
              oriented,
              null,
              android.graphics.RectF(
                ((o.width - w) / 2).toFloat(),
                (
                  (o.height - h) /
                    2
                ).toFloat(),
                ((o.width + w) / 2).toFloat(),
                ((o.height + h) / 2).toFloat(),
              ),
              android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG),
            )
            oriented.recycle()
            target
          }
        bitmaps.add(bitmap)
        overlays.add(
          object : SdrRasterOverlay() {
            override fun getBitmap(presentationTimeUs: Long) = bitmap

            override fun getOverlaySettings(presentationTimeUs: Long): OverlaySettings {
              val ms = presentationTimeUs / 1000.0
              val start = o.startMs ?: 0.0
              val end = o.endMs ?: plan.durationMs
              val alpha =
                if (ms < start ||
                  ms >= end
                ) {
                  0.0
                } else {
                  (o.opacity ?: 1.0) *
                    minOf(
                      1.0,
                      if ((o.fadeInMs ?: 0.0) >
                        0
                      ) {
                        (ms - start) / o.fadeInMs!!
                      } else {
                        1.0
                      },
                      if ((o.fadeOutMs ?: 0.0) > 0) (end - ms) / o.fadeOutMs!! else 1.0,
                    )
                }
              return StaticOverlaySettings
                .Builder()
                .setAlphaScale(alpha.toFloat())
                .setOverlayFrameAnchor(-1f, 1f)
                .setBackgroundFrameAnchor((2 * o.x / plan.width - 1).toFloat(), (1 - 2 * o.y / plan.height).toFloat())
                .build()
            }
          },
        )
      }
      options.subtitles?.let { subtitles ->
        val overlay = SubtitleOverlay(kit, subtitles, plan)
        dynamic.add(overlay)
        overlays.add(overlay)
      }
      // Seven rasters plus the video fit even GLES2's minimum eight fragment samplers.
      return Prepared(overlays.chunked(7).map { OverlayEffect(it) }) {
        dynamic.forEach { it.close() }
        bitmaps.forEach { if (!it.isRecycled) it.recycle() }
      }
    } catch (e: Throwable) {
      dynamic.forEach { it.close() }
      bitmaps.forEach { it.recycle() }
      throw e
    }
  }

  private class SubtitleOverlay(
    private val kit: NitroMediaKit,
    private val options: SubtitleOptions,
    private val plan: VideoPlan,
  ) : SdrRasterOverlay(),
    Closeable {
    private val cues = EditingValidation.cues(options, plan.durationMs)
    private var key: List<Int> = emptyList()
    private var bitmap = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
    private val margin = options.margin ?: minOf(plan.width, plan.height) * 0.05
    private val font = options.fontSize ?: minOf(plan.width, plan.height) * 0.045

    init {
      require(
        margin.isFinite() && margin >= 0 && margin * 2 < minOf(plan.width, plan.height),
      ) { "Invalid subtitle margin." }
      ExportPlanner.finite(font, 1.0, 512.0, "fontSize")
      kit.parseColor(options.color ?: "#FFFFFFFF")
    }

    override fun getBitmap(presentationTimeUs: Long): Bitmap {
      val time = presentationTimeUs / 1000.0
      val active = cues.indices.filter { time >= cues[it].startMs && time < cues[it].endMs }
      if (active != key) {
        val replacement =
          if (active.isEmpty()) {
            Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
          } else {
            kit.textBitmap(
              active.joinToString("\n") { cues[it].text },
              font.toFloat(),
              kit.parseColor(options.color ?: "#FFFFFFFF"),
              (plan.width - 2 * margin).toInt(),
              (plan.height - 2 * margin).toInt().coerceAtMost(plan.height / 2),
            )
          }
        bitmap.recycle()
        bitmap = replacement
        key = active
      }
      return bitmap
    }

    override fun getOverlaySettings(presentationTimeUs: Long): OverlaySettings =
      StaticOverlaySettings
        .Builder()
        .setAlphaScale(
          if (cues.none {
              presentationTimeUs /
                1000.0 >=
                it.startMs &&
                presentationTimeUs / 1000.0 < it.endMs
            }
          ) {
            0f
          } else {
            1f
          },
        ).setOverlayFrameAnchor(0f, -1f)
        .setBackgroundFrameAnchor(
          0f,
          (
            -1 +
              2 * margin / plan.height
          ).toFloat(),
        ).build()

    override fun close() {
      if (!bitmap.isRecycled) bitmap.recycle()
    }
  }
}
