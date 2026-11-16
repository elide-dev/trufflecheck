package fixture.heap

object Heap {
  val home: String? = System.getProperty("user.home") // expect: TruffleHostValueInImageHeap
}
