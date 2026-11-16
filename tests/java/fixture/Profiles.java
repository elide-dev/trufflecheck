package fixture;

import com.oracle.truffle.api.CallTarget;
import com.oracle.truffle.api.dsl.Specialization;
import com.oracle.truffle.api.interop.InteropLibrary;
import com.oracle.truffle.api.nodes.Node;
import com.oracle.truffle.api.profiles.BranchProfile;

abstract class Profiles extends Node {
  final BranchProfile legacy = BranchProfile.create(); // expect: TruffleLegacyProfile

  abstract Object execute(Object value);

  @Specialization
  Object doIt(Object value) {
    if (InteropLibrary.getUncached().isNull(value)) { // expect: TruffleUncachedOnFastPath
      legacy.enter();
    }
    return value;
  }

  static final class Plain extends Node {
    final BranchProfile handWritten = BranchProfile.create();

    Object call(CallTarget target) {
      return target.call(); // expect: TruffleUncachedOnFastPath
    }
  }
}
