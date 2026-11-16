package fixture.seeded;

final class Seeded {
  static final String PATH = System.getenv("PATH"); // expect: TruffleHostValueInImageHeap
}

final class Lazy {
  static final String PATH = System.getenv("PATH");
}
