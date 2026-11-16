package fixture;

import com.oracle.truffle.api.CompilerDirectives;
import com.oracle.truffle.api.CompilerDirectives.CompilationFinal;
import com.oracle.truffle.api.nodes.ControlFlowException;
import com.oracle.truffle.api.nodes.Node;

final class DeoptLoop extends Node {
  @CompilationFinal Object cached;
  @CompilationFinal boolean generic;

  static final class Failed extends ControlFlowException {}

  Object cycle(Object value) {
    if (value == null) {
      CompilerDirectives.transferToInterpreterAndInvalidate(); // expect: TruffleDeoptLoop
      throw new Failed();
    }
    return value;
  }

  Object respecialize(Object value) {
    if (value == null) {
      CompilerDirectives.transferToInterpreterAndInvalidate();
      generic = true;
      return null;
    }
    return value;
  }

  Object delegates(Object value) {
    CompilerDirectives.transferToInterpreterAndInvalidate();
    return respecialize(value);
  }

  Object unbounded(Object value) {
    if (cached != value) { // expect: TruffleDeoptLoop
      CompilerDirectives.transferToInterpreterAndInvalidate();
      cached = value;
    }
    return cached;
  }

  Object bounded(Object value) {
    if (!generic && cached != value) {
      CompilerDirectives.transferToInterpreterAndInvalidate();
      if (cached == null) {
        cached = value;
      } else {
        generic = true;
      }
    }
    return generic ? value : cached;
  }
}
