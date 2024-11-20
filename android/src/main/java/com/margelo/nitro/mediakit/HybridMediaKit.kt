package com.margelo.nitro.mediakit

import kotlin.math.PI

class HybridMediaKit : HybridMediaKitSpec() {
    override val memorySize: Long
        get() = 0L

    override val pi: Double
        get() = PI

    override fun add(a: Double, b: Double): Double {
        return a + b
    }
}
