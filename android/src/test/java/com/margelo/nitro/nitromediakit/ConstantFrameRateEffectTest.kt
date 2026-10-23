package com.margelo.nitro.nitromediakit

import androidx.media3.common.GlObjectsProvider
import androidx.media3.common.GlTextureInfo
import androidx.media3.effect.GlShaderProgram
import java.lang.reflect.Proxy
import org.junit.Assert.*
import org.junit.Test

@android.annotation.SuppressLint("UnsafeOptInUsageError")
class ConstantFrameRateEffectTest {
    private val provider = Proxy.newProxyInstance(GlObjectsProvider::class.java.classLoader, arrayOf(GlObjectsProvider::class.java)) { _, _, _ -> error("Resampling must not allocate GL objects") } as GlObjectsProvider
    private fun texture(id: Int) = GlTextureInfo(id, -1, -1, 16, 16)

    @Test fun lookaheadAlwaysIncludesEnoughUpstreamCapacity() {
        val effects = ConstantFrameRateEffect.create(30.0, 1_000_000)
        assertEquals(2, (effects.first() as androidx.media3.effect.FrameCache).capacity)
        assertTrue(effects.last() is ConstantFrameRateEffect)
    }
    @Test fun repeatsAndReleasesTexturesExactlyOnce() {
        val program = ConstantFrameRateEffect.Program(10.0, 1_000_000)
        val timestamps = mutableListOf<Long>(); val frames = mutableListOf<Int>(); val released = mutableListOf<Int>()
        var ended = false
        program.setInputListener(object : GlShaderProgram.InputListener { override fun onInputFrameProcessed(texture: GlTextureInfo) { released.add(texture.texId) } })
        program.setOutputListener(object : GlShaderProgram.OutputListener {
            override fun onOutputFrameAvailable(texture: GlTextureInfo, time: Long) { timestamps.add(time); frames.add(texture.texId); program.releaseOutputFrame(texture) }
            override fun onCurrentOutputStreamEnded() { ended = true }
        })
        program.queueInputFrame(provider, texture(1), 0)
        program.queueInputFrame(provider, texture(2), 500_000)
        program.signalEndOfCurrentInputStream()
        assertEquals((0L..9L).map { it * 100_000 }, timestamps)
        assertEquals(List(5) { 1 } + List(5) { 2 }, frames)
        assertEquals(listOf(1, 2), released); assertTrue(ended)
    }
    @Test fun eosDuringBackpressureDoesNotEraseTheLastFrame() {
        val program = ConstantFrameRateEffect.Program(4.0, 1_000_000)
        val frames = mutableListOf<Int>(); var pending: GlTextureInfo? = null
        program.setOutputListener(object : GlShaderProgram.OutputListener {
            override fun onOutputFrameAvailable(texture: GlTextureInfo, time: Long) { frames.add(texture.texId); pending = texture }
        })
        program.queueInputFrame(provider, texture(1), 0)
        program.queueInputFrame(provider, texture(2), 500_000)
        program.signalEndOfCurrentInputStream()
        repeat(4) { val value = pending; pending = null; if (value != null) program.releaseOutputFrame(value) }
        assertEquals(listOf(1, 1, 2, 2), frames)
    }
    @Test fun fractionalGridHasNoCumulativeTimestampDrift() {
        val program = ConstantFrameRateEffect.Program(24000.0 / 1001, 10_000_000)
        val times = mutableListOf<Long>()
        program.setOutputListener(object : GlShaderProgram.OutputListener {
            override fun onOutputFrameAvailable(texture: GlTextureInfo, time: Long) { times.add(time); program.releaseOutputFrame(texture) }
        })
        program.queueInputFrame(provider, texture(1), 0); program.signalEndOfCurrentInputStream()
        assertEquals(240, times.size)
        assertEquals(9_968_292L, times.last())
        assertTrue(times.zipWithNext().all { (a, b) -> b > a })
    }
}
