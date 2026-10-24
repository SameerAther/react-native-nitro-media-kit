package com.margelo.nitro.nitromediakit

import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.os.Build

internal object DeviceCapabilities {
  fun get(): MediaCapabilities =
    MediaCapabilities(
      "android",
      arrayOf(codec(VideoCodec.H264, "video/avc"), codec(VideoCodec.HEVC, "video/hevc")),
      true,
    )

  private fun codec(
    codec: VideoCodec,
    mime: String,
  ): CodecCapability {
    val matches =
      MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.filter {
        it.isEncoder &&
          it.supportedTypes.any { type -> type.equals(mime, true) }
      }
    val caps = matches.mapNotNull { runCatching { it.getCapabilitiesForType(mime) }.getOrNull() }
    val hdr =
      caps.any { c ->
        c.profileLevels.any {
          it.profile == MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10HDR10 ||
            it.profile == MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10HDR10Plus
        }
      }
    return CodecCapability(
      codec,
      if (matches.isEmpty()) CapabilitySupport.UNSUPPORTED else CapabilitySupport.SUPPORTED,
      if (Build.VERSION.SDK_INT <
        29
      ) {
        CapabilitySupport.UNKNOWN
      } else if (matches.any { it.isHardwareAccelerated }) {
        CapabilitySupport.SUPPORTED
      } else {
        CapabilitySupport.UNSUPPORTED
      },
      if (hdr) CapabilitySupport.SUPPORTED else CapabilitySupport.UNKNOWN,
      matches.map { it.name }.toTypedArray(),
      caps
        .mapNotNull {
          it.videoCapabilities?.supportedWidths?.upper
        }.maxOrNull()
        ?.toDouble(),
      caps.mapNotNull { it.videoCapabilities?.supportedHeights?.upper }.maxOrNull()?.toDouble(),
    )
  }

  fun check(plan: VideoPlan) {
    if (plan.canCopy) return
    val encoders =
      MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.filter {
        it.isEncoder &&
          it.supportedTypes.any { t -> t.equals(plan.mime, true) }
      }
    require(
      encoders.any {
        runCatching {
          it
            .getCapabilitiesForType(
              plan.mime,
            ).videoCapabilities
            ?.areSizeAndRateSupported(plan.width, plan.height, plan.fps) ==
            true
        }.getOrDefault(false)
      },
    ) { "[UNSUPPORTED_EXPORT] No advertised encoder supports the requested size and frame rate." }
  }
}
