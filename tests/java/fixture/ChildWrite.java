package fixture;

import com.oracle.truffle.api.nodes.Node;

final class ChildWrite extends Node {
  static final class Leaf extends Node {}

  @Child Leaf child;
  @Children final Leaf[] children = new Leaf[2];

  ChildWrite(Leaf initial) {
    child = initial;
  }

  void swap(Leaf next) {
    child = next; // expect: TruffleChildWriteWithoutInsert
  }

  void adopt(Leaf next) {
    child = insert(next);
  }

  void clear() {
    child = null;
  }

  void element(Leaf next) {
    children[0] = next; // expect: TruffleChildWriteWithoutInsert
  }

  void adoptElement(Leaf next) {
    children[1] = insert(next);
  }
}
