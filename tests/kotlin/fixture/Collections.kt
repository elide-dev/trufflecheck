package fixture

import java.util.LinkedList
import java.util.Stack

fun linked(): MutableList<String> = LinkedList() // expect: TruffleForbiddenCollection

fun linkedReference(): () -> MutableList<String> = ::LinkedList // expect: TruffleForbiddenCollection

class KQueue : Stack<String>() // expect: TruffleForbiddenCollection

fun buffer(): StringBuffer = StringBuffer() // expect: TruffleForbiddenCollection

fun arrayList(): MutableList<String> = ArrayList()
