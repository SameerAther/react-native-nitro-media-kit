import AVFoundation
import Foundation
import NitroModules
import VideoToolbox

enum DeviceCapabilities {
  static func get() -> MediaCapabilities {
    var encoders: CFArray?
    let status = VTCopyVideoEncoderList(nil, &encoders)
    let entries = encoders as? [[String: Any]] ?? []
    let codecs: [CodecCapability] = [
      (VideoCodec.h264, kCMVideoCodecType_H264), (VideoCodec.hevc, kCMVideoCodecType_HEVC),
    ].map { codec, type in
      let matches = entries.filter {
        ($0[kVTVideoEncoderList_CodecType as String] as? NSNumber)?.uint32Value == type
      }
      return CodecCapability(
        codec: codec,
        encode: status != noErr ? .unknown : matches.isEmpty ? .unsupported : .supported,
        hardware: matches.contains(where: {
          ($0[kVTVideoEncoderList_IsHardwareAccelerated as String] as? Bool) == true
        }) ? .supported : .unknown,
        hdr: .unknown,
        encoderNames: matches.compactMap { $0[kVTVideoEncoderList_EncoderID as String] as? String },
        maxWidth: nil, maxHeight: nil)
    }
    return MediaCapabilities(platform: "ios", codecs: codecs, advisory: true)
  }
  static func check(_ plan: VideoPlan) throws {
    if plan.canCopy { return }
    let settings: [String: Any] = [
      AVVideoCodecKey: plan.mime == "video/hevc" ? AVVideoCodecType.hevc : AVVideoCodecType.h264,
      AVVideoWidthKey: plan.width, AVVideoHeightKey: plan.height,
    ]
    let url = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
      .appendingPathExtension("mp4")
    let writer = try AVAssetWriter(outputURL: url, fileType: .mp4)
    guard writer.canApply(outputSettings: settings, forMediaType: .video) else {
      throw MediaJob.error("Requested encoder settings are unsupported.")
    }
  }
}
