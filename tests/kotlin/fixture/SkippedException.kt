package fixture

import com.oracle.truffle.api.CompilerDirectives
import com.oracle.truffle.api.nodes.Node
import com.oracle.truffle.api.nodes.UnexpectedResultException

class KSkippedException : Node() {
  fun checked(value: Int): Int {
    if (value < 0) {
      throw IllegalArgumentException("negative") // expect: TruffleSkippedException expect: TruffleStackfulException
    }
    return value
  }

  fun validated(value: Int): Int {
    require(value >= 0) // expect: TruffleSkippedException expect: TruffleStackfulException
    return value
  }

  fun executeInt(value: Any): Int {
    if (value is Int) return value
    throw UnexpectedResultException(value)
  }

  fun slowPath(value: Int): Int {
    if (value < 0) {
      CompilerDirectives.transferToInterpreter()
      throw IllegalStateException("negative") // expect: TruffleStackfulException
    }
    return value
  }
}
