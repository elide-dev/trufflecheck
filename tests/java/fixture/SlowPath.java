package fixture;

import com.oracle.truffle.api.CompilerDirectives;
import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.nodes.ControlFlowException;
import com.oracle.truffle.api.nodes.Node;

final class SlowPath extends Node {
  static final class Failure extends ControlFlowException {
    final String message;

    Failure(String message) {
      this.message = message;
    }
  }

  int inline(int value) {
    if (value < 0) {
      CompilerDirectives.transferToInterpreter();
      throw new Failure("negative: " + value); // expect: TruffleSlowPathInline
    }
    return value;
  }

  int extracted(int value) {
    if (value < 0) {
      CompilerDirectives.transferToInterpreter();
      throw failure(value);
    }
    return value;
  }

  @TruffleBoundary
  static Failure failure(int value) {
    return new Failure("negative: " + value);
  }
}
