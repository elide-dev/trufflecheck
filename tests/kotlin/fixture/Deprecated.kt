package fixture

import com.oracle.truffle.api.TruffleLanguage
import com.oracle.truffle.api.nodes.Node

class KDeprecated : Node() {
  fun cost(): Any = getCost() // expect: TruffleDeprecatedApi

  fun thread(env: TruffleLanguage.Env, body: Runnable): Thread = env.createThread(body) // expect: TruffleDeprecatedApi

  fun modern(env: TruffleLanguage.Env, body: Runnable): Thread = env.newTruffleThreadBuilder(body).build()
}
