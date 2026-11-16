package fixture;

import com.oracle.truffle.api.TruffleSafepoint;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.Node;
import com.oracle.truffle.api.nodes.RepeatingNode;

final class GuestLoop extends Node {
  abstract static class Expression extends Node {
    abstract boolean executeBoolean(VirtualFrame frame);
  }

  @Child Expression condition;
  @Child Expression body;

  void handWritten(VirtualFrame frame) {
    while (condition.executeBoolean(frame)) { // expect: TruffleGuestLoop
      body.executeBoolean(frame);
    }
  }

  void polling(VirtualFrame frame) {
    while (condition.executeBoolean(frame)) {
      body.executeBoolean(frame);
      TruffleSafepoint.poll(this);
    }
  }

  int countdown(int n) {
    var total = 0;
    while (n-- > 0) {
      total += n;
    }
    return total;
  }

  int fib(int n) {
    return n < 2 ? n : fib(n - 1) + fib(n - 2); // expect: TruffleFastPathRecursion expect: TruffleFastPathRecursion
  }

  static final class Repeating extends Node implements RepeatingNode {
    @Child Expression body;

    @Override
    public boolean executeRepeating(VirtualFrame frame) {
      while (body.executeBoolean(frame)) {
        body.executeBoolean(frame);
      }
      return false;
    }
  }
}
