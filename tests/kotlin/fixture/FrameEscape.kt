package fixture

import com.oracle.truffle.api.Truffle
import com.oracle.truffle.api.frame.Frame
import com.oracle.truffle.api.frame.FrameInstance.FrameAccess
import com.oracle.truffle.api.frame.MaterializedFrame
import com.oracle.truffle.api.frame.VirtualFrame
import com.oracle.truffle.api.nodes.Node
import java.util.function.Supplier

class KFrameEscape : Node() {
  var stored: VirtualFrame? = null // expect: TruffleFrameEscape
  var kept: Any? = null
  var materialized: MaterializedFrame? = null

  fun store(frame: VirtualFrame) {
    kept = frame // expect: TruffleFrameEscape
  }

  fun materialize(frame: VirtualFrame) {
    materialized = frame.materialize()
  }

  fun capture(frame: VirtualFrame): Supplier<Any> = Supplier { frame.arguments } // expect: TruffleFrameEscape

  fun inline(frame: VirtualFrame): Int = frame.arguments.count { it != null }

  fun anonymous(frame: VirtualFrame): Any =
    object {
      override fun toString(): String = frame.arguments.size.toString() // expect: TruffleFrameEscape
    }

  fun walk(): Frame? = Truffle.getRuntime().iterateFrames { it.getFrame(FrameAccess.READ_WRITE) } // expect: TruffleFrameEscape

  fun readOnly(): Frame? = Truffle.getRuntime().iterateFrames { it.getFrame(FrameAccess.READ_ONLY) }
}
