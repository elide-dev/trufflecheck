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
import com.sun.source.tree.BinaryTree;
import com.sun.source.tree.ExpressionStatementTree;
import com.sun.source.tree.MethodTree;
import com.sun.source.tree.Tree;
import javax.inject.Inject;

/**
 * Message building (string concatenation) on a slow path inlined into a node's fast-path method,
 * after {@code transferToInterpreter*()}. It never runs compiled, but host compilation of the
 * interpreter still inlines it, bloating the native image and the interpreter's code (see
 * HostCompilation.md, "Extract complex slow-path code in separate methods"). Move it to a separate
 * method, ideally a {@code @TruffleBoundary}.
 */
@BugPattern(
    name = "TruffleSlowPathInline",
    summary = "Extract slow-path message building out of fast-path methods",
    severity = BugPattern.SeverityLevel.SUGGESTION)
public final class TruffleSlowPathInline extends TruffleChecker implements BugChecker.BinaryTreeMatcher {
  /** For {@link java.util.ServiceLoader}; Error Prone itself injects the flags constructor. */
  public TruffleSlowPathInline() {
    this(ErrorProneFlags.empty());
  }

  @Inject
  public TruffleSlowPathInline(ErrorProneFlags flags) {
    super(flags, "slowpath");
  }

  @Override
  public Description matchBinary(BinaryTree tree, VisitorState state) {
    if (tree.getKind() != Tree.Kind.PLUS) return Description.NO_MATCH;
    var type = ASTHelpers.getType(tree);
    if (type == null || !type.tsym.getQualifiedName().contentEquals("java.lang.String")) return Description.NO_MATCH;
    if (ASTHelpers.constValue(tree) != null) return Description.NO_MATCH;
    if (state.getPath().getParentPath().getLeaf() instanceof BinaryTree parent && parent.getKind() == Tree.Kind.PLUS) {
      return Description.NO_MATCH;
    }
    var method = ASTHelpers.findEnclosingNode(state.getPath(), MethodTree.class);
    var symbol = method == null ? null : ASTHelpers.getSymbol(method);
    if (symbol == null || ASTHelpers.hasAnnotation(symbol, Truffle.BOUNDARY, state)) return Description.NO_MATCH;
    var owner = symbol.enclClass();
    if (owner == null || !Truffle.isSubtype(owner.type, Truffle.NODE, state)) return Description.NO_MATCH;
    var afterTransfer = Truffle.precededBy(state.getPath(), statement -> Truffle.isTransfer(statement, state));
    if (!afterTransfer || skip(state)) return Description.NO_MATCH;
    return report(tree, "This slow-path message is inlined into '" + symbol.getSimpleName() + "'.");
  }
}
