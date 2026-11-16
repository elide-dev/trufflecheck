package fixture;

import com.oracle.truffle.api.CompilerAsserts;
import com.oracle.truffle.api.CompilerDirectives;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.ExplodeLoop;
import com.oracle.truffle.api.nodes.ExplodeLoop.LoopExplosionKind;
import com.oracle.truffle.api.nodes.Node;

final class ChildrenLoop extends Node {
  abstract static class Expression extends Node {
    abstract Object execute(VirtualFrame frame);
  }

  @Children final Expression[] arguments;

  ChildrenLoop(Expression[] arguments) {
    this.arguments = arguments;
  }

  Object[] unexploded(VirtualFrame frame) {
    var values = new Object[arguments.length];
    for (int i = 0; i < arguments.length; i++) { // expect: TruffleChildrenLoop
      values[i] = arguments[i].execute(frame);
    }
    for (Expression argument : arguments) { // expect: TruffleChildrenLoop
      argument.execute(frame);
    }
    return values;
  }

  @ExplodeLoop
  Object[] exploded(VirtualFrame frame) {
    var values = new Object[arguments.length];
    for (int i = 0; i < arguments.length; i++) {
      values[i] = arguments[i].execute(frame);
    }
    return values;
  }

  @ExplodeLoop
  int unbounded(int[] values, int count) {
    var sum = 0;
    for (int i = 0; i < count; i++) { // expect: TruffleChildrenLoop
      sum += values[i];
    }
    for (int value : values) { // expect: TruffleChildrenLoop
      sum += value;
    }
    return sum;
  }

  @ExplodeLoop
  int asserted(int[] values) {
    CompilerAsserts.partialEvaluationConstant(values.length);
    var sum = 0;
    for (int value : values) {
      sum += value;
    }
    return sum;
  }

  @ExplodeLoop(kind = LoopExplosionKind.MERGE_EXPLODE)
  int unkeyed(int start) { // expect: TruffleChildrenLoop
    int pc = start;
    while (pc < 8) {
      pc++;
    }
    return pc;
  }

  @ExplodeLoop(kind = LoopExplosionKind.MERGE_EXPLODE)
  int keyed(int start) {
    int pc = start;
    while (pc < 8) {
      pc = CompilerDirectives.mergeExplodeKey(pc) + 1;
    }
    return pc;
  }
}
