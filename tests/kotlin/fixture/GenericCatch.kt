package fixture

import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary
import com.oracle.truffle.api.exception.AbstractTruffleException
import com.oracle.truffle.api.nodes.ControlFlowException
import com.oracle.truffle.api.nodes.Node

class KGenericCatch : Node() {
  fun swallow(body: Runnable): Any? {
    try {
      body.run()
    } catch (e: Exception) { // expect: TruffleGenericCatch
      return null
    }
    return body
  }

  fun passesThrough(body: Runnable): Any? {
    try {
      body.run()
    } catch (e: ControlFlowException) {
      throw e
    } catch (e: AbstractTruffleException) {
      throw e
    } catch (e: RuntimeException) {
      return null
    }
    return body
  }

  fun rethrows(body: Runnable): Any? {
    try {
      body.run()
    } catch (t: Throwable) {
      throw t
    }
    return body
  }

  @TruffleBoundary
  fun boundary(body: Runnable): Any? {
    try {
      body.run()
    } catch (e: Exception) {
      return null
    }
    return body
  }
}
