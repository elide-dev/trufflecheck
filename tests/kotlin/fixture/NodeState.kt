package fixture

import com.oracle.truffle.api.Assumption
import com.oracle.truffle.api.CallTarget
import com.oracle.truffle.api.CompilerDirectives.CompilationFinal
import com.oracle.truffle.api.nodes.Node
import com.oracle.truffle.api.profiles.BranchProfile
import com.oracle.truffle.api.profiles.ConditionProfile

class KNodeState : Node() {
  var assumption: Assumption? = null // expect: TruffleNonConstantNodeState
  var profile: BranchProfile = BranchProfile.create() // expect: TruffleNonConstantNodeState
  var target: CallTarget? = null // expect: TruffleNonConstantNodeState
  @CompilationFinal var stable: Assumption? = null
  val condition: ConditionProfile = ConditionProfile.create()
  var other: Any? = null

  companion object {
    val SHARED: BranchProfile = BranchProfile.create() // expect: TruffleNonConstantNodeState
    val DISABLED: ConditionProfile = ConditionProfile.getUncached()
  }
}

class KNotANodeState {
  var assumption: Assumption? = null
}
