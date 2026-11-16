package fixture;

import com.oracle.truffle.api.TruffleLanguage;
import com.oracle.truffle.api.nodes.Node;

final class Deprecated extends Node {
  Object cost() {
    return getCost(); // expect: TruffleDeprecatedApi
  }

  Thread thread(TruffleLanguage.Env env, Runnable body) {
    return env.createThread(body); // expect: TruffleDeprecatedApi
  }

  Thread modern(TruffleLanguage.Env env, Runnable body) {
    return env.newTruffleThreadBuilder(body).build();
  }
}
