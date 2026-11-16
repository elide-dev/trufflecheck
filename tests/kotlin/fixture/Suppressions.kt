package fixture

import com.oracle.truffle.api.nodes.Node // expect: TruffleDslSuppressions

@Suppress("truffle") // expect: TruffleDslSuppressions
class KBlanket : Node()

@SuppressWarnings("unused", "all")
class KAll : Node()

@Suppress("truffle-inlining")
class KSpecific : Node()
