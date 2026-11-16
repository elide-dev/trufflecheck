package fixture;

import com.oracle.truffle.api.CallTarget;
import com.oracle.truffle.api.ContextThreadLocal;
import com.oracle.truffle.api.TruffleLanguage;
import com.oracle.truffle.api.nodes.Node;
import com.oracle.truffle.api.nodes.UnadoptableNode;

final class StaticContextState {
  static TruffleLanguage.Env env; // expect: TruffleStaticContextState
  static CallTarget target; // expect: TruffleStaticContextState
  static final ThreadLocal<Object> LOCAL = new ThreadLocal<>(); // expect: TruffleStaticContextState
  static ContextThreadLocal<Object> contextLocal;
  static final TruffleLanguage.ContextReference<Object> CONTEXT = null;
  static final Shared SHARED = new Shared();
  static final Adoptable ADOPTABLE = new Adoptable(); // expect: TruffleStaticContextState
  TruffleLanguage.Env instanceEnv;

  static final class Shared extends Node implements UnadoptableNode {}

  static final class Adoptable extends Node {}
}
