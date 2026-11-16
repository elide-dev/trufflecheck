@file:JvmName("HeapValues")

package fixture.heap

import java.util.Random

val home: String? = System.getProperty("user.home") // expect: TruffleHostValueInImageHeap

object KHeap {
  val random = Random() // expect: TruffleHostValueInImageHeap
  val clock = KClock() // expect: TruffleHostValueInImageHeap
  val started = startedAt() // expect: TruffleHostValueInImageHeap
  val lazy: () -> Long = { System.nanoTime() }

  init {
    val now = System.currentTimeMillis() // expect: TruffleHostValueInImageHeap
  }

  fun later(): String? = System.getProperty("user.home")

  fun startedAt(): Long = System.nanoTime()
}

class KClock {
  val start = System.nanoTime()
}

enum class KMode(val stamp: Long) {
  FAST(System.nanoTime()), // expect: TruffleHostValueInImageHeap
}

enum class KStamp {
  NOW; // expect: TruffleHostValueInImageHeap

  val at = System.nanoTime()
}
