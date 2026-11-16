package fixture;

import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.exception.AbstractTruffleException;
import com.oracle.truffle.api.nodes.ControlFlowException;
import com.oracle.truffle.api.nodes.Node;

final class GenericCatch extends Node {
  Object swallow(Runnable body) {
    try {
      body.run();
    } catch (Exception e) { // expect: TruffleGenericCatch
      return null;
    }
    return body;
  }

  Object passesThrough(Runnable body) {
    try {
      body.run();
    } catch (ControlFlowException | AbstractTruffleException e) {
      throw e;
    } catch (RuntimeException e) {
      return null;
    }
    return body;
  }

  Object rethrows(Runnable body) {
    try {
      body.run();
    } catch (Throwable t) {
      throw t;
    }
    return body;
  }

  Object specific(Runnable body) {
    try {
      body.run();
    } catch (IllegalStateException e) {
      return null;
    }
    return body;
  }

  @TruffleBoundary
  Object boundary(Runnable body) {
    try {
      body.run();
    } catch (Exception e) {
      return null;
    }
    return body;
  }
}
