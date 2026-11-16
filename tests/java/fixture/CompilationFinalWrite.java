package fixture;

import com.oracle.truffle.api.Assumption;
import com.oracle.truffle.api.CompilerDirectives;
import com.oracle.truffle.api.CompilerDirectives.CompilationFinal;
import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.nodes.Node;

final class CompilationFinalWrite extends Node {
  @CompilationFinal int cached;
  @CompilationFinal(dimensions = 1) int[] table = new int[4];
  @CompilationFinal(dimensions = 0) int[] reference = new int[4];
  @CompilationFinal Assumption stable = Assumption.create();
  int plain;

  CompilationFinalWrite() {
    cached = 1;
  }

  void unguarded(int value) {
    cached = value; // expect: TruffleCompilationFinalWrite
  }

  void element(int value) {
    table[0] = value; // expect: TruffleCompilationFinalWrite
  }

  void increment() {
    cached++; // expect: TruffleCompilationFinalWrite
  }

  void beyondDimensions(int value) {
    reference[0] = value;
  }

  void transferOnly(int value) {
    CompilerDirectives.transferToInterpreter();
    cached = value; // expect: TruffleCompilationFinalWrite
  }

  void invalidated(int value) {
    CompilerDirectives.transferToInterpreterAndInvalidate();
    cached = value;
  }

  void guarded(int value) {
    if (CompilerDirectives.isPartialEvaluationConstant(this)) {
      CompilerDirectives.transferToInterpreterAndInvalidate();
    }
    cached = value;
  }

  void viaAssumption(int value) {
    stable.invalidate();
    cached = value;
  }

  @TruffleBoundary
  void boundary(int value) {
    cached = value;
  }

  void ordinary(int value) {
    plain = value;
  }
}
