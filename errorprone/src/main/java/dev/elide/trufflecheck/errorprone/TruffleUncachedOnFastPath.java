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
import javax.inject.Inject;

/**
 * Uncached nodes and libraries, and {@code CallTarget.call}, in code partial evaluation reaches:
 * uncached variants dispatch on every call and don't specialize; a direct {@code
 * CallTarget.call} gets no inlining or splitting. Use {@code @Cached} nodes / libraries and {@code
 * DirectCallNode}/{@code IndirectCallNode} children.
 */
@BugPattern(
    name = "TruffleUncachedOnFastPath",
    summary = "Uncached nodes and direct CallTarget.call on the fast path",
    severity = BugPattern.SeverityLevel.WARNING)
public final class TruffleUncachedOnFastPath extends TruffleChecker
    implements BugChecker.MethodInvocationTreeMatcher {
  private static final String CALL_TARGET = "com.oracle.truffle.api.CallTarget";

  /** For {@link java.util.ServiceLoader}; Error Prone itself injects the flags constructor. */
  public TruffleUncachedOnFastPath() {
    this(ErrorProneFlags.empty());
  }

  @Inject
  public TruffleUncachedOnFastPath(ErrorProneFlags flags) {
    super(flags, "uncached");
  }

  @Override
  public Description matchMethodInvocation(MethodInvocationTree tree, VisitorState state) {
    var symbol = ASTHelpers.getSymbol(tree);
    if (symbol == null) return Description.NO_MATCH;
    var name = symbol.getSimpleName().toString();
    var owner = symbol.owner.getQualifiedName().toString();
    String detail;
    if (name.equals("getUncached") && owner.startsWith("com.oracle.truffle.") || name.equals("getUncached")
        && Truffle.isNode(ASTHelpers.getReturnType(tree), state)) {
      detail = "'" + symbol.owner.getSimpleName() + ".getUncached()' doesn't specialize.";
    } else if (name.equals("call") && Truffle.isSubtype(symbol.owner.type, CALL_TARGET, state)) {
      detail = "A direct CallTarget.call gets no inlining or splitting.";
    } else {
      return Description.NO_MATCH;
    }
    if (skip(state) || !Truffle.inPartialEvaluation(state)) return Description.NO_MATCH;
    return report(tree, detail);
  }
}
