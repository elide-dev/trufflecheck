package fixture;

import com.oracle.truffle.api.CompilerDirectives.CompilationFinal;
import com.oracle.truffle.api.HostCompilerDirectives.BytecodeInterpreterSwitch;
import com.oracle.truffle.api.nodes.Node;

final class BytecodeSwitch extends Node {
  @CompilationFinal(dimensions = 1) final byte[] code = {0, 1, 2};

  int interpret() { // expect: TruffleBytecodeSwitch
    int bci = 0;
    int acc = 0;
    while (bci < code.length) {
      int op = code[bci];
      switch (op) {
        case 0 -> acc++;
        case 1 -> acc--;
        default -> acc = 0;
      }
      bci++;
    }
    return acc;
  }

  @BytecodeInterpreterSwitch
  int annotated() {
    int bci = 0;
    int acc = 0;
    while (bci < code.length) {
      switch (code[bci]) {
        case 0 -> acc++;
        default -> acc = 0;
      }
      bci++;
    }
    return acc;
  }
}
