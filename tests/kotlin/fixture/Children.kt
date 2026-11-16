package fixture

import com.oracle.truffle.api.nodes.Node
import com.oracle.truffle.api.CompilerDirectives.CompilationFinal
import com.oracle.truffle.api.frame.VirtualFrame
import com.oracle.truffle.api.nodes.NodeInterface
import com.oracle.truffle.api.nodes.RootNode

class KLeaf : Node()

interface KExpression : NodeInterface

class KRoot : RootNode(null) {
  override fun execute(frame: VirtualFrame): Any = 0
}

class KParent(val reference: KLeaf) : Node() {
  var child: KLeaf? = null // expect: TruffleNodeChildren
  var children: Array<KLeaf>? = null // expect: TruffleNodeChildren
  var expression: KExpression? = null // expect: TruffleNodeChildren
  var grid: Array<Array<KLeaf>>? = null // expect: TruffleNodeChildren
  val list: List<KLeaf> = emptyList() // expect: TruffleNodeChildren
  @Child var adopted: KLeaf? = null
  @Children var adoptedAll: Array<KLeaf>? = null
  val fixed: KLeaf = reference
  @CompilationFinal var target: KRoot? = null
  var targets: Array<KRoot>? = null

  companion object {
    var shared: KLeaf? = null // expect: TruffleStaticContextState
  }
}

fun captures(captured: KLeaf): Node =
  object : Node() {
    fun use(): Any = captured // expect: TruffleNodeChildren
  }

fun callee(root: KRoot): Node =
  object : Node() {
    fun use(): Any = root
  }

fun owns(captured: KLeaf): Node =
  object : Node() {
    @Child var kept: KLeaf? = captured
  }
