package com.margelo.nitro.nitromediakit

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import org.junit.Assert.assertEquals
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class EnvelopeAudioProcessorTest {
  @Test fun fadesCountFramesRatherThanChannelsAndResetBetweenStreams() {
    val processor = EnvelopeAudioProcessor(1.0, 2.0, 2.0, 4.0)
    processor.configure(AudioProcessor.AudioFormat(1000, 2, C.ENCODING_PCM_16BIT))
    repeat(2) {
      processor.flush(AudioProcessor.StreamMetadata.DEFAULT)
      val data = ByteBuffer.allocateDirect(16).order(ByteOrder.nativeOrder())
      repeat(8) { data.putShort(10000.toShort()) }
      data.flip()
      processor.queueInput(data)
      val output = processor.output.order(ByteOrder.nativeOrder())
      for (expected in listOf(0, 5000, 10000, 5000)) repeat(2) { assertEquals(expected, output.short.toInt()) }
      assertEquals(0, output.remaining())
    }
    processor.reset()
  }

  @Test fun outputTimelineFadesUseTheClipsAbsoluteStart() {
    val processor = EnvelopeAudioProcessor(0.5, 0.0, 0.0, 2.0, 2.0, 4.0, 0.0, 10.0)
    processor.configure(AudioProcessor.AudioFormat(1000, 1, C.ENCODING_PCM_16BIT))
    processor.flush(AudioProcessor.StreamMetadata.DEFAULT)
    val data =
      ByteBuffer
        .allocateDirect(4)
        .order(ByteOrder.nativeOrder())
        .putShort(10000)
        .putShort(10000)
    data.flip()
    processor.queueInput(data)
    val output = processor.output.order(ByteOrder.nativeOrder())
    assertEquals(2500, output.short.toInt())
    assertEquals(3750, output.short.toInt())
  }
}
