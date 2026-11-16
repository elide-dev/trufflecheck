package fixture

import com.oracle.truffle.api.CompilerAsserts
import com.oracle.truffle.api.CompilerDirectives
import com.oracle.truffle.api.frame.VirtualFrame
import com.oracle.truffle.api.nodes.ExplodeLoop
import com.oracle.truffle.api.nodes.ExplodeLoop.LoopExplosionKind
import com.oracle.truffle.api.nodes.Node

abstract class KExpressionNode : Node() {
  abstract fun execute(frame: VirtualFrame): Any
}

class KChildrenLoop(@Children private val arguments: Array<KExpressionNode>) : Node() {
  fun unexploded(frame: VirtualFrame): Array<Any?> {
    val values = arrayOfNulls<Any>(arguments.size)
    for (i in arguments.indices) { // expect: TruffleChildrenLoop
      values[i] = arguments[i].execute(frame)
    }
    arguments.forEach { it.execute(frame) } // expect: TruffleChildrenLoop
    return values
  }

  @ExplodeLoop
  fun exploded(frame: VirtualFrame): Array<Any?> {
    val values = arrayOfNulls<Any>(arguments.size)
    for (i in arguments.indices) {
      values[i] = arguments[i].execute(frame)
    }
    return values
  }

  @ExplodeLoop
  fun unbounded(values: IntArray): Int {
    var sum = 0
    for (value in values) { // expect: TruffleChildrenLoop
      sum += value
    }
    return sum
  }

  @ExplodeLoop
  fun asserted(values: IntArray): Int {
    CompilerAsserts.partialEvaluationConstant<Any>(values.size)
    var sum = 0
    for (value in values) {
      sum += value
    }
    return sum
  }

  @ExplodeLoop(kind = LoopExplosionKind.MERGE_EXPLODE) // expect: TruffleChildrenLoop
  fun unkeyed(start: Int): Int {
    var pc = start
    while (pc < 8) pc++
    return pc
  }

  @ExplodeLoop(kind = LoopExplosionKind.MERGE_EXPLODE)
  fun keyed(start: Int): Int {
    var pc = start
    while (pc < 8) pc = CompilerDirectives.mergeExplodeKey(pc) + 1
    return pc
  }
}
