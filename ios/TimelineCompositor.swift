@preconcurrency import AVFoundation
import CoreImage
import Foundation

struct TimelineLayer: @unchecked Sendable {
  let trackID: CMPersistentTrackID
  let transform: CGAffineTransform
  let geometry: GeometryPlan
  let startMs: Double, durationMs: Double, fadeInMs: Double
}
final class TimelineInstruction: NSObject, AVVideoCompositionInstructionProtocol,
  @unchecked Sendable
{
  let timeRange: CMTimeRange
  let enablePostProcessing = false, containsTweening = true
  let requiredSourceTrackIDs: [NSValue]?
  let passthroughTrackID = kCMPersistentTrackID_Invalid
  let layers: [TimelineLayer]
  let size: CGSize
  let overlays: PreparedOverlays
  let control: PipelineControl
  init(
    range: CMTimeRange, layers: [TimelineLayer], size: CGSize, overlays: PreparedOverlays,
    control: PipelineControl
  ) {
    timeRange = range
    self.layers = layers
    self.size = size
    self.overlays = overlays
    self.control = control
    requiredSourceTrackIDs = layers.map { NSNumber(value: $0.trackID) }
  }
}
final class TimelineCompositor: NSObject, AVVideoCompositing, @unchecked Sendable {
  var sourcePixelBufferAttributes: [String: any Sendable]? {
    [
      kCVPixelBufferPixelFormatTypeKey as String: [
        kCVPixelFormatType_420YpCbCr10BiPlanarVideoRange,
        kCVPixelFormatType_420YpCbCr8BiPlanarVideoRange, kCVPixelFormatType_32BGRA,
      ], kCVPixelBufferMetalCompatibilityKey as String: true,
    ]
  }
  var requiredPixelBufferAttributesForRenderContext: [String: any Sendable] {
    [
      kCVPixelBufferPixelFormatTypeKey as String: kCVPixelFormatType_64RGBAHalf,
      kCVPixelBufferMetalCompatibilityKey as String: true,
    ]
  }
  var supportsWideColorSourceFrames: Bool { true }
  var supportsHDRSourceFrames: Bool { true }
  private let queue = DispatchQueue(label: "NitroMediaKit.compositor")
  private let lock = NSLock()
  private var context: AVVideoCompositionRenderContext?
  private var generation = 0
  func renderContextChanged(_ newRenderContext: AVVideoCompositionRenderContext) {
    lock.lock()
    context = newRenderContext
    lock.unlock()
  }
  func startRequest(_ request: AVAsynchronousVideoCompositionRequest) {
    lock.lock()
    let context = context
    let version = generation
    lock.unlock()
    queue.async { [self] in
      autoreleasepool {
        lock.lock()
        let cancelled = version != generation
        lock.unlock()
        if cancelled {
          request.finishCancelledRequest()
          return
        }
        do {
          guard let instruction = request.videoCompositionInstruction as? TimelineInstruction,
            let buffer = context?.newPixelBuffer()
          else { throw MediaJob.error("Cannot allocate timeline frame.") }
          try instruction.control.check()
          let time = request.compositionTime.seconds * 1000
          let canvas = CGRect(origin: .zero, size: instruction.size)
          var frame = CIImage(color: .black).cropped(to: canvas)
          for layer in instruction.layers {
            guard let source = request.sourceFrame(byTrackID: layer.trackID) else { continue }
            var image = CIImage(cvPixelBuffer: source).transformed(by: layer.transform)
            image = NitroMediaKit.spatial(image, geometry: layer.geometry, target: instruction.size)
            if layer.fadeInMs > 0 {
              let alpha = min(1, max(0, (time - layer.startMs) / layer.fadeInMs))
              image = image.applyingFilter(
                "CIColorMatrix", parameters: ["inputAVector": CIVector(x: 0, y: 0, z: 0, w: alpha)])
            }
            frame = image.composited(over: frame)
          }
          frame = try instruction.overlays.composite(over: frame, timeMs: time)
          NitroMediaKit.imageContext.render(
            frame, to: buffer, bounds: canvas,
            colorSpace: CGColorSpace(name: CGColorSpace.extendedLinearITUR_2020)!)
          request.finish(withComposedVideoFrame: buffer)
        } catch { request.finish(with: error) }
      }
    }
  }
  func cancelAllPendingVideoCompositionRequests() {
    lock.lock()
    generation += 1
    lock.unlock()
    queue.sync {}
  }
}
