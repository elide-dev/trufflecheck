package fixture;

import com.oracle.truffle.api.interop.TruffleObject;

final class DeclaredType implements TruffleObject {
  TruffleObject field; // expect: TruffleDeclaredType
  Object fine;

  TruffleObject returned() { // expect: TruffleDeclaredType
    return this;
  }

  Object parameter(TruffleObject value) { // expect: TruffleDeclaredType
    return value;
  }
}
