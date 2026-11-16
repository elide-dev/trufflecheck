package fixture

import com.oracle.truffle.api.CallTarget
import com.oracle.truffle.api.ContextThreadLocal
import com.oracle.truffle.api.TruffleLanguage
import com.oracle.truffle.api.nodes.Node
import com.oracle.truffle.api.nodes.UnadoptableNode

class KSharedNode : Node(), UnadoptableNode

class KAdoptableNode : Node()

object KStaticContextState {
  var env: TruffleLanguage.Env? = null // expect: TruffleStaticContextState
  var target: CallTarget? = null // expect: TruffleStaticContextState
  var contextLocal: ContextThreadLocal<Any>? = null
  val shared = KSharedNode()
  val adoptable = KAdoptableNode() // expect: TruffleStaticContextState
}

val topLevelLocal = ThreadLocal<Any>() // expect: TruffleStaticContextState

class KInstanceState(val env: TruffleLanguage.Env)
