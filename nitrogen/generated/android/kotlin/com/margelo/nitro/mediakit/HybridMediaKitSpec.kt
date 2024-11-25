///
/// HybridMediaKitSpec.kt
/// This file was generated by nitrogen. DO NOT MODIFY THIS FILE.
/// https://github.com/mrousavy/nitro
/// Copyright © 2024 Marc Rousavy @ Margelo
///

package com.margelo.nitro.mediakit

import android.util.Log
import androidx.annotation.Keep
import com.facebook.jni.HybridData
import com.facebook.proguard.annotations.DoNotStrip
import com.margelo.nitro.core.*

/**
 * A Kotlin class representing the MediaKit HybridObject.
 * Implement this abstract class to create Kotlin-based instances of MediaKit.
 */
@DoNotStrip
@Keep
@Suppress("RedundantSuppression", "KotlinJniMissingFunction", "PropertyName", "RedundantUnitReturnType", "unused")
abstract class HybridMediaKitSpec: HybridObject() {
  @DoNotStrip
  private var mHybridData: HybridData = initHybrid()

  init {
    // Pass this `HybridData` through to it's base class,
    // to represent inheritance to JHybridObject on C++ side
    super.updateNative(mHybridData)
  }

  /**
   * Call from a child class to initialize HybridData with a child.
   */
  override fun updateNative(hybridData: HybridData) {
    mHybridData = hybridData
  }

  // Properties
  @get:DoNotStrip
  @get:Keep
  abstract val pi: Double

  // Methods
  @DoNotStrip
  @Keep
  abstract fun add(a: Double, b: Double): Double
  
  @DoNotStrip
  @Keep
  abstract fun convertImageToVideo(image: String, duration: Double): Promise<String>

  private external fun initHybrid(): HybridData

  companion object {
    private const val TAG = "HybridMediaKitSpec"
    init {
      try {
        Log.i(TAG, "Loading MediaKit C++ library...")
        System.loadLibrary("MediaKit")
        Log.i(TAG, "Successfully loaded MediaKit C++ library!")
      } catch (e: Error) {
        Log.e(TAG, "Failed to load MediaKit C++ library! Is it properly installed and linked? " +
                    "Is the name correct? (see `CMakeLists.txt`, at `add_library(...)`)", e)
        throw e
      }
    }
  }
}