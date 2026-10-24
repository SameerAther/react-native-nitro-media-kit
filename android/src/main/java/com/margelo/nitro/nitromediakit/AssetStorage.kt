package com.margelo.nitro.nitromediakit

import java.io.Closeable
import java.io.File

/** Retained input staging is released only after disposal AND the final borrower completes. */
internal class AssetStorage(
  val file: File,
  private val closeOwner: () -> Unit,
) {
  companion object {
    private val count =
      java.util.concurrent.atomic
        .AtomicInteger()
  }

  init {
    if (count.incrementAndGet() >
      64
    ) {
      count.decrementAndGet()
      throw IllegalStateException("[ASSET_LIMIT] Dispose an asset before opening more than 64 inputs.")
    }
  }

  private val size = file.length()
  private val modified = file.lastModified()
  private var closed = false
  private var users = 0

  @Synchronized fun borrow(): Closeable {
    check(!closed) { "[ASSET_CLOSED] Media asset has been disposed." }
    users++
    var returned = false
    return Closeable {
      synchronized(this) {
        if (!returned) {
          returned = true
          users--
          if (closed && users == 0) finishOwner()
        }
      }
    }
  }

  private fun finishOwner() {
    count.decrementAndGet()
    runCatching { closeOwner() }
  }

  fun validate() {
    check(file.isFile && file.length() == size && file.lastModified() == modified) {
      "[INPUT_CHANGED] Media source changed; reopen it."
    }
  }

  @Synchronized fun close() {
    if (!closed) {
      closed = true
      if (users == 0) finishOwner()
    }
  }
}
