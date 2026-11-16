package fixture;

import com.oracle.truffle.api.Truffle;
import com.oracle.truffle.api.frame.Frame;
import com.oracle.truffle.api.frame.FrameInstance.FrameAccess;
import com.oracle.truffle.api.frame.MaterializedFrame;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.Node;
import java.util.function.Supplier;

final class FrameEscape extends Node {
  VirtualFrame stored; // expect: TruffleFrameEscape
  Object kept;
  MaterializedFrame materialized;

  void store(VirtualFrame frame) {
    kept = frame; // expect: TruffleFrameEscape
  }

  void materialize(VirtualFrame frame) {
    materialized = frame.materialize();
  }

  Supplier<Object> capture(VirtualFrame frame) {
    return () -> frame.getArguments(); // expect: TruffleFrameEscape
  }

  Object anonymous(VirtualFrame frame) {
    return new Object() {
      @Override
      public String toString() {
        return String.valueOf(frame.getArguments().length); // expect: TruffleFrameEscape
      }
    };
  }

  Object passDown(VirtualFrame frame) {
    return frame.getArguments();
  }

  Frame walk() {
    return Truffle.getRuntime().iterateFrames(f -> f.getFrame(FrameAccess.READ_WRITE)); // expect: TruffleFrameEscape
  }

  Frame readOnly() {
    return Truffle.getRuntime().iterateFrames(f -> f.getFrame(FrameAccess.READ_ONLY));
  }
}
