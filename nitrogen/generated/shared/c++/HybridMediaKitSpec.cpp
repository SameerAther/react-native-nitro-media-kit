///
/// HybridMediaKitSpec.cpp
/// This file was generated by nitrogen. DO NOT MODIFY THIS FILE.
/// https://github.com/mrousavy/nitro
/// Copyright © 2024 Marc Rousavy @ Margelo
///

#include "HybridMediaKitSpec.hpp"

namespace margelo::nitro::mediakit {

  void HybridMediaKitSpec::loadHybridMethods() {
    // load base methods/properties
    HybridObject::loadHybridMethods();
    // load custom methods/properties
    registerHybrids(this, [](Prototype& prototype) {
      prototype.registerHybridGetter("pi", &HybridMediaKitSpec::getPi);
      prototype.registerHybridMethod("add", &HybridMediaKitSpec::add);
    });
  }

} // namespace margelo::nitro::mediakit
