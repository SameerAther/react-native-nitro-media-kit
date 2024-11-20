///
/// MediaKit-Swift-Cxx-Bridge.cpp
/// This file was generated by nitrogen. DO NOT MODIFY THIS FILE.
/// https://github.com/mrousavy/nitro
/// Copyright © 2024 Marc Rousavy @ Margelo
///

#include "MediaKit-Swift-Cxx-Bridge.hpp"

// Include C++ implementation defined types
#include "HybridMediaKitSpecSwift.hpp"
#include "MediaKit-Swift-Cxx-Umbrella.hpp"
#include <NitroModules/HybridContext.hpp>

namespace margelo::nitro::mediakit::bridge::swift {

  // pragma MARK: std::shared_ptr<margelo::nitro::mediakit::HybridMediaKitSpec>
  std::shared_ptr<margelo::nitro::mediakit::HybridMediaKitSpec> create_std__shared_ptr_margelo__nitro__mediakit__HybridMediaKitSpec_(void* _Nonnull swiftUnsafePointer) {
    MediaKit::HybridMediaKitSpecCxx swiftPart = MediaKit::HybridMediaKitSpecCxxUnsafe::fromUnsafe(swiftUnsafePointer);
    return HybridContext::getOrCreate<margelo::nitro::mediakit::HybridMediaKitSpecSwift>(swiftPart);
  }
  void* _Nonnull get_std__shared_ptr_margelo__nitro__mediakit__HybridMediaKitSpec_(std__shared_ptr_margelo__nitro__mediakit__HybridMediaKitSpec_ cppType) {
    std::shared_ptr<margelo::nitro::mediakit::HybridMediaKitSpecSwift> swiftWrapper = std::dynamic_pointer_cast<margelo::nitro::mediakit::HybridMediaKitSpecSwift>(cppType);
  #ifdef NITRO_DEBUG
    if (swiftWrapper == nullptr) [[unlikely]] {
      throw std::runtime_error("Class \"HybridMediaKitSpec\" is not implemented in Swift!");
    }
  #endif
    MediaKit::HybridMediaKitSpecCxx swiftPart = swiftWrapper->getSwiftPart();
    return MediaKit::HybridMediaKitSpecCxxUnsafe::toUnsafe(swiftPart);
  }

} // namespace margelo::nitro::mediakit::bridge::swift
