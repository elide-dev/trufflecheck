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
import com.sun.source.tree.CompoundAssignmentTree;
import com.sun.source.tree.ExpressionStatementTree;
import com.sun.source.tree.ExpressionTree;
import com.sun.source.tree.MethodTree;
import com.sun.source.tree.StatementTree;
import com.sun.source.tree.Tree;
import com.sun.source.tree.UnaryTree;
import javax.inject.Inject;

/**
 * Writes to {@code @CompilationFinal} fields, or to elements of {@code @CompilationFinal} arrays
 * within their declared dimensions, outside constructors and initializers, without a dominating
 * {@code transferToInterpreterAndInvalidate()} or {@code Assumption.invalidate()}. Compiled code
 * has folded the old value; changing it without invalidating leaves that code wrong.
 */
@BugPattern(
    name = "TruffleCompilationFinalWrite",
    summary = "Writes to @CompilationFinal state must invalidate compiled code first",
    severity = BugPattern.SeverityLevel.ERROR)
public final class TruffleCompilationFinalWrite extends TruffleChecker
    implements BugChecker.AssignmentTreeMatcher,
        BugChecker.CompoundAssignmentTreeMatcher,
        BugChecker.UnaryTreeMatcher {
  private static final String ASSUMPTION = "com.oracle.truffle.api.Assumption";

  /** For {@link java.util.ServiceLoader}; Error Prone itself injects the flags constructor. */
  public TruffleCompilationFinalWrite() {
    this(ErrorProneFlags.empty());
  }

  @Inject
  public TruffleCompilationFinalWrite(ErrorProneFlags flags) {
    super(flags, "cfwrite");
  }

  @Override
  public Description matchAssignment(AssignmentTree tree, VisitorState state) {
    return check(tree, tree.getVariable(), state);
  }

  @Override
  public Description matchCompoundAssignment(CompoundAssignmentTree tree, VisitorState state) {
    return check(tree, tree.getVariable(), state);
  }

  @Override
  public Description matchUnary(UnaryTree tree, VisitorState state) {
    return switch (tree.getKind()) {
      case PREFIX_INCREMENT, PREFIX_DECREMENT, POSTFIX_INCREMENT, POSTFIX_DECREMENT ->
          check(tree, tree.getExpression(), state);
      default -> Description.NO_MATCH;
    };
  }

  private Description check(Tree tree, ExpressionTree target, VisitorState state) {
    var root = Truffle.arrayRoot(target);
    var field = Truffle.field(root.root());
    if (field == null) return Description.NO_MATCH;
    var dimensions = Truffle.compilationFinalDimensions(field, state);
    if (dimensions == null) return Description.NO_MATCH;
    // Element stores past the declared constant depth aren't folded.
    if (root.depth() > 0 && dimensions >= 0 && root.depth() > dimensions) return Description.NO_MATCH;
    if (Truffle.inInitializerOf(state.getPath(), field.owner) || exempt(state) || skip(state)) {
      return Description.NO_MATCH;
    }
    if (Truffle.precededBy(state.getPath(), s -> Truffle.containsStatement(s, x -> invalidates(x, state)))) {
      return Description.NO_MATCH;
    }
    var plainTransfer =
        Truffle.precededBy(
            state.getPath(),
            s ->
                s instanceof ExpressionStatementTree e
                    && Truffle.isDirective(e.getExpression(), Truffle.DIRECTIVES, state, "transferToInterpreter"));
    return report(
        tree,
        plainTransfer
            ? "transferToInterpreter() deoptimizes but doesn't invalidate; use transferToInterpreterAndInvalidate()."
            : "'" + field.getSimpleName() + "' is @CompilationFinal.");
  }

  /** Boundaries and interpreter-only methods: by convention reached after the caller invalidated. */
  private static boolean exempt(VisitorState state) {
    var method = ASTHelpers.findEnclosingNode(state.getPath(), MethodTree.class);
    var symbol = method == null ? null : ASTHelpers.getSymbol(method);
    if (symbol == null) return false;
    if (ASTHelpers.hasAnnotation(symbol, Truffle.BOUNDARY, state)) return true;
    var body = method.getBody();
    return body != null
        && !body.getStatements().isEmpty()
        && body.getStatements().get(0) instanceof ExpressionStatementTree first
        && Truffle.isDirective(first.getExpression(), Truffle.ASSERTS, state, "neverPartOfCompilation");
  }

  private static boolean invalidates(StatementTree statement, VisitorState state) {
    if (!(statement instanceof ExpressionStatementTree expression)) return false;
    var call = expression.getExpression();
    if (Truffle.isDirective(call, Truffle.DIRECTIVES, state, "transferToInterpreterAndInvalidate")) return true;
    return call instanceof com.sun.source.tree.MethodInvocationTree invocation
        && ASTHelpers.getSymbol(invocation) != null
        && ASTHelpers.getSymbol(invocation).getSimpleName().contentEquals("invalidate")
        && Truffle.isSubtype(ASTHelpers.getSymbol(invocation).owner.type, ASSUMPTION, state);
  }
}
