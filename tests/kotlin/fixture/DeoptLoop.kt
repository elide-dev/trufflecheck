package fixture

import com.oracle.truffle.api.CompilerDirectives
import com.oracle.truffle.api.CompilerDirectives.CompilationFinal
import com.oracle.truffle.api.nodes.ControlFlowException
import com.oracle.truffle.api.nodes.Node

class KFailed : ControlFlowException()

class KDeoptLoop : Node() {
  @CompilationFinal var cached: Any? = null
  @CompilationFinal var generic: Boolean = false

  fun cycle(value: Any?): Any {
    if (value == null) {
      CompilerDirectives.transferToInterpreterAndInvalidate() // expect: TruffleDeoptLoop
      throw KFailed()
    }
    return value
  }

  fun respecialize(value: Any?): Any? {
    if (value == null) {
      CompilerDirectives.transferToInterpreterAndInvalidate()
      generic = true
      return null
    }
    return value
  }

  fun unbounded(value: Any?): Any? {
    if (cached !== value) { // expect: TruffleDeoptLoop
      CompilerDirectives.transferToInterpreterAndInvalidate()
      cached = value
    }
    return cached
  }
}
