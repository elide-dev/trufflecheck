package fixture

import com.oracle.truffle.api.Assumption
import com.oracle.truffle.api.CompilerDirectives
import com.oracle.truffle.api.CompilerDirectives.CompilationFinal
import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary
import com.oracle.truffle.api.nodes.Node

class KCompilationFinalWrite : Node() {
  @CompilationFinal var cached: Int = 0
  @CompilationFinal(dimensions = 1) val table = IntArray(4)
  @CompilationFinal(dimensions = 0) val reference = IntArray(4)
  @CompilationFinal var stable: Assumption = Assumption.create()
  var plain: Int = 0

  init {
    cached = 1
  }

  fun unguarded(value: Int) {
    cached = value // expect: TruffleCompilationFinalWrite
  }

  fun element(value: Int) {
    table[0] = value // expect: TruffleCompilationFinalWrite
  }

  fun increment() {
    cached++ // expect: TruffleCompilationFinalWrite
  }

  fun beyondDimensions(value: Int) {
    reference[0] = value
  }

  fun transferOnly(value: Int) {
    CompilerDirectives.transferToInterpreter()
    cached = value // expect: TruffleCompilationFinalWrite
  }

  fun invalidated(value: Int) {
    CompilerDirectives.transferToInterpreterAndInvalidate()
    cached = value
  }

  fun guarded(value: Int) {
    if (CompilerDirectives.isPartialEvaluationConstant(this)) {
      CompilerDirectives.transferToInterpreterAndInvalidate()
    }
    cached = value
  }

  fun viaAssumption(value: Int) {
    stable.invalidate()
    cached = value
  }

  @TruffleBoundary
  fun boundary(value: Int) {
    cached = value
  }

  fun ordinary(value: Int) {
    plain = value
  }
}
