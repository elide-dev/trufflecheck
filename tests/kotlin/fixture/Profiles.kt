package fixture

import com.oracle.truffle.api.CallTarget
import com.oracle.truffle.api.dsl.Specialization
import com.oracle.truffle.api.interop.InteropLibrary
import com.oracle.truffle.api.nodes.Node
import com.oracle.truffle.api.profiles.BranchProfile

abstract class KProfiles : Node() {
  val legacy: BranchProfile = BranchProfile.create() // expect: TruffleLegacyProfile

  abstract fun execute(value: Any): Any

  @Specialization
  fun doIt(value: Any): Any {
    if (InteropLibrary.getUncached().isNull(value)) { // expect: TruffleUncachedOnFastPath
      legacy.enter()
    }
    return value
  }
}

class KPlainCaller : Node() {
  val handWritten: BranchProfile = BranchProfile.create()

  fun invoke(target: CallTarget): Any = target.call() // expect: TruffleUncachedOnFastPath
}
