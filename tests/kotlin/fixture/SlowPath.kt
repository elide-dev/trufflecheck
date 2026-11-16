package fixture

import com.oracle.truffle.api.CompilerDirectives
import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary
import com.oracle.truffle.api.nodes.ControlFlowException
import com.oracle.truffle.api.nodes.Node

class KFailure(val text: String) : ControlFlowException()

class KSlowPath : Node() {
  fun inline(value: Int): Int {
    if (value < 0) {
      CompilerDirectives.transferToInterpreter()
      throw KFailure("negative: $value") // expect: TruffleSlowPathInline
    }
    return value
  }

  fun extracted(value: Int): Int {
    if (value < 0) {
      CompilerDirectives.transferToInterpreter()
      throw failure(value)
    }
    return value
  }

  @TruffleBoundary
  fun failure(value: Int): KFailure = KFailure("negative: $value")
}
