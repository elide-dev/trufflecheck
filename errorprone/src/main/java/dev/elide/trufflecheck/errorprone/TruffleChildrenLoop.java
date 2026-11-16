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
import com.sun.source.tree.EnhancedForLoopTree;
import com.sun.source.tree.ExpressionTree;
import com.sun.source.tree.ForLoopTree;
import com.sun.source.tree.IdentifierTree;
import com.sun.source.tree.MemberSelectTree;
import com.sun.source.tree.MethodInvocationTree;
import com.sun.source.tree.MethodTree;
import com.sun.source.util.TreeScanner;
import com.sun.tools.javac.code.Symbol;
import java.util.HashSet;
import java.util.Set;
import javax.inject.Inject;
import javax.lang.model.element.ElementKind;

/**
 * Loops over {@code @Children} and {@code @ExplodeLoop} hygiene:
 *
 * <ul>
 *   <li>a loop over a {@code @Children} array in compiled code without {@code @ExplodeLoop} leaves
 *       each {@code children[i].execute…} an unresolvable virtual call;
 *   <li>an {@code @ExplodeLoop} loop bounded by a parameter or local (not provably a PE constant
 *       via {@code CompilerAsserts.partialEvaluationConstant}) may explode without bound and bail out;
 *   <li>{@code MERGE_EXPLODE} without {@code CompilerDirectives.mergeExplodeKey(…)} (Truffle 25.5).
 * </ul>
 */
@BugPattern(
    name = "TruffleChildrenLoop",
    summary = "Loops over @Children need @ExplodeLoop, and exploded loops need constant bounds",
    severity = BugPattern.SeverityLevel.WARNING)
public final class TruffleChildrenLoop extends TruffleChecker
    implements BugChecker.EnhancedForLoopTreeMatcher, BugChecker.ForLoopTreeMatcher, BugChecker.MethodTreeMatcher {
  /** For {@link java.util.ServiceLoader}; Error Prone itself injects the flags constructor. */
  public TruffleChildrenLoop() {
    this(ErrorProneFlags.empty());
  }

  @Inject
  public TruffleChildrenLoop(ErrorProneFlags flags) {
    super(flags, "loop");
  }

  private static boolean exploded(VisitorState state) {
    var method = ASTHelpers.findEnclosingNode(state.getPath(), MethodTree.class);
    var symbol = method == null ? null : ASTHelpers.getSymbol(method);
    return symbol != null && ASTHelpers.hasAnnotation(symbol, Truffle.EXPLODE_LOOP, state);
  }

  private static boolean isChildren(ExpressionTree tree, VisitorState state) {
    var field = Truffle.field(tree);
    return field != null && ASTHelpers.hasAnnotation(field, Truffle.CHILDREN, state);
  }

  /** The array whose {@code .length} bounds {@code i < x.length}, or null. */
  private static ExpressionTree lengthOf(ExpressionTree condition) {
    if (condition instanceof BinaryTree binary
        && binary.getRightOperand() instanceof MemberSelectTree select
        && select.getIdentifier().contentEquals("length")) {
      return select.getExpression();
    }
    return null;
  }

  @Override
  public Description matchEnhancedForLoop(EnhancedForLoopTree tree, VisitorState state) {
    return check(tree, tree.getExpression(), state);
  }

  @Override
  public Description matchForLoop(ForLoopTree tree, VisitorState state) {
    var condition = tree.getCondition();
    var bound = condition == null ? null : lengthOf(condition);
    if (bound != null) return check(tree, bound, state);
    // i < n with n a parameter or local, inside @ExplodeLoop.
    if (condition instanceof BinaryTree binary
        && binary.getRightOperand() instanceof IdentifierTree name
        && ASTHelpers.getSymbol(name) instanceof Symbol.VarSymbol variable
        && variable.getKind() != ElementKind.FIELD
        && exploded(state)
        && !constant(variable, state)
        && !skip(state)) {
      return report(tree, "'" + variable.getSimpleName() + "' bounds an exploded loop but isn't asserted constant.");
    }
    return Description.NO_MATCH;
  }

  private Description check(com.sun.source.tree.Tree loop, ExpressionTree array, VisitorState state) {
    if (exploded(state)) {
      var symbol = ASTHelpers.getSymbol(array);
      if (symbol instanceof Symbol.VarSymbol variable
          && variable.getKind() != ElementKind.FIELD
          && !constant(variable, state)
          && !skip(state)) {
        return report(loop, "'" + variable.getSimpleName() + "' bounds an exploded loop but isn't asserted constant.");
      }
      return Description.NO_MATCH;
    }
    if (!isChildren(array, state) || skip(state) || !Truffle.inPartialEvaluation(state)) {
      return Description.NO_MATCH;
    }
    return report(loop, "Loops over @Children must be @ExplodeLoop.");
  }

  /** {@code CompilerAsserts.partialEvaluationConstant(x)} somewhere in the enclosing method. */
  private static boolean constant(Symbol.VarSymbol variable, VisitorState state) {
    var method = ASTHelpers.findEnclosingNode(state.getPath(), MethodTree.class);
    if (method == null || method.getBody() == null) return false;
    var found = new boolean[1];
    method.getBody().accept(
        new TreeScanner<Void, Void>() {
          @Override
          public Void visitMethodInvocation(MethodInvocationTree call, Void unused) {
            if (Truffle.isDirective(call, Truffle.ASSERTS, state, "partialEvaluationConstant")) {
              for (var argument : call.getArguments()) {
                var root = argument;
                while (root instanceof MemberSelectTree select) root = select.getExpression();
                if (ASTHelpers.getSymbol(root) == variable) found[0] = true;
              }
            }
            return super.visitMethodInvocation(call, unused);
          }
        },
        null);
    return found[0];
  }

  /** {@code @ExplodeLoop(kind = MERGE_EXPLODE)} without {@code mergeExplodeKey}. */
  @Override
  public Description matchMethod(MethodTree tree, VisitorState state) {
    var symbol = ASTHelpers.getSymbol(tree);
    if (symbol == null || tree.getBody() == null) return Description.NO_MATCH;
    var merge = false;
    for (var annotation : symbol.getAnnotationMirrors()) {
      if (!annotation.getAnnotationType().toString().equals(Truffle.EXPLODE_LOOP)) continue;
      for (var value : annotation.getElementValues().values()) {
        if (value.getValue().toString().endsWith("MERGE_EXPLODE")) merge = true;
      }
    }
    if (!merge) return Description.NO_MATCH;
    var keyed = new boolean[1];
    tree.getBody().accept(
        new TreeScanner<Void, Void>() {
          @Override
          public Void visitMethodInvocation(MethodInvocationTree call, Void unused) {
            if (Truffle.isDirective(call, Truffle.DIRECTIVES, state, "mergeExplodeKey")) keyed[0] = true;
            return super.visitMethodInvocation(call, unused);
          }
        },
        null);
    return keyed[0] || skip(state)
        ? Description.NO_MATCH
        : report(tree, "MERGE_EXPLODE loops must mark their merge key with CompilerDirectives.mergeExplodeKey(…).");
  }
}
