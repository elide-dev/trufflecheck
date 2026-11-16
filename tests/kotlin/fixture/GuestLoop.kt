package fixture

import com.oracle.truffle.api.TruffleSafepoint
import com.oracle.truffle.api.frame.VirtualFrame
import com.oracle.truffle.api.nodes.Node
import com.oracle.truffle.api.nodes.RepeatingNode

abstract class KBooleanNode : Node() {
  abstract fun executeBoolean(frame: VirtualFrame): Boolean
}

class KGuestLoop(@Child var condition: KBooleanNode, @Child var body: KBooleanNode) : Node() {
  fun handWritten(frame: VirtualFrame) {
    while (condition.executeBoolean(frame)) { // expect: TruffleGuestLoop
      body.executeBoolean(frame)
    }
  }

  fun polling(frame: VirtualFrame) {
    while (condition.executeBoolean(frame)) {
      body.executeBoolean(frame)
      TruffleSafepoint.poll(this)
    }
  }

  fun countdown(start: Int): Int {
    var n = start
    var total = 0
    while (n-- > 0) total += n
    return total
  }

  fun fib(n: Int): Int = if (n < 2) n else fib(n - 1) + fib(n - 2) // expect: TruffleFastPathRecursion expect: TruffleFastPathRecursion
}

class KRepeating(@Child var body: KBooleanNode) : Node(), RepeatingNode {
  override fun executeRepeating(frame: VirtualFrame): Boolean {
    while (body.executeBoolean(frame)) body.executeBoolean(frame)
    return false
  }
}
