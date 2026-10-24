import Darwin
import Foundation

/// Streaming BS.1770 gating with a 4x, 48-tap windowed-sinc peak estimate. Mono/stereo only.
final class LoudnessMeter {
  struct Filter {
    let b0: Double, b1: Double, b2: Double, a1: Double, a2: Double
    var z1 = 0.0, z2 = 0.0
    mutating func apply(_ x: Double) -> Double {
      let y = b0 * x + z1
      z1 = b1 * x - a1 * y + z2
      z2 = b2 * x - a2 * y
      return y
    }
  }
  let sampleRate: Int, channels: Int
  private var shelf: [Filter], highpass: [Filter]
  private var history: [[Double]], peakCoefficients: [[Double]]
  private var energies: [Double], blocks: [Double] = []
  var exceededDurationLimit: Bool { frame > sampleRate * 86400 }
  private var frame = 0, ringIndex = 0, peakIndex = 0
  private var sum = 0.0, samplePeak = 0.0, truePeak = 0.0
  init(sampleRate: Int, channels: Int) throws {
    guard sampleRate >= 8000, sampleRate <= 192000, (1...2).contains(channels) else {
      throw MeterError.unsupported
    }
    self.sampleRate = sampleRate
    self.channels = channels
    let k = Darwin.tan(Double.pi * 1681.974450955533 / Double(sampleRate))
    let q = 0.7071752369554196
    let vh = Darwin.pow(10, 3.999843853973347 / 20)
    let vb = Darwin.pow(vh, 0.4996667741545416)
    let den = 1 + k / q + k * k
    let pre = Filter(
      b0: (vh + vb * k / q + k * k) / den, b1: 2 * (k * k - vh) / den,
      b2: (vh - vb * k / q + k * k) / den, a1: 2 * (k * k - 1) / den, a2: (1 - k / q + k * k) / den)
    let r = Darwin.tan(Double.pi * 38.13547087602444 / Double(sampleRate))
    let rq = 0.5003270373238773
    let rd = 1 + r / rq + r * r
    let hp = Filter(b0: 1, b1: -2, b2: 1, a1: 2 * (r * r - 1) / rd, a2: (1 - r / rq + r * r) / rd)
    shelf = Array(repeating: pre, count: channels)
    highpass = Array(repeating: hp, count: channels)
    energies = Array(repeating: 0, count: max(1, Int((Double(sampleRate) * 0.4).rounded())))
    history = Array(repeating: Array(repeating: 0, count: 12), count: channels)
    peakCoefficients = []
    for phase in 0..<4 {
      var coefficients: [Double] = []
      for tap in 0..<12 {
        let n = tap * 4 + phase
        let x: Double = (Double(n) - 23.5) / 4
        let sinc: Double = Swift.abs(x) < 1e-12 ? 1 : Darwin.sin(Double.pi * x) / (Double.pi * x)
        let window: Double = 0.5 * (1 - Darwin.cos(2 * Double.pi * Double(n) / 47))
        coefficients.append(sinc * window)
      }
      peakCoefficients.append(coefficients)
    }
  }
  func add(_ samples: UnsafeBufferPointer<Float>) {
    for base in stride(from: 0, to: samples.count - channels + 1, by: channels) {
      var energy = 0.0
      for channel in 0..<channels {
        let raw = Double(samples[base + channel])
        let x = raw.isFinite ? raw : 0
        samplePeak = max(samplePeak, Swift.abs(x))
        history[channel][peakIndex] = x
        for phase in 0..<4 {
          var interpolated = 0.0
          for tap in 0..<12 {
            interpolated +=
              history[channel][(peakIndex - tap + 12) % 12] * peakCoefficients[phase][tap]
          }
          truePeak = max(truePeak, Swift.abs(interpolated))
        }
        let weighted = highpass[channel].apply(shelf[channel].apply(x))
        energy += weighted * weighted
      }
      sum += energy - energies[ringIndex]
      energies[ringIndex] = energy
      ringIndex = (ringIndex + 1) % energies.count
      peakIndex = (peakIndex + 1) % 12
      frame += 1
      let hop = max(1, Int((Double(sampleRate) * 0.1).rounded()))
      if !exceededDurationLimit && frame >= energies.count && (frame - energies.count) % hop == 0 {
        blocks.append(max(0, sum) / Double(energies.count))
      }
    }
  }
  struct Measurement {
    let lufs: Double?, samplePeakDb: Double?, truePeakDb: Double?, durationMs: Double
  }
  func finish() -> Measurement {
    // Flush the finite impulse response tail without adding silence to loudness/duration.
    for _ in 0..<12 {
      for channel in 0..<channels {
        history[channel][peakIndex] = 0
        for phase in 0..<4 {
          var x = 0.0
          for tap in 0..<12 {
            x += history[channel][(peakIndex - tap + 12) % 12] * peakCoefficients[phase][tap]
          }
          truePeak = max(truePeak, Swift.abs(x))
        }
      }
      peakIndex = (peakIndex + 1) % 12
    }
    let absolute = blocks.filter { $0 > Darwin.pow(10, (-70 + 0.691) / 10) }
    let average = absolute.isEmpty ? 0 : absolute.reduce(0, +) / Double(absolute.count)
    let gated = absolute.filter { $0 >= average * 0.1 }
    let energy = gated.isEmpty ? 0 : gated.reduce(0, +) / Double(gated.count)
    return Measurement(
      lufs: energy > 0 ? -0.691 + 10 * Darwin.log10(energy) : nil,
      samplePeakDb: samplePeak > 0 ? 20 * Darwin.log10(samplePeak) : nil,
      truePeakDb: truePeak > 0 || samplePeak > 0
        ? 20 * Darwin.log10(max(truePeak, samplePeak)) : nil,
      durationMs: Double(frame) * 1000 / Double(sampleRate))
  }
  enum MeterError: Error { case unsupported }
}
