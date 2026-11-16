package fixture;

import com.oracle.truffle.api.nodes.Node;

final class Suppressions {
  @SuppressWarnings("truffle") // expect: TruffleDslSuppressions
  static final class Blanket extends Node {}

  @SuppressWarnings({"unused", "all"}) // expect: TruffleDslSuppressions
  static final class All extends Node {}

  @SuppressWarnings("truffle-inlining")
  static final class Specific extends Node {}
}
