package fixture;

import com.oracle.truffle.api.CompilerAsserts;
import com.oracle.truffle.api.CompilerDirectives;
import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.HostCompilerDirectives;
import com.oracle.truffle.api.dsl.Specialization;
import com.oracle.truffle.api.interop.InteropLibrary;
import com.oracle.truffle.api.interop.TruffleObject;
import com.oracle.truffle.api.library.ExportLibrary;
import com.oracle.truffle.api.library.ExportMessage;
import com.oracle.truffle.api.nodes.Node;
import java.util.concurrent.locks.ReentrantLock;

final class Blocking {
  static final class Locking extends Node {
    private static final ThreadLocal<Object> LOCAL = new ThreadLocal<>();
    private final Object lock = new Object();
    private final ReentrantLock reentrant = new ReentrantLock();

    void monitor() {
      synchronized (lock) {} // expect: TruffleBlockingInNode
    }

    void locked() {
      reentrant.lock(); // expect: TruffleBlockingInNode
    }

    static Object local() {
      return LOCAL.get(); // expect: TruffleBlockingInNode
    }

    synchronized void whole() {} // expect: TruffleBlockingInNode

    @TruffleBoundary
    void boundary() {
      synchronized (lock) {}
    }

    void slowPath() {
      CompilerDirectives.transferToInterpreterAndInvalidate();
      synchronized (lock) {}
    }

    void guarded() {
      if (CompilerDirectives.inInterpreter()) {
        synchronized (lock) {}
      }
      if (!CompilerDirectives.inCompiledCode()) {
        synchronized (lock) {}
      }
    }

    void fastPathGuard() {
      if (HostCompilerDirectives.inInterpreterFastPath()) {
        synchronized (lock) {}
      }
      if (CompilerDirectives.inCompiledCode()) {
        return;
      } else {
        synchronized (lock) {}
      }
    }

    RuntimeException unreachable() {
      throw CompilerDirectives.shouldNotReachHere(String.valueOf(LOCAL.get()));
    }

    void never() {
      CompilerAsserts.neverPartOfCompilation();
      synchronized (lock) {}
    }

    Runnable deferred() {
      return () -> reentrant.lock();
    }
  }

  @ExportLibrary(InteropLibrary.class)
  static final class Exported implements TruffleObject {
    private final Object lock = new Object();

    @ExportMessage
    boolean hasMembers() {
      synchronized (lock) { return false; } // expect: TruffleBlockingInNode
    }

    void helper() {
      synchronized (lock) {}
    }
  }

  /** A Bytecode DSL-style operation: not a Node, but its specializations compile. */
  static final class Operation {
    @Specialization
    static int perform(Object lock) {
      synchronized (lock) { return 1; } // expect: TruffleBlockingInNode
    }
  }

  static final class Plain {
    void plain() {
      synchronized (this) {}
    }
  }
}
