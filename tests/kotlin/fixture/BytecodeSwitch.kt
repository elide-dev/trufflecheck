package fixture

import com.oracle.truffle.api.CompilerDirectives.CompilationFinal
import com.oracle.truffle.api.HostCompilerDirectives.BytecodeInterpreterSwitch
import com.oracle.truffle.api.nodes.Node

class KBytecodeSwitch : Node() {
  @CompilationFinal(dimensions = 1) private val code = byteArrayOf(0, 1, 2)

  fun interpret(): Int { // expect: TruffleBytecodeSwitch
    var bci = 0
    var acc = 0
    while (bci < code.size) {
      val op = code[bci]
      when (op.toInt()) {
        0 -> acc++
        else -> acc = 0
      }
      when (code[bci]) {
        1.toByte() -> acc--
        else -> {}
      }
      bci++
    }
    return acc
  }

  @BytecodeInterpreterSwitch
  fun annotated(): Int {
    var bci = 0
    var acc = 0
    while (bci < code.size) {
      when (code[bci]) {
        0.toByte() -> acc++
        else -> acc = 0
      }
      bci++
    }
    return acc
  }
}
