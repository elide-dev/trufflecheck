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
import com.sun.source.tree.AssignmentTree;
import com.sun.source.tree.ExpressionTree;
import com.sun.source.tree.MethodInvocationTree;
import com.sun.source.tree.ParenthesizedTree;
import com.sun.source.tree.Tree;
import javax.inject.Inject;

/**
 * Assigning a new node to a {@code @Child} field (or a {@code @Children} element) outside the
 * constructor without {@code insert(…)}: the node has no parent, so {@code getRootNode()}, {@code
 * replace} and DSL-generated code misbehave, and the tree isn't notified of the change.
 */
@BugPattern(
    name = "TruffleChildWriteWithoutInsert",
    summary = "Child nodes assigned after construction must go through insert()",
    severity = BugPattern.SeverityLevel.ERROR)
public final class TruffleChildWriteWithoutInsert extends TruffleChecker
    implements BugChecker.AssignmentTreeMatcher {
  /** For {@link java.util.ServiceLoader}; Error Prone itself injects the flags constructor. */
  public TruffleChildWriteWithoutInsert() {
    this(ErrorProneFlags.empty());
  }

  @Inject
  public TruffleChildWriteWithoutInsert(ErrorProneFlags flags) {
    super(flags, "insert");
  }

  @Override
  public Description matchAssignment(AssignmentTree tree, VisitorState state) {
    var root = Truffle.arrayRoot(tree.getVariable());
    var field = Truffle.field(root.root());
    if (field == null) return Description.NO_MATCH;
    var annotation = root.depth() == 0 ? Truffle.CHILD : Truffle.CHILDREN;
    if (root.depth() > 1 || !ASTHelpers.hasAnnotation(field, annotation, state)) return Description.NO_MATCH;
    if (Truffle.inInitializerOf(state.getPath(), field.owner) || adopted(tree.getExpression(), state)) {
      return Description.NO_MATCH;
    }
    return skip(state) ? Description.NO_MATCH : report(tree);
  }

  /** {@code insert(…)}, {@code null}, or a conditional of those. */
  private static boolean adopted(ExpressionTree value, VisitorState state) {
    while (value instanceof ParenthesizedTree parens) value = parens.getExpression();
    if (value.getKind() == Tree.Kind.NULL_LITERAL) return true;
    if (value instanceof com.sun.source.tree.ConditionalExpressionTree conditional) {
      return adopted(conditional.getTrueExpression(), state) && adopted(conditional.getFalseExpression(), state);
    }
    if (!(value instanceof MethodInvocationTree call)) return false;
    var symbol = ASTHelpers.getSymbol(call);
    return symbol != null
        && symbol.getSimpleName().contentEquals("insert")
        && Truffle.isSubtype(symbol.owner.type, Truffle.NODE, state);
  }
}
