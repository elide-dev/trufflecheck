package fixture.guest;

import org.graalvm.polyglot.SandboxPolicy;
import org.graalvm.polyglot.Value; // expect: TruffleHostInterop

final class Guest {
  Value value;
  org.graalvm.polyglot.Context context; // expect: TruffleHostInterop
  SandboxPolicy policy;
  org.graalvm.polyglot.io.ByteSequence bytes;
}
