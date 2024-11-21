//
//  HybridMediaKit.swift
//  Pods
//
//  Created by Sameer Ather on 20/11/2024.
//
import Foundation

class HybridMediaKit : HybridMediaKitSpec {
  public var hybridContext = margelo.nitro.HybridContext()
  public var memorySize: Int {
    return getSizeOf(self)
  }

  public var pi: Double {
    return Double.pi
  }
  public func add(a: Double, b: Double) throws -> Double {
    return a + b
  }
}
