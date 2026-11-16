package fixture.heap;

final class Heap {
  static final String HOME = System.getProperty("user.home"); // expect: TruffleHostValueInImageHeap

  static String lazy() {
    return System.getProperty("user.home");
  }
}
