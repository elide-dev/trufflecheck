package fixture

import com.oracle.truffle.api.nodes.Node

class KChildLeaf : Node()

class KChildWrite(initial: KChildLeaf) : Node() {
  @Child var child: KChildLeaf? = initial
  @Children val children: Array<KChildLeaf?> = arrayOfNulls(2)

  fun swap(next: KChildLeaf) {
    child = next // expect: TruffleChildWriteWithoutInsert
  }

  fun adopt(next: KChildLeaf) {
    child = insert(next)
  }

  fun clear() {
    child = null
  }

  fun element(next: KChildLeaf) {
    children[0] = next // expect: TruffleChildWriteWithoutInsert
  }

  fun adoptElement(next: KChildLeaf) {
    children[1] = insert(next)
  }
}
