package com.margelo.nitro.nitromediakit

import java.io.Closeable
import java.lang.ref.PhantomReference
import java.lang.ref.ReferenceQueue
import java.util.concurrent.ConcurrentHashMap

/** GC fallback without Java finalizers. Cleanup records never reference their HybridObject. */
internal object AssetReaper {
  private val queue = ReferenceQueue<Any>()
  private val records = ConcurrentHashMap<PhantomReference<Any>, AssetStorage>()

  init {
    Thread({
      while (true) {
        val reference = queue.remove()
        records.remove(reference)?.let { runCatching { it.close() } }
        reference.clear()
      }
    }, "NitroMediaKit.assets").apply {
      isDaemon = true
      start()
    }
  }

  fun watch(
    target: Any,
    storage: AssetStorage,
  ): Closeable {
    val reference = PhantomReference(target, queue)
    records[reference] = storage
    return Closeable {
      records.remove(reference)?.close()
      reference.clear()
    }
  }
}
