package fixture;

import com.oracle.truffle.api.CompilerDirectives.CompilationFinal;
import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.nodes.Node;
import java.util.LinkedList;
import java.util.List;

/** Each marked line must produce exactly the named finding; nothing else may. */
final class Nodes {
  static final class Leaf extends Node {}

  static final class Parent extends Node {
    Leaf child; // expect: TruffleNodeChildren
    Leaf[] children; // expect: TruffleNodeChildren
    @Child Leaf adopted;
    @Children Leaf[] adoptedAll;
    @CompilationFinal int[] table; // expect: TruffleCompilationFinalDimensions
    @CompilationFinal(dimensions = 1) int[] constant;
    private final Object lock = new Object();

    void blocking() {
      synchronized (lock) {} // expect: TruffleBlockingInNode
    }

    @TruffleBoundary
    void boundary() {
      synchronized (lock) {}
    }
  }

  static RuntimeException stackful() {
    return new RuntimeException("x"); // expect: TruffleStackfulException
  }

  static final class Stackless extends RuntimeException {
    Stackless() {
      super("x", null, false, false);
    }
  }

  static List<String> list() {
    return new LinkedList<>(); // expect: TruffleForbiddenCollection
  }
}
