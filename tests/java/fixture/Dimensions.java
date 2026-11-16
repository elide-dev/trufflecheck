package fixture;

import com.oracle.truffle.api.CompilerDirectives.CompilationFinal;
import com.oracle.truffle.api.nodes.Node;

final class Dimensions extends Node {
  @CompilationFinal int[] table; // expect: TruffleCompilationFinalDimensions
  @CompilationFinal(dimensions = 1) int[] constant;
  @CompilationFinal(dimensions = 0) Object[] reference;
  @CompilationFinal int scalar;
}
