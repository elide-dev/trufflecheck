package fixture;

import java.util.LinkedList;
import java.util.List;
import java.util.Stack;
import java.util.function.Supplier;

final class Collections {
  static List<String> linked() {
    return new LinkedList<>(); // expect: TruffleForbiddenCollection
  }

  static Supplier<List<String>> reference() {
    return LinkedList::new; // expect: TruffleForbiddenCollection
  }

  static final class Queue extends Stack<String> {} // expect: TruffleForbiddenCollection

  static StringBuffer buffer() {
    return new StringBuffer(); // expect: TruffleForbiddenCollection
  }

  static List<String> array() {
    return new java.util.ArrayList<>();
  }
}
