package fixture.heap;

import java.util.Random;

final class Heap {
  static final String HOME = System.getProperty("user.home"); // expect: TruffleHostValueInImageHeap
  static final Random RANDOM = new Random(); // expect: TruffleHostValueInImageHeap
  static final Clock CLOCK = new Clock(); // expect: TruffleHostValueInImageHeap
  static final long STARTED = started(); // expect: TruffleHostValueInImageHeap
  static final Runnable TASK =
      new Runnable() {
        final long created = System.nanoTime(); // expect: TruffleHostValueInImageHeap

        @Override
        public void run() {
          System.nanoTime();
        }
      };
  static final Runnable LAZY = () -> System.nanoTime();

  static {
    long now = System.currentTimeMillis(); // expect: TruffleHostValueInImageHeap
  }

  static String lazy() {
    return System.getProperty("user.home");
  }

  static long started() {
    return System.nanoTime();
  }

  static final class Clock {
    final long start;

    Clock() {
      start = System.nanoTime();
    }
  }

  enum Mode {
    FAST(System.nanoTime()); // expect: TruffleHostValueInImageHeap

    final long stamp;

    Mode(long stamp) {
      this.stamp = stamp;
    }
  }

  enum Stamp {
    NOW; // expect: TruffleHostValueInImageHeap

    final long at;

    Stamp() {
      at = System.nanoTime();
    }
  }
}
