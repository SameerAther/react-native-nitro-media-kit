package com.margelo.nitro.nitromediakit

import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test
import java.io.File

class AssetStorageTest {
  @Test fun disposeDefersCleanupUntilEveryBorrowerFinishes() {
    val file = File.createTempFile("media-asset", ".bin").apply { writeBytes(byteArrayOf(1, 2, 3)) }
    try {
      var cleaned = 0
      val storage = AssetStorage(file) { cleaned++ }
      val a = storage.borrow()
      val b = storage.borrow()
      storage.close()
      storage.close()
      assertEquals(0, cleaned)
      a.close()
      a.close()
      assertEquals(0, cleaned)
      b.close()
      assertEquals(1, cleaned)
      try {
        storage.borrow()
        fail("Disposed asset accepted work")
      } catch (_: IllegalStateException) {
      }
    } finally {
      file.delete()
    }
  }

  @Test fun mutationInvalidatesCachedMetadataBeforeProcessing() {
    val file = File.createTempFile("media-asset", ".bin").apply { writeText("old") }
    try {
      val storage = AssetStorage(file) {}
      val lease = storage.borrow()
      storage.validate()
      file.appendText("changed")
      try {
        storage.validate()
        fail("Changed source accepted")
      } catch (_: IllegalStateException) {
      }
      lease.close()
      storage.close()
    } finally {
      file.delete()
    }
  }
}
