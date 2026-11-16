package fixture

import com.oracle.truffle.api.interop.TruffleObject

class KDeclaredType : TruffleObject {
  var field: TruffleObject? = null // expect: TruffleDeclaredType
  var fine: Any? = null

  fun returned(): TruffleObject = this // expect: TruffleDeclaredType

  fun parameter(value: TruffleObject): Any = value // expect: TruffleDeclaredType
}
