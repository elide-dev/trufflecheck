package fixture

import com.oracle.truffle.api.nodes.Node

class KHazards(@Child val fixed: KLeaf) : Node() { // expect: TruffleKotlinNodeHazards
  val computed: Int by lazy { 42 } // expect: TruffleKotlinNodeHazards
  @Child val declared: KLeaf = KLeaf() // expect: TruffleKotlinNodeHazards
  @Child var adopted: KLeaf? = null

  fun spread(values: IntArray): Int = sum(*values) // expect: TruffleKotlinNodeHazards

  private fun sum(vararg values: Int): Int = values.size
}
