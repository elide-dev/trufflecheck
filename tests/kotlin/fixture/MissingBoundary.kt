package fixture

import com.oracle.truffle.api.CompilerDirectives
import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary
import com.oracle.truffle.api.nodes.Node
import java.math.BigInteger

class KMissingBoundary : Node() {
  private val map = HashMap<String, Any>()
  private val list: List<Any> = emptyList()

  fun lookup(key: String): Any? = map[key] // expect: TruffleMissingBoundary

  fun element(index: Int): Any = list[index]

  fun format(value: Any): String = String.format("%s", value) // expect: TruffleMissingBoundary

  fun describe(value: Any): String = value.toString() // expect: TruffleMissingBoundary

  fun interpolate(name: String): String = "name: $name" // expect: TruffleMissingBoundary

  fun constant(): String = "plain"

  fun add(a: BigInteger, b: BigInteger): BigInteger = a.add(b) // expect: TruffleMissingBoundary

  fun sign(a: BigInteger): Int = a.signum()

  @TruffleBoundary
  fun boundary(value: Any): String = String.format("%s", value) + "$value"

  fun slow(value: Any): String {
    CompilerDirectives.transferToInterpreter()
    return String.format("%s", value)
  }
}

class KNotANode {
  fun host(value: Any): String = "$value".format()
}
