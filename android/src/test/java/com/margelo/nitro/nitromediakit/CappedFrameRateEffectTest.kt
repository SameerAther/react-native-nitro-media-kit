package com.margelo.nitro.nitromediakit

import androidx.media3.common.GlObjectsProvider
import androidx.media3.common.GlTextureInfo
import androidx.media3.effect.GlShaderProgram
import java.lang.reflect.Proxy
import kotlin.math.roundToLong
import org.junit.Assert.*
import org.junit.Test

@android.annotation.SuppressLint("UnsafeOptInUsageError")
class CappedFrameRateEffectTest {
    private val provider = Proxy.newProxyInstance(GlObjectsProvider::class.java.classLoader, arrayOf(GlObjectsProvider::class.java)) { _, _, _ -> error("Frame cap must not allocate GL objects") } as GlObjectsProvider

    @Test fun thirtyFpsIsActuallyReducedToTwentyFour() = checkCap(30.0, 24.0, 30, 24)
    @Test fun fractionalCapRetainsOriginalPresentationTimes() = checkCap(60.0, 23.5, 600, 235)
    private fun checkCap(sourceFps: Double, cap: Double, count: Int, expected: Int) {
        val program = CappedFrameRateEffect.Program(cap)
        val source = (0 until count).map { (it * 1_000_000 / sourceFps).roundToLong() }
        val times = mutableListOf<Long>(); val released = mutableListOf<Int>(); var ended = false
        program.setInputListener(object : GlShaderProgram.InputListener { override fun onInputFrameProcessed(texture: GlTextureInfo) { released.add(texture.texId) } })
        program.setOutputListener(object : GlShaderProgram.OutputListener {
            override fun onOutputFrameAvailable(texture: GlTextureInfo, time: Long) { times.add(time); program.releaseOutputFrame(texture) }
            override fun onCurrentOutputStreamEnded() { ended = true }
        })
        source.forEachIndexed { index, time -> program.queueInputFrame(provider, GlTextureInfo(index + 1, -1, -1, 16, 16), time) }
        program.signalEndOfCurrentInputStream()
        assertEquals(expected, times.size)
        assertTrue(source.containsAll(times)); assertEquals(count, released.distinct().size); assertTrue(ended)
        program.flush()
        program.queueInputFrame(provider, GlTextureInfo(1000, -1, -1, 16, 16), 0)
        assertEquals(0L, times.last())
    }
}
