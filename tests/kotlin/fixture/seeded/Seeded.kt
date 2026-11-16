package fixture.seeded

object KSeeded {
  val path: String? = System.getenv("PATH") // expect: TruffleHostValueInImageHeap
}
