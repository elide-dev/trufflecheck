package fixture

import com.oracle.truffle.api.nodes.ControlFlowException

fun plainException(): RuntimeException = RuntimeException("x") // expect: TruffleStackfulException

fun arithmetic(): ArithmeticException = ArithmeticException() // expect: TruffleStackfulException

fun validated(value: Int): Int {
  require(value > 0) // expect: TruffleStackfulException
  return value
}

fun failing(): Nothing = error("x") // expect: TruffleStackfulException

fun anonymousStackless(): RuntimeException =
  object : RuntimeException("x") {
    override fun fillInStackTrace(): Throwable = this
  }

fun anonymousStackful(): RuntimeException = object : RuntimeException("x") {} // expect: TruffleStackfulException

class KMine : RuntimeException("x") // expect: TruffleStackfulException

class KStackless : RuntimeException("x", null, false, false)

open class KOverrides : RuntimeException() {
  override fun fillInStackTrace(): Throwable = this
}

class KInherits : KOverrides()

class KControl : ControlFlowException()

fun project(): RuntimeException = KMine()
