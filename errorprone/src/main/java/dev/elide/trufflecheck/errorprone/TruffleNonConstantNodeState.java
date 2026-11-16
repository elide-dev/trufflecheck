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
 * Speculation state in a node (or {@code @ExportLibrary} class) that partial evaluation can't fold:
 * an Assumption, profile, CallTarget, Shape or FrameDescriptor field that is neither {@code final}
 * nor {@code @CompilationFinal}. Partial evaluation only constant-folds final, @CompilationFinal,
 * @Child and @Children fields of constant receivers; anything else is a real load, and a guard on
 * it is a real branch. Static profiles are shared by every node, so they profile nothing (except
 * the disabled instances from {@code getUncached()}).
 */
@BugPattern(
    name = "TruffleNonConstantNodeState",
    summary = "Speculation state in nodes must be final or @CompilationFinal",
    severity = BugPattern.SeverityLevel.ERROR)
public final class TruffleNonConstantNodeState extends TruffleChecker
    implements BugChecker.VariableTreeMatcher {
  private static final String PROFILE = "com.oracle.truffle.api.profiles.Profile";
  private static final String INLINED = "com.oracle.truffle.api.profiles.InlinedProfile";

  private final Set<String> types;

  /** For {@link java.util.ServiceLoader}; Error Prone itself injects the flags constructor. */
  public TruffleNonConstantNodeState() {
    this(ErrorProneFlags.empty());
  }

  @Inject
  public TruffleNonConstantNodeState(ErrorProneFlags flags) {
    super(flags, "nodestate");
    this.types = policy.owners("nodestate");
  }

  @Override
  public Description matchVariable(VariableTree tree, VisitorState state) {
    var symbol = ASTHelpers.getSymbol(tree);
    if (symbol == null || symbol.getKind() != ElementKind.FIELD) return Description.NO_MATCH;
    var owner = symbol.enclClass();
    var holder =
        owner != null
            && (Truffle.isSubtype(owner.type, Truffle.NODE, state)
                || ASTHelpers.hasAnnotation(owner, Truffle.EXPORT_LIBRARY, state));
    if (!holder) return Description.NO_MATCH;
    var type = symbol.type;
    if (symbol.getModifiers().contains(Modifier.STATIC)) {
      if (!Truffle.isSubtype(type, PROFILE, state) || Truffle.isSubtype(type, INLINED, state)) return Description.NO_MATCH;
      if (tree.getInitializer() instanceof MethodInvocationTree call
          && ASTHelpers.getSymbol(call) != null
          && ASTHelpers.getSymbol(call).getSimpleName().contentEquals("getUncached")) {
        return Description.NO_MATCH;
      }
      return skip(state)
          ? Description.NO_MATCH
          : report(tree, "A static profile is shared by every node and profiles nothing; make it an instance field.");
    }
    if (symbol.getModifiers().contains(Modifier.FINAL)
        || ASTHelpers.hasAnnotation(symbol, Truffle.COMPILATION_FINAL, state)) {
      return Description.NO_MATCH;
    }
    for (var banned : types) {
      if (Truffle.isSubtype(type, banned, state)) {
        return skip(state)
            ? Description.NO_MATCH
            : report(tree, "'" + symbol.getSimpleName() + "' is a " + type.tsym.getSimpleName() + ".");
      }
    }
    return Description.NO_MATCH;
  }
}
