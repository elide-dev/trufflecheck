package fixture;

import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.exception.AbstractTruffleException;

final class BoundaryException {
  static final class GuestError extends AbstractTruffleException {
    GuestError(String message) {
      super(message);
    }
  }

  @TruffleBoundary
  static Object parse(String text) { // expect: TruffleBoundaryExceptionPolicy
    if (text.isEmpty()) {
      throw new GuestError("empty");
    }
    return text;
  }

  @TruffleBoundary(transferToInterpreterOnException = false)
  static Object parseQuietly(String text) {
    if (text.isEmpty()) {
      throw new GuestError("empty");
    }
    return text;
  }
}
