package fixture;

import java.util.concurrent.atomic.AtomicIntegerFieldUpdater;

final class FieldUpdater {
  static final AtomicIntegerFieldUpdater<FieldUpdater> COUNT =
      AtomicIntegerFieldUpdater.newUpdater(FieldUpdater.class, "count");
  volatile int count;

  int lazy() {
    var updater = AtomicIntegerFieldUpdater.newUpdater(FieldUpdater.class, "count"); // expect: TruffleFieldUpdaterConstant
    return updater.incrementAndGet(this);
  }
}
