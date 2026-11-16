package fixture

import java.util.concurrent.atomic.AtomicIntegerFieldUpdater

class KFieldUpdater {
  @Volatile var count: Int = 0

  fun lazy(): Int {
    val updater = AtomicIntegerFieldUpdater.newUpdater(KFieldUpdater::class.java, "count") // expect: TruffleFieldUpdaterConstant
    return updater.incrementAndGet(this)
  }

  companion object {
    val COUNT: AtomicIntegerFieldUpdater<KFieldUpdater> = AtomicIntegerFieldUpdater.newUpdater(KFieldUpdater::class.java, "count")
  }
}
