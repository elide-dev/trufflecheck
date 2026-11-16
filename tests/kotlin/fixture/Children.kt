package fixture

import com.oracle.truffle.api.nodes.Node
import com.oracle.truffle.api.nodes.NodeInterface

class KLeaf : Node()

interface KExpression : NodeInterface

class KParent(val reference: KLeaf) : Node() {
  var child: KLeaf? = null // expect: TruffleNodeChildren
  var children: Array<KLeaf>? = null // expect: TruffleNodeChildren
  var expression: KExpression? = null // expect: TruffleNodeChildren
  var grid: Array<Array<KLeaf>>? = null // expect: TruffleNodeChildren
  val list: List<KLeaf> = emptyList() // expect: TruffleNodeChildren
  @Child var adopted: KLeaf? = null
  @Children var adoptedAll: Array<KLeaf>? = null
  val fixed: KLeaf = reference

  companion object {
    var shared: KLeaf? = null
  }
}

fun captures(captured: KLeaf): Node =
  object : Node() {
    fun use(): Any = captured // expect: TruffleNodeChildren
  }

fun owns(captured: KLeaf): Node =
  object : Node() {
    @Child var kept: KLeaf? = captured
  }
