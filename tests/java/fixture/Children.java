package fixture;

import com.oracle.truffle.api.CompilerDirectives.CompilationFinal;
import com.oracle.truffle.api.nodes.Node;
import com.oracle.truffle.api.nodes.NodeInterface;
import com.oracle.truffle.api.nodes.RootNode;
import java.util.List;

final class Children {
  static final class Leaf extends Node {}

  interface Expression extends NodeInterface {}

  abstract static class Root extends RootNode {
    Root() {
      super(null);
    }
  }

  static final class Parent extends Node {
    Leaf child; // expect: TruffleNodeChildren
    Leaf[] children; // expect: TruffleNodeChildren
    Expression expression; // expect: TruffleNodeChildren
    Leaf[][] grid; // expect: TruffleNodeChildren
    List<Leaf> list; // expect: TruffleNodeChildren
    final List<? extends Node> fixedList = List.of(); // expect: TruffleNodeChildren
    @Child Leaf adopted;
    @Children Leaf[] adoptedAll;
    @Child Expression adoptedExpression;
    final Leaf reference;
    @CompilationFinal Root target;
    Root[] targets;
    static Leaf shared; // expect: TruffleStaticContextState

    Parent(Leaf reference) {
      this.reference = reference;
    }
  }

  static Node anonymous(Leaf captured) {
    return new Node() {
      Object use() {
        return captured; // expect: TruffleNodeChildren
      }
    };
  }

  static Node local(Leaf captured) {
    class Local extends Node {
      Object use() {
        return captured; // expect: TruffleNodeChildren
      }
    }
    return new Local();
  }

  static Node callee(Root root) {
    return new Node() {
      Object use() {
        return root;
      }
    };
  }

  static Node owned(Leaf captured) {
    return new Node() {
      @Child Leaf kept = captured;
    };
  }
}
