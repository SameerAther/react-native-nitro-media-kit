package com.margelo.nitro.nitromediakit
  
import com.facebook.proguard.annotations.DoNotStrip

@DoNotStrip
class NitroMediaKit : HybridNitroMediaKitSpec() {
  override fun multiply(a: Double, b: Double): Double {
    return a * b
  }
}
