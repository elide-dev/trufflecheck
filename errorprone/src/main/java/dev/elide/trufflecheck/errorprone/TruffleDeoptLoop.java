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
import com.sun.source.tree.BinaryTree;
import com.sun.source.tree.BlockTree;
import com.sun.source.tree.ExpressionStatementTree;
import com.sun.source.tree.ExpressionTree;
import com.sun.source.tree.IfTree;
import com.sun.source.tree.MethodInvocationTree;
import com.sun.source.tree.NewClassTree;
import com.sun.source.tree.ReturnTree;
import com.sun.source.tree.StatementTree;
import com.sun.source.tree.ThrowTree;
import com.sun.source.tree.Tree;
import com.sun.source.util.TreeScanner;
import java.util.List;
import javax.inject.Inject;

/**
 * Deoptimization cycles (truffle/docs/DeoptCyclePatterns.md): since 25.0 the runtime detects them
 * and permanently gives up compiling the call target.
 *
 * <ul>
 *   <li>{@code transferToInterpreterAndInvalidate()} followed only by a {@code throw} or a plain
 *       {@code return}: nothing changed, so recompiled code takes the same path and invalidates
 *       again;
 *   <li>an unbounded value cache: {@code if (cached != value) { invalidate; cached = value; }} on a
 *       {@code @CompilationFinal} field with no generic fallback state.
 * </ul>
 */
@BugPattern(
    name = "TruffleDeoptLoop",
    summary = "Invalidation without a state change deoptimizes forever",
    severity = BugPattern.SeverityLevel.WARNING)
public final class TruffleDeoptLoop extends TruffleChecker
    implements BugChecker.BlockTreeMatcher, BugChecker.IfTreeMatcher {
  /** For {@link java.util.ServiceLoader}; Error Prone itself injects the flags constructor. */
  public TruffleDeoptLoop() {
    this(ErrorProneFlags.empty());
  }

  @Inject
  public TruffleDeoptLoop(ErrorProneFlags flags) {
    super(flags, "deopt");
  }

  private static boolean invalidate(StatementTree statement, VisitorState state) {
    return statement instanceof ExpressionStatementTree expression
        && Truffle.isDirective(
            expression.getExpression(), Truffle.DIRECTIVES, state, "transferToInterpreterAndInvalidate");
  }

  /** A return of a literal or name (no call that could change state), or any throw. */
  private static boolean inert(StatementTree statement) {
    if (statement instanceof ThrowTree) return true;
    if (!(statement instanceof ReturnTree ret)) return false;
    if (ret.getExpression() == null) return true;
    var calls = new boolean[1];
    ret.getExpression().accept(
        new TreeScanner<Void, Void>() {
          @Override
          public Void visitMethodInvocation(MethodInvocationTree node, Void unused) {
            calls[0] = true;
            return null;
          }

          @Override
          public Void visitNewClass(NewClassTree node, Void unused) {
            calls[0] = true;
            return null;
          }
        },
        null);
    return !calls[0];
  }

  @Override
  public Description matchBlock(BlockTree tree, VisitorState state) {
    List<? extends StatementTree> statements = tree.getStatements();
    for (int i = 0; i < statements.size(); i++) {
      if (!invalidate(statements.get(i), state)) continue;
      var rest = statements.subList(i + 1, statements.size());
      if (rest.isEmpty() || !rest.stream().allMatch(TruffleDeoptLoop::inert)) continue;
      // A throw of a freshly built exception is the classic cycle; a rethrow after recording state isn't reached.
      if (skip(state)) return Description.NO_MATCH;
      return report(statements.get(i), "Nothing changes between invalidating and leaving.");
    }
    return Description.NO_MATCH;
  }

  /** {@code if (cachedField != x) { invalidate; cachedField = x; }} with no other state recorded. */
  @Override
  public Description matchIf(IfTree tree, VisitorState state) {
    var condition = tree.getCondition();
    while (condition instanceof com.sun.source.tree.ParenthesizedTree parens) condition = parens.getExpression();
    if (!(condition instanceof BinaryTree compare)
        || (compare.getKind() != Tree.Kind.NOT_EQUAL_TO && compare.getKind() != Tree.Kind.EQUAL_TO)) {
      return Description.NO_MATCH;
    }
    var field = Truffle.field(compare.getLeftOperand());
    if (field == null) field = Truffle.field(compare.getRightOperand());
    if (field == null || Truffle.compilationFinalDimensions(field, state) == null) return Description.NO_MATCH;
    if (!(tree.getThenStatement() instanceof BlockTree then)) return Description.NO_MATCH;
    var invalidates = false;
    var writes = 0;
    var writesCached = false;
    for (var statement : then.getStatements()) {
      if (invalidate(statement, state)) invalidates = true;
      if (statement instanceof ExpressionStatementTree expression
          && expression.getExpression() instanceof AssignmentTree assignment) {
        writes++;
        if (Truffle.field(assignment.getVariable()) == field) writesCached = true;
      }
    }
    if (!invalidates || !writesCached || writes > 1 || tree.getElseStatement() != null || skip(state)) {
      return Description.NO_MATCH;
    }
    return report(
        tree,
        "'" + field.getSimpleName() + "' caches one value and re-specializes on every new one; add a generic state"
            + " (or a bounded cache) so it stops invalidating.");
  }
}
