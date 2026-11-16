package fixture;

import com.oracle.truffle.api.Assumption;
import com.oracle.truffle.api.CallTarget;
import com.oracle.truffle.api.CompilerDirectives.CompilationFinal;
import com.oracle.truffle.api.nodes.Node;
import com.oracle.truffle.api.profiles.BranchProfile;
import com.oracle.truffle.api.profiles.ConditionProfile;

final class NodeState extends Node {
  Assumption assumption; // expect: TruffleNonConstantNodeState
  BranchProfile profile = BranchProfile.create(); // expect: TruffleNonConstantNodeState
  CallTarget target; // expect: TruffleNonConstantNodeState
  @CompilationFinal Assumption stable;
  final ConditionProfile condition = ConditionProfile.create();
  static final BranchProfile SHARED = BranchProfile.create(); // expect: TruffleNonConstantNodeState
  static final ConditionProfile DISABLED = ConditionProfile.getUncached();
  Object other;

  static final class NotANode {
    Assumption assumption;
  }
}
