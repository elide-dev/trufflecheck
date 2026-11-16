package fixture;

import com.oracle.truffle.api.CompilerDirectives;
import com.oracle.truffle.api.nodes.Node;
import com.oracle.truffle.api.nodes.UnexpectedResultException;

final class SkippedException extends Node {
  int checked(int value) {
    if (value < 0) {
      throw new IllegalArgumentException("negative"); // expect: TruffleSkippedException expect: TruffleStackfulException
    }
    return value;
  }

  int executeInt(Object value) throws UnexpectedResultException {
    if (value instanceof Integer i) {
      return i;
    }
    throw new UnexpectedResultException(value);
  }

  int slowPath(int value) {
    if (value < 0) {
      CompilerDirectives.transferToInterpreter();
      throw new IllegalStateException("negative"); // expect: TruffleStackfulException
    }
    return value;
  }

  static int host(int value) {
    return value;
  }
}
