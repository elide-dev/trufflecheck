package fixture;

import com.oracle.truffle.api.nodes.ControlFlowException;

final class Stackful {
  static RuntimeException plain() {
    return new RuntimeException("x"); // expect: TruffleStackfulException
  }

  static ArithmeticException arithmetic() {
    return new ArithmeticException(); // expect: TruffleStackfulException
  }

  static RuntimeException anonymousStackless() {
    return new RuntimeException("x") {
      @Override
      public synchronized Throwable fillInStackTrace() {
        return this;
      }
    };
  }

  static final class Mine extends RuntimeException { // expect: TruffleStackfulException
    Mine() {
      super("x");
    }
  }

  static final class Implicit extends IllegalStateException {} // expect: TruffleStackfulException

  static final class Stackless extends RuntimeException {
    Stackless() {
      super("x", null, false, false);
    }

    Stackless(String message) {
      this();
    }
  }

  static class Overrides extends RuntimeException {
    @Override
    public synchronized Throwable fillInStackTrace() {
      return this;
    }
  }

  static final class Inherits extends Overrides {}

  static final class Control extends ControlFlowException {}

  static RuntimeException project() {
    return new Mine();
  }

  static RuntimeException exempt() {
    return new Stackless();
  }
}
