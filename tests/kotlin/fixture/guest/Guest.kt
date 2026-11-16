package fixture.guest

import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary
import org.graalvm.polyglot.Context
import org.graalvm.polyglot.SandboxPolicy
import org.graalvm.polyglot.Value

class KGuest(
  val value: Value, // expect: TruffleHostInterop
  val context: org.graalvm.polyglot.Context, // expect: TruffleHostInterop
  val policy: SandboxPolicy,
)

fun wrap(x: Any): Any = org.graalvm.polyglot.Value.asValue(x) // expect: TruffleHostInterop

/** A reviewed crossing: suppressed where the reason lives, without hiding the other uses. */
@Suppress("TruffleHostInterop")
@TruffleBoundary
fun enter(): Any = Context.getCurrent()
