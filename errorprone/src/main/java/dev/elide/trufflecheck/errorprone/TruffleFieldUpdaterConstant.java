/*
 * Copyright (c) 2024-2026 Elide Technologies, Inc.
 * SPDX-License-Identifier: Apache-2.0 OR MIT
 */

package dev.elide.trufflecheck.errorprone;

import com.google.errorprone.BugPattern;
import com.google.errorprone.ErrorProneFlags;
import com.google.errorprone.VisitorState;
import com.google.errorprone.bugpatterns.BugChecker;
import com.google.errorprone.matchers.Description;
import com.google.errorprone.util.ASTHelpers;
import com.sun.source.tree.MethodInvocationTree;
import com.sun.source.tree.VariableTree;
import java.util.Set;
import javax.inject.Inject;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.Modifier;

/**
 * {@code Atomic*FieldUpdater.newUpdater(…)} and {@code Unsafe} field/array offsets must be the
 * direct initializer of a {@code static final} field. Native Image recomputes offsets only for that
 * shape (anything else bakes the build JVM's offset into the image heap), and partial evaluation
 * treats an updater as legal only when it's a constant (Truffle 25.1+); otherwise compilation
 * bails out.
 */
@BugPattern(
    name = "TruffleFieldUpdaterConstant",
    summary = "Field updaters and Unsafe offsets belong in static final fields",
    severity = BugPattern.SeverityLevel.WARNING)
public final class TruffleFieldUpdaterConstant extends TruffleChecker
    implements BugChecker.MethodInvocationTreeMatcher {
  private static final Set<String> UPDATERS =
      Set.of(
          "java.util.concurrent.atomic.AtomicIntegerFieldUpdater",
          "java.util.concurrent.atomic.AtomicLongFieldUpdater",
          "java.util.concurrent.atomic.AtomicReferenceFieldUpdater");
  private static final Set<String> UNSAFE = Set.of("sun.misc.Unsafe", "jdk.internal.misc.Unsafe");
  private static final Set<String> OFFSETS =
      Set.of("objectFieldOffset", "staticFieldOffset", "arrayBaseOffset", "arrayIndexScale");

  /** For {@link java.util.ServiceLoader}; Error Prone itself injects the flags constructor. */
  public TruffleFieldUpdaterConstant() {
    this(ErrorProneFlags.empty());
  }

  @Inject
  public TruffleFieldUpdaterConstant(ErrorProneFlags flags) {
    super(flags, "updater");
  }

  @Override
  public Description matchMethodInvocation(MethodInvocationTree tree, VisitorState state) {
    var symbol = ASTHelpers.getSymbol(tree);
    if (symbol == null) return Description.NO_MATCH;
    var owner = symbol.owner.getQualifiedName().toString();
    var name = symbol.getSimpleName().toString();
    var relevant =
        (UPDATERS.contains(owner) && name.equals("newUpdater")) || (UNSAFE.contains(owner) && OFFSETS.contains(name));
    if (!relevant) return Description.NO_MATCH;
    if (state.getPath().getParentPath().getLeaf() instanceof VariableTree variable) {
      var field = ASTHelpers.getSymbol(variable);
      if (field != null
          && field.getKind() == ElementKind.FIELD
          && field.getModifiers().containsAll(Set.of(Modifier.STATIC, Modifier.FINAL))) {
        return Description.NO_MATCH;
      }
    }
    return skip(state) ? Description.NO_MATCH : report(tree, "'" + name + "' isn't a static final field's initializer.");
  }
}
