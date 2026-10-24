import CoreImage
import Foundation
import UIKit

final class PreparedOverlays: @unchecked Sendable {
  struct Layer {
    let image: CIImage
    let start: Double, end: Double, opacity: Double, fadeIn: Double, fadeOut: Double
  }
  let layers: [Layer]
  private let cues: [SubtitleCue]
  private let subtitle: SubtitleOptions?
  private let size: CGSize
  private let kit: NitroMediaKit
  private let lock = NSLock()
  private var active: [Int] = []
  private var caption: CIImage?
  init(
    layers: [Layer], cues: [SubtitleCue], subtitle: SubtitleOptions?, size: CGSize,
    kit: NitroMediaKit
  ) {
    self.layers = layers
    self.cues = cues
    self.subtitle = subtitle
    self.size = size
    self.kit = kit
  }
  func composite(over source: CIImage, timeMs: Double) throws -> CIImage {
    var frame = source
    for layer in layers where timeMs >= layer.start && timeMs < layer.end {
      let alpha =
        layer.opacity
        * min(
          1, layer.fadeIn > 0 ? (timeMs - layer.start) / layer.fadeIn : 1,
          layer.fadeOut > 0 ? (layer.end - timeMs) / layer.fadeOut : 1)
      let image = layer.image.applyingFilter(
        "CIColorMatrix", parameters: ["inputAVector": CIVector(x: 0, y: 0, z: 0, w: alpha)])
      frame = image.composited(over: frame)
    }
    if let subtitle {
      let indices = cues.indices.filter { timeMs >= cues[$0].startMs && timeMs < cues[$0].endMs }
      lock.lock()
      defer { lock.unlock() }
      if indices != active {
        if indices.isEmpty {
          caption = nil
        } else {
          let margin: Double = subtitle.margin ?? Double(min(size.width, size.height)) * 0.05
          let font: Double = subtitle.fontSize ?? Double(min(size.width, size.height)) * 0.045
          let color = try kit.watermarkColor(subtitle.color ?? "#FFFFFFFF", opacity: 1)
          let text = try kit.watermarkImage(
            indices.map { cues[$0].text }.joined(separator: "\n"), fontSize: font, color: color,
            maxSize: CGSize(
              width: size.width - 2 * margin, height: min(size.height - 2 * margin, size.height / 2)
            ))
          caption = text.transformed(
            by: CGAffineTransform(translationX: (size.width - text.extent.width) / 2, y: margin))
        }
        active = indices
      }
      if let caption { frame = caption.composited(over: frame) }
    }
    return frame
  }
}
extension NitroMediaKit {
  func prepareOverlays(options: ExportVideoOptions, plan: VideoPlan) async throws
    -> PreparedOverlays
  {
    let size = CGSize(width: plan.width, height: plan.height)
    var layers: [PreparedOverlays.Layer] = []
    var pixels = 0.0
    if let watermark = try makeOverlay(options: options, size: size) {
      layers.append(
        .init(image: watermark, start: 0, end: plan.durationMs, opacity: 1, fadeIn: 0, fadeOut: 0))
    }
    for overlay in options.overlays ?? [] {
      guard overlay.x + overlay.width <= size.width, overlay.y + overlay.height <= size.height
      else { throw MediaJob.error("Overlay must fit the output canvas.") }
      pixels += ceil(overlay.width) * ceil(overlay.height)
      guard pixels <= min(16_777_216, Double(ProcessInfo.processInfo.physicalMemory) / 64) else {
        throw MediaJob.error("Combined overlays exceed the image memory budget.")
      }
      var image: CIImage
      if overlay.kind == .image {
        let path = try await getLocalFilePath(overlay.uri!)
        let bitmap = try downsampleImage(
          path: path, maxLongEdge: max(overlay.width, overlay.height))
        guard let cg = bitmap.cgImage else { throw MediaJob.error("Cannot decode overlay image.") }
        image = CIImage(cgImage: cg)
        let scale = min(overlay.width / image.extent.width, overlay.height / image.extent.height)
        image = image.transformed(by: CGAffineTransform(scaleX: scale, y: scale))
        image = image.transformed(
          by: CGAffineTransform(
            translationX: overlay.x + (overlay.width - image.extent.width) / 2,
            y: size.height - overlay.y - (overlay.height + image.extent.height) / 2))
      } else {
        let font = try ExportPlanner.finite(
          overlay.fontSize ?? min(512, max(1, min(overlay.width, overlay.height) * 0.4)), min: 1,
          max: 512,
          name: "fontSize")
        image = try watermarkImage(
          overlay.text!, fontSize: font,
          color: watermarkColor(overlay.color ?? "#FFFFFFFF", opacity: 1),
          maxSize: CGSize(width: overlay.width, height: overlay.height))
        image = image.transformed(
          by: CGAffineTransform(
            translationX: overlay.x, y: size.height - overlay.y - image.extent.height))
      }
      layers.append(
        .init(
          image: image, start: overlay.startMs ?? 0, end: overlay.endMs ?? plan.durationMs,
          opacity: overlay.opacity ?? 1, fadeIn: overlay.fadeInMs ?? 0,
          fadeOut: overlay.fadeOutMs ?? 0))
    }
    let cues =
      try options.subtitles.map { try EditingValidation.cues($0, duration: plan.durationMs) } ?? []
    if let subtitle = options.subtitles {
      let margin: Double = subtitle.margin ?? Double(min(size.width, size.height)) * 0.05
      guard margin.isFinite, margin >= 0, margin * 2 < min(size.width, size.height) else {
        throw MediaJob.error("Invalid subtitle margin.")
      }
      _ = try ExportPlanner.finite(
        subtitle.fontSize ?? Double(min(size.width, size.height)) * 0.045, min: 1, max: 512,
        name: "fontSize")
      _ = try watermarkColor(subtitle.color ?? "#FFFFFFFF", opacity: 1)
    }
    return PreparedOverlays(
      layers: layers, cues: cues, subtitle: options.subtitles, size: size, kit: self)
  }
  static func spatial(_ image: CIImage, geometry: GeometryPlan, target: CGSize) -> CIImage {
    let extent = image.extent
    var frame = image.transformed(
      by: CGAffineTransform(translationX: -extent.minX, y: -extent.minY))
    // CI uses a bottom-left origin; the public crop API uses oriented top-left pixels.
    frame = frame.cropped(
      to: CGRect(
        x: geometry.x, y: extent.height - geometry.y - geometry.cropHeight,
        width: geometry.cropWidth, height: geometry.cropHeight))
    frame = frame.transformed(
      by: CGAffineTransform(translationX: -frame.extent.minX, y: -frame.extent.minY))
    if geometry.rotation != 0 {
      frame = frame.transformed(
        by: CGAffineTransform(rotationAngle: -Double(geometry.rotation) * Double.pi / 180))
    }
    frame = frame.transformed(
      by: CGAffineTransform(translationX: -frame.extent.minX, y: -frame.extent.minY))
    if geometry.flipX || geometry.flipY {
      frame = frame.transformed(
        by: CGAffineTransform(scaleX: geometry.flipX ? -1 : 1, y: geometry.flipY ? -1 : 1))
      frame = frame.transformed(
        by: CGAffineTransform(translationX: -frame.extent.minX, y: -frame.extent.minY))
    }
    let scale =
      geometry.cover
      ? max(target.width / frame.extent.width, target.height / frame.extent.height)
      : min(target.width / frame.extent.width, target.height / frame.extent.height)
    frame = frame.transformed(by: CGAffineTransform(scaleX: scale, y: scale))
    frame = frame.transformed(
      by: CGAffineTransform(
        translationX: (target.width - frame.extent.width) / 2,
        y: (target.height - frame.extent.height) / 2))
    return frame.cropped(to: CGRect(origin: .zero, size: target))
  }
}
