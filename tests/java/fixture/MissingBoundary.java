package fixture;

import com.oracle.truffle.api.CompilerDirectives;
import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.nodes.Node;
import java.math.BigInteger;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

final class MissingBoundary extends Node {
  final Map<String, Object> map = new HashMap<>();
  final List<Object> list = List.of();

  Object lookup(String key) {
    return map.get(key); // expect: TruffleMissingBoundary
  }

  Object element(int index) {
    return list.get(index);
  }

  String format(Object value) {
    return String.format("%s", value); // expect: TruffleMissingBoundary
  }

  String describe(Object value) {
    return value.toString(); // expect: TruffleMissingBoundary
  }

  String concat(String name) {
    return "name: " + name; // expect: TruffleMissingBoundary
  }

  String constant() {
    return "a" + "b";
  }

  BigInteger add(BigInteger a, BigInteger b) {
    return a.add(b); // expect: TruffleMissingBoundary
  }

  int sign(BigInteger a) {
    return a.signum();
  }

  @TruffleBoundary
  String boundary(Object value) {
    return String.format("%s", value) + value;
  }

  String slow(Object value) {
    CompilerDirectives.transferToInterpreter();
    return String.format("%s", value);
  }

  /** Static methods of a node count too: they're usually called from its execute paths. */
  static String helper(Object value) {
    return value.toString(); // expect: TruffleMissingBoundary
  }

  static final class NotANode {
    String host(Object value) {
      return String.format("%s", value);
    }
  }
}
