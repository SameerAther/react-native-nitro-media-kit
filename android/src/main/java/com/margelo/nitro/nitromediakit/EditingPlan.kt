package com.margelo.nitro.nitromediakit

internal data class GeometryPlan(
  val x: Double,
  val y: Double,
  val cropWidth: Double,
  val cropHeight: Double,
  val rotation: Int,
  val flipX: Boolean,
  val flipY: Boolean,
  val cover: Boolean,
  val width: Double,
  val height: Double,
) {
  companion object {
    fun make(
      width: Int,
      height: Int,
      options: VideoGeometry?,
    ): GeometryPlan {
      val crop = options?.crop
      val x =
        crop?.x ?: 0.0
      val y = crop?.y ?: 0.0
      val w = crop?.width ?: width.toDouble()
      val h = crop?.height ?: height.toDouble()
      require(
        listOf(x, y, w, h).all {
          it.isFinite()
        } &&
          x >= 0 &&
          y >= 0 &&
          w >= 2 &&
          h >= 2 &&
          x + w <= width &&
          y + h <= height,
      ) { "Crop must fit the oriented source bounds." }
      val rotation = options?.rotation ?: 0.0
      require(rotation in listOf(0.0, 90.0, 180.0, 270.0)) { "rotation must be 0, 90, 180 or 270 clockwise degrees." }
      val swap = rotation == 90.0 || rotation == 270.0
      val outputW = options?.width ?: if (swap) h else w
      val outputH = options?.height ?: if (swap) w else h
      require(
        (options?.width == null) == (options?.height == null),
      ) { "Canvas width and height must be specified together." }
      if (options?.width !=
        null
      ) {
        require(
          listOf(outputW, outputH).all {
            it.isFinite() && it in 2.0..8192.0 && it % 2 == 0.0
          },
        ) { "Canvas dimensions must be even integers in [2, 8192]." }
      }
      return GeometryPlan(
        x,
        y,
        w,
        h,
        rotation.toInt(),
        options?.flipHorizontal ?: false,
        options?.flipVertical ?: false,
        options?.fit == GeometryFit.COVER,
        outputW,
        outputH,
      )
    }
  }
}

internal data class SubtitleCue(
  val startMs: Double,
  val endMs: Double,
  val text: String,
)

