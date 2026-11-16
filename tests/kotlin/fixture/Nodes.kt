package fixture

import com.oracle.truffle.api.nodes.Node
import java.util.LinkedList

class Blocking : Node() {
  private val lock = Any()

  fun blocking(): Int = synchronized(lock) { 1 } // expect: TruffleBlockingInNode
}

fun stackful(): RuntimeException = RuntimeException("x") // expect: TruffleStackfulException

fun list(): List<String> = LinkedList() // expect: TruffleForbiddenCollection
