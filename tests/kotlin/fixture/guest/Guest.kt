package fixture.guest

import org.graalvm.polyglot.SandboxPolicy
import org.graalvm.polyglot.Value // expect: TruffleHostInterop

class KGuest(
  val value: Value,
  val context: org.graalvm.polyglot.Context, // expect: TruffleHostInterop
  val policy: SandboxPolicy,
)

fun wrap(x: Any): Any = org.graalvm.polyglot.Value.asValue(x) // expect: TruffleHostInterop