internal object EditingValidation {
  fun validate(
    options: ExportVideoOptions,
    duration: Double,
    width: Int,
    height: Int,
  ) {
    ExportPlanner.finite(options.speed ?: 1.0, 0.25, 4.0, "speed")
    require((options.overlays?.size ?: 0) <= 32) { "At most 32 overlays are supported." }
    val edge = minOf(width, height).toDouble()

    fun color(value: String?) {
      require(value == null || value.matches(Regex("^#[0-9a-fA-F]{6}([0-9a-fA-F]{2})?$"))) {
        "color must be #RRGGBB or #RRGGBBAA."
      }
    }
    options.watermark?.let { text ->
      require(text.isNotBlank() && text.length <= 4096) { "Watermark must contain 1–4096 characters." }
      val margin = options.watermarkOptions?.margin ?: edge * 0.025
      require(margin.isFinite() && margin >= 0 && margin * 2 < edge) { "Invalid watermark margin." }
      ExportPlanner.finite(options.watermarkOptions?.fontSize ?: edge * 0.05, 1.0, 512.0, "fontSize")
      ExportPlanner.finite(options.watermarkOptions?.opacity ?: 1.0, 0.0, 1.0, "opacity")
      color(options.watermarkOptions?.color)
    }
    options.overlays?.forEach { o ->
      require(
        listOf(o.x, o.y, o.width, o.height).all { it.isFinite() } &&
          o.x >= 0 &&
          o.y >= 0 &&
          o.width in 1.0..8192.0 &&
          o.height in 1.0..8192.0,
      ) { "Invalid overlay bounds." }
      require(o.x + o.width <= width && o.y + o.height <= height) { "Overlay must fit the output canvas." }
      if (o.kind ==
        OverlayKind.TEXT
      ) {
        ExportPlanner.finite(
          o.fontSize ?: minOf(512.0, maxOf(1.0, minOf(o.width, o.height) * 0.4)),
          1.0,
          512.0,
          "fontSize",
        )
        color(o.color)
      }
      val start = o.startMs ?: 0.0
      val end = o.endMs ?: duration
      require(start.isFinite() && end.isFinite() && start >= 0 && end > start && end <= duration + 0.001) {
        "Overlay range must fit the output timeline."
      }
      ExportPlanner.finite(o.fadeInMs ?: 0.0, 0.0, end - start, "fadeInMs")
      ExportPlanner.finite(o.fadeOutMs ?: 0.0, 0.0, end - start, "fadeOutMs")
      require((o.fadeInMs ?: 0.0) + (o.fadeOutMs ?: 0.0) <= end - start) { "Overlay fades overlap." }
      ExportPlanner.finite(o.opacity ?: 1.0, 0.0, 1.0, "opacity")
      if (o.kind == OverlayKind.IMAGE) {
        require(!o.uri.isNullOrEmpty() && o.text == null) { "Image overlays require uri and no text." }
      } else {
        require(
          !o.text.isNullOrEmpty() && o.text.length <= 4096 && o.uri == null,
        ) { "Text overlays require 1–4096 characters and no uri." }
      }
    }
    options.audioEdit?.let { a ->
      ExportPlanner.finite(a.volume ?: 1.0, 0.0, 1.0, "volume")
      ExportPlanner.finite(a.musicVolume ?: 1.0, 0.0, 1.0, "musicVolume")
      for (value in listOf(
        a.fadeInMs ?: 0.0,
        a.fadeOutMs ?: 0.0,
        a.musicStartMs ?: 0.0,
      )) {
        ExportPlanner.finite(value, 0.0, duration, "audio timing")
      }
      require((a.fadeInMs ?: 0.0) + (a.fadeOutMs ?: 0.0) <= duration) { "Audio fades overlap." }
      a.trackIndex?.let {
        require(
          it.isFinite() && it >= 0 && it % 1 == 0.0,
        ) { "trackIndex must be a nonnegative integer." }
      }
      require(options.audio != AudioPolicy.MUTE && options.video?.keepAudio != false) {
        "audioEdit conflicts with muted audio."
      }
    }
    options.subtitles?.let {
      val margin = it.margin ?: edge * 0.05
      require(margin.isFinite() && margin >= 0 && margin * 2 < edge) { "Invalid subtitle margin." }
      ExportPlanner.finite(it.fontSize ?: edge * 0.045, 1.0, 512.0, "fontSize")
      color(it.color)
      cues(it, duration)
    }
    require(
      options.copyPolicy != CopyPolicy.REQUIRE_COPY || options.trim == null || options.trimMode == TrimMode.KEYFRAME,
    ) {
      "Stream-copy trimming requires keyframe mode."
    }
  }

