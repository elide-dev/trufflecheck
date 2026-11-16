package fixture

import com.oracle.truffle.api.CompilerAsserts
import com.oracle.truffle.api.CompilerDirectives
import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary
import com.oracle.truffle.api.HostCompilerDirectives
import com.oracle.truffle.api.dsl.Specialization
import com.oracle.truffle.api.interop.InteropLibrary
import com.oracle.truffle.api.interop.TruffleObject
import com.oracle.truffle.api.library.ExportLibrary
import com.oracle.truffle.api.library.ExportMessage
import com.oracle.truffle.api.nodes.Node
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

class Locking : Node() {
  private val lock = Any()
  private val reentrant = ReentrantLock()

  @get:Synchronized val guarded: Int = 0 // expect: TruffleBlockingInNode

  fun monitor(): Int = synchronized(lock) { 1 } // expect: TruffleBlockingInNode

  fun locked() {
    reentrant.lock() // expect: TruffleBlockingInNode
  }

  fun scoped(): Int = reentrant.withLock { 1 } // expect: TruffleBlockingInNode

  @Synchronized fun whole() {} // expect: TruffleBlockingInNode

  @TruffleBoundary
  fun boundary(): Int = synchronized(lock) { 1 }

  fun slowPath(): Int {
    CompilerDirectives.transferToInterpreterAndInvalidate()
    return synchronized(lock) { 1 }
  }

  fun interpreted() {
    if (CompilerDirectives.inInterpreter()) {
      reentrant.lock()
    }
  }

  fun fastPathGuard() {
    if (HostCompilerDirectives.inInterpreterFastPath()) {
      reentrant.lock()
    }
    if (CompilerDirectives.inCompiledCode()) {
      return
    } else {
      reentrant.lock()
    }
  }

  fun unreachable(): RuntimeException = throw CompilerDirectives.shouldNotReachHere(LOCAL_NAME.get().toString())

  fun deferred() = CompilerDirectives.interpreterOnly(Runnable { reentrant.lock() })

  fun never() {
    CompilerAsserts.neverPartOfCompilation()
    reentrant.lock()
  }

  companion object {
    private val LOCAL = ThreadLocal<Any>()
    private val LOCAL_NAME = ThreadLocal<Any>()

    fun local(): Any? = LOCAL.get() // expect: TruffleBlockingInNode
  }
}

@ExportLibrary(InteropLibrary::class)
class Exported : TruffleObject {
  private val lock = Any()

  @ExportMessage
  fun hasMembers(): Boolean = synchronized(lock) { false } // expect: TruffleBlockingInNode

  fun helper(): Int = synchronized(lock) { 1 }
}

object Operation {
  @Specialization
  @JvmStatic
  fun perform(lock: Any): Int = synchronized(lock) { 1 } // expect: TruffleBlockingInNode
}

class Plain {
  fun plain(): Int = synchronized(this) { 1 }
}
