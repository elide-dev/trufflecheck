package fixture

import com.oracle.truffle.api.CompilerDirectives.CompilationFinal
import com.oracle.truffle.api.nodes.Node

class KDimensions : Node() {
  @CompilationFinal var table: IntArray = IntArray(0) // expect: TruffleCompilationFinalDimensions
  @CompilationFinal(dimensions = 1) var constant: IntArray = IntArray(0)
  @CompilationFinal var scalar: Int = 0
}
