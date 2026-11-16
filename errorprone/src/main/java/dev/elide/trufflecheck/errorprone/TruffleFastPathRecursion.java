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
import com.sun.source.tree.IdentifierTree;
import com.sun.source.tree.MemberSelectTree;
import com.sun.source.tree.MethodInvocationTree;
import com.sun.source.tree.MethodTree;
import javax.inject.Inject;

/**
 * A method that partial evaluation reaches calling itself directly. Partial evaluation can't
 * inline recursion, and host inlining only takes the first level, so the recursive call stays a
 * real call (or bails out). Recurse through the AST (child nodes, call targets) or move the
 * recursion behind {@code @TruffleBoundary}.
 */
@BugPattern(
    name = "TruffleFastPathRecursion",
    summary = "Direct recursion in compiled code",
    severity = BugPattern.SeverityLevel.WARNING)
public final class TruffleFastPathRecursion extends TruffleChecker
    implements BugChecker.MethodInvocationTreeMatcher {
  /** For {@link java.util.ServiceLoader}; Error Prone itself injects the flags constructor. */
  public TruffleFastPathRecursion() {
    this(ErrorProneFlags.empty());
  }

  @Inject
  public TruffleFastPathRecursion(ErrorProneFlags flags) {
    super(flags, "recursion");
  }

  @Override
  public Description matchMethodInvocation(MethodInvocationTree tree, VisitorState state) {
    var method = ASTHelpers.findEnclosingNode(state.getPath(), MethodTree.class);
    if (method == null || ASTHelpers.getSymbol(tree) != ASTHelpers.getSymbol(method)) return Description.NO_MATCH;
    // Same receiver: unqualified or this.m(…); other.m(…) recurses into another node of the tree.
    var select = tree.getMethodSelect();
    if (!(select instanceof IdentifierTree)
        && !(select instanceof MemberSelectTree member && member.getExpression().toString().equals("this"))) {
      return Description.NO_MATCH;
    }
    if (skip(state) || !Truffle.inPartialEvaluation(state)) return Description.NO_MATCH;
    return report(tree);
  }
}