  fun cues(
    options: SubtitleOptions,
    duration: Double,
  ): List<SubtitleCue> {
    require(options.content.toByteArray(Charsets.UTF_8).size <= 1_048_576) { "Subtitles exceed 1 MiB." }
    val text =
      options.content
        .removePrefix("\uFEFF")
        .replace("\r\n", "\n")
        .replace("\r", "\n")
        .trim()
    require(options.format != SubtitleFormat.VTT || text.startsWith("WEBVTT")) { "Missing WEBVTT header." }
    val result = mutableListOf<SubtitleCue>()
    text.split(Regex("\n[ \t]*\n")).forEach { block ->
      if (block.trimStart().let {
          it.startsWith(
            "NOTE",
          ) ||
            it.startsWith("STYLE") ||
            it.startsWith("REGION")
        }
      ) {
        return@forEach
      }
      val lines = block.lines()
      val index = lines.indexOfFirst { it.contains("-->") }
      if (index < 0) return@forEach
      val parts = lines[index].split("-->")
      require(parts.size == 2) { "Invalid subtitle timing." }
      val start = timestamp(parts[0].trim())
      val end = timestamp(parts[1].trim().split(Regex("\\s+"))[0])
      require(start >= 0 && end > start) { "Invalid subtitle range." }
      val body = plainText(lines.drop(index + 1).joinToString("\n"))
      require(body.isNotEmpty() && body.length <= 4096) { "Invalid subtitle text." }
      if (start < duration) result.add(SubtitleCue(start, minOf(end, duration), body))
      require(result.size <= 2000) { "At most 2000 subtitle cues are supported." }
    }
    require(result.isNotEmpty()) { "No subtitle cues intersect the output timeline." }
    result.sortBy { it.startMs }
    val ends = mutableListOf<Double>()
    result.forEach { cue ->
      ends.removeAll { it <= cue.startMs }
      ends.add(cue.endMs)
      require(ends.size <= 4) { "At most four simultaneous subtitle cues are supported." }
    }
    return result
  }

  private fun plainText(value: String): String {
    var inTag = false
    val result =
      buildString {
        for (char in value) {
          if (char ==
            '<'
          ) {
            inTag = true
          } else if (char == '>' && inTag) {
            inTag = false
          } else if (!inTag) {
            append(char)
          }
        }
      }
    return result
      .replace("&amp;", "&")
      .replace("&lt;", "<")
      .replace("&gt;", ">")
      .replace("&quot;", "\"")
      .replace("&nbsp;", " ")
  }

  private fun timestamp(text: String): Double {
    require(text.matches(Regex("^(?:[0-9]{2,}:)?[0-5][0-9]:[0-5][0-9][.,][0-9]{3}$"))) { "Invalid subtitle timestamp." }
    val p = text.replace(',', '.').split(':')
    require(p.size in 2..3) { "Invalid subtitle timestamp." }
    val seconds =
      p.last().toDoubleOrNull() ?: Double.NaN
    val minutes =
      p[p.size - 2].toDoubleOrNull() ?: Double.NaN
    val hours = if (p.size == 3) p[0].toDoubleOrNull() ?: Double.NaN else 0.0
    require(
      seconds.isFinite() &&
        minutes.isFinite() &&
        hours.isFinite() &&
        seconds >= 0 &&
        seconds < 60 &&
        minutes >= 0 &&
        minutes < 60 &&
        hours >= 0,
    ) { "Invalid subtitle timestamp." }
    return (hours * 3600 + minutes * 60 + seconds) * 1000
  }
}

internal data class TimelineLayout(
  val starts: List<Double>,
  val durationMs: Double,
) {
  companion object {
    fun make(
      durations: List<Double>,
      transitions: List<Double>,
    ): TimelineLayout {
      require(durations.isNotEmpty() && durations.size <= 64 && transitions.size == durations.size) {
        "A timeline requires 1–64 clips."
      }
      require(durations.all { it.isFinite() && it > 0 }) { "Invalid clip duration." }
      for (i in durations.indices) {
        val t = transitions[i]
        require(
          t.isFinite() &&
            t >= 0 &&
            t <= durations[i] &&
            (
              if (i <
                durations.lastIndex
              ) {
                t <= durations[i + 1]
              } else {
                t == 0.0
              }
            ),
        ) { "Invalid dissolve duration." }
        require(
          i == 0 || transitions[i - 1] + t <= durations[i],
        ) { "Transitions cannot create three overlapping clips." }
      }
      val starts = mutableListOf(0.0)
      for (i in 1 until durations.size) starts.add(starts[i - 1] + durations[i - 1] - transitions[i - 1])
      return TimelineLayout(starts, starts.last() + durations.last())
    }
  }
}
