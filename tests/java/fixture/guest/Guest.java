package fixture.guest;

import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.SandboxPolicy;
import org.graalvm.polyglot.Value;

final class Guest {
  Value value; // expect: TruffleHostInterop
  org.graalvm.polyglot.Context context; // expect: TruffleHostInterop
  SandboxPolicy policy;
  org.graalvm.polyglot.io.ByteSequence bytes;

  Object wrap(Object x) {
    return Value.asValue(x); // expect: TruffleHostInterop
  }

  /** A reviewed crossing: suppressed where the reason lives, without hiding the other uses. */
  @SuppressWarnings("TruffleHostInterop")
  @TruffleBoundary
  static Object enter() {
    return Context.getCurrent();
  }
}
