///
/// HybridMediaKitSpecCxx.swift
/// This file was generated by nitrogen. DO NOT MODIFY THIS FILE.
/// https://github.com/mrousavy/nitro
/// Copyright © 2024 Marc Rousavy @ Margelo
///

import Foundation
import NitroModules

/**
 * Helper class for converting instances of `HybridMediaKitSpecCxx` from- and to unsafe pointers.
 * This is useful to pass Swift classes to C++, without having to strongly type the C++ function signature.
 * The actual Swift type can be included in the .cpp file, without having to forward-declare anything in .hpp.
 */
public final class HybridMediaKitSpecCxxUnsafe {
  /**
   * Casts a `HybridMediaKitSpecCxx` instance to a retained unsafe raw pointer.
   * This acquires one additional strong reference on the object!
   */
  public static func toUnsafe(_ instance: HybridMediaKitSpecCxx) -> UnsafeMutableRawPointer {
    return Unmanaged.passRetained(instance).toOpaque()
  }

  /**
   * Casts an unsafe pointer to a `HybridMediaKitSpecCxx`.
   * The pointer has to be a retained opaque `Unmanaged<HybridMediaKitSpecCxx>`.
   * This removes one strong reference from the object!
   */
  public static func fromUnsafe(_ pointer: UnsafeMutableRawPointer) -> HybridMediaKitSpecCxx {
    return Unmanaged<HybridMediaKitSpecCxx>.fromOpaque(pointer).takeRetainedValue()
  }
}

/**
 * A class implementation that bridges HybridMediaKitSpec over to C++.
 * In C++, we cannot use Swift protocols - so we need to wrap it in a class to make it strongly defined.
 *
 * Also, some Swift types need to be bridged with special handling:
 * - Enums need to be wrapped in Structs, otherwise they cannot be accessed bi-directionally (Swift bug: https://github.com/swiftlang/swift/issues/75330)
 * - Other HybridObjects need to be wrapped/unwrapped from the Swift TCxx wrapper
 * - Throwing methods need to be wrapped with a Result<T, Error> type, as exceptions cannot be propagated to C++
 */
public class HybridMediaKitSpecCxx {
  /**
   * The Swift <> C++ bridge's namespace (`margelo::nitro::mediakit::bridge::swift`)
   * from `MediaKit-Swift-Cxx-Bridge.hpp`.
   * This contains specialized C++ templates, and C++ helper functions that can be accessed from Swift.
   */
  public typealias bridge = margelo.nitro.mediakit.bridge.swift

  /**
   * Holds an instance of the `HybridMediaKitSpec` Swift protocol.
   */
  private var __implementation: any HybridMediaKitSpec

  /**
   * Create a new `HybridMediaKitSpecCxx` that wraps the given `HybridMediaKitSpec`.
   * All properties and methods bridge to C++ types.
   */
  public init(_ implementation: some HybridMediaKitSpec) {
    self.__implementation = implementation
    /* no base class */
  }

  /**
   * Get the actual `HybridMediaKitSpec` instance this class wraps.
   */
  @inline(__always)
  public func getHybridMediaKitSpec() -> any HybridMediaKitSpec {
    return __implementation
  }

  /**
   * Contains a (weak) reference to the C++ HybridObject to cache it.
   */
  public var hybridContext: margelo.nitro.HybridContext {
    @inline(__always)
    get {
      return self.__implementation.hybridContext
    }
    @inline(__always)
    set {
      self.__implementation.hybridContext = newValue
    }
  }

  /**
   * Get the memory size of the Swift class (plus size of any other allocations)
   * so the JS VM can properly track it and garbage-collect the JS object if needed.
   */
  @inline(__always)
  public var memorySize: Int {
    return self.__implementation.memorySize
  }

  // Properties
  public var pi: Double {
    @inline(__always)
    get {
      return self.__implementation.pi
    }
  }

  // Methods
  @inline(__always)
  public func add(a: Double, b: Double) -> Double {
    do {
      let __result = try self.__implementation.add(a: a, b: b)
      return __result
    } catch {
      let __message = "\(error.localizedDescription)"
      fatalError("Swift errors can currently not be propagated to C++! See https://github.com/swiftlang/swift/issues/75290 (Error: \(__message))")
    }
  }
  
  @inline(__always)
  public func convertImageToVideo(image: std.string, duration: Double) -> bridge.std__shared_ptr_Promise_std__string__ {
    do {
      let __result = try self.__implementation.convertImageToVideo(image: String(image), duration: duration)
      return { () -> bridge.std__shared_ptr_Promise_std__string__ in
        let __promise = bridge.create_std__shared_ptr_Promise_std__string__()
        __result
          .then({ __result in __promise.pointee.resolve(std.string(__result)) })
          .catch({ __error in __promise.pointee.reject(__error.toCpp()) })
        return __promise
      }()
    } catch {
      let __message = "\(error.localizedDescription)"
      fatalError("Swift errors can currently not be propagated to C++! See https://github.com/swiftlang/swift/issues/75290 (Error: \(__message))")
    }
  }
  
  @inline(__always)
  public func mergeVideos(videos: bridge.std__vector_std__string_) -> bridge.std__shared_ptr_Promise_std__string__ {
    do {
      let __result = try self.__implementation.mergeVideos(videos: videos.map({ __item in String(__item) }))
      return { () -> bridge.std__shared_ptr_Promise_std__string__ in
        let __promise = bridge.create_std__shared_ptr_Promise_std__string__()
        __result
          .then({ __result in __promise.pointee.resolve(std.string(__result)) })
          .catch({ __error in __promise.pointee.reject(__error.toCpp()) })
        return __promise
      }()
    } catch {
      let __message = "\(error.localizedDescription)"
      fatalError("Swift errors can currently not be propagated to C++! See https://github.com/swiftlang/swift/issues/75290 (Error: \(__message))")
    }
  }
}
