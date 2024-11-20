///
/// MediaKit-Swift-Cxx-Umbrella.hpp
/// This file was generated by nitrogen. DO NOT MODIFY THIS FILE.
/// https://github.com/mrousavy/nitro
/// Copyright © 2024 Marc Rousavy @ Margelo
///

#pragma once

// Forward declarations of C++ defined types
// Forward declaration of `HybridMediaKitSpec` to properly resolve imports.
namespace margelo::nitro::mediakit { class HybridMediaKitSpec; }

// Include C++ defined types
#include "HybridMediaKitSpec.hpp"
#include <memory>

// C++ helpers for Swift
#include "MediaKit-Swift-Cxx-Bridge.hpp"

// Common C++ types used in Swift
#include <NitroModules/ArrayBufferHolder.hpp>
#include <NitroModules/AnyMapHolder.hpp>
#include <NitroModules/HybridContext.hpp>
#include <NitroModules/RuntimeError.hpp>

// Forward declarations of Swift defined types
// Forward declaration of `HybridMediaKitSpecCxx` to properly resolve imports.
namespace MediaKit { class HybridMediaKitSpecCxx; }

// Include Swift defined types
#if __has_include("MediaKit-Swift.h")
// This header is generated by Xcode/Swift on every app build.
// If it cannot be found, make sure the Swift module's name (= podspec name) is actually "MediaKit".
#include "MediaKit-Swift.h"
#else
#error MediaKit's autogenerated Swift header cannot be found! Make sure the Swift module's name (= podspec name) is actually "MediaKit", and try building the app first.
#endif
