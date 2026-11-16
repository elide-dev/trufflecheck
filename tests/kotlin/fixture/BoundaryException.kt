package fixture

import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary
import com.oracle.truffle.api.exception.AbstractTruffleException

class KGuestError(message: String) : AbstractTruffleException(message)

@TruffleBoundary // expect: TruffleBoundaryExceptionPolicy
fun parse(text: String): Any {
  if (text.isEmpty()) throw KGuestError("empty")
  return text
}

@TruffleBoundary(transferToInterpreterOnException = false)
fun parseQuietly(text: String): Any {
  if (text.isEmpty()) throw KGuestError("empty")
  return text
}
