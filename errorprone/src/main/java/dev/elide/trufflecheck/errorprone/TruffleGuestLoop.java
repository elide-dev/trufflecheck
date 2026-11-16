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
import com.sun.source.tree.ClassTree;
import com.sun.source.tree.DoWhileLoopTree;
import com.sun.source.tree.ForLoopTree;
import com.sun.source.tree.MethodInvocationTree;
import com.sun.source.tree.MethodTree;
import com.sun.source.tree.StatementTree;
import com.sun.source.tree.Tree;
import com.sun.source.tree.WhileLoopTree;
import com.sun.source.util.TreeScanner;
import javax.inject.Inject;

/**
 * A back-edge loop in a node that executes guest code (calls {@code execute…} on a child) without
 * {@code LoopNode}/{@code RepeatingNode}, {@code TruffleSafepoint.poll} or OSR polling: no on-stack
 * replacement, no loop-count profiling, and safepoints (cancellation, thread-local actions) can't
 * interrupt it. Exploded loops are exempt.
 */
@BugPattern(
    name = "TruffleGuestLoop",
    summary = "Guest loops belong in LoopNode/RepeatingNode, or must poll safepoints",
    severity = BugPattern.SeverityLevel.WARNING)
public final class TruffleGuestLoop extends TruffleChecker
    implements BugChecker.WhileLoopTreeMatcher, BugChecker.DoWhileLoopTreeMatcher, BugChecker.ForLoopTreeMatcher {
  private static final String REPEATING = "com.oracle.truffle.api.nodes.RepeatingNode";

  /** For {@link java.util.ServiceLoader}; Error Prone itself injects the flags constructor. */
  public TruffleGuestLoop() {
    this(ErrorProneFlags.empty());
  }

  @Inject
  public TruffleGuestLoop(ErrorProneFlags flags) {
    super(flags, "guestloop");
  }

  @Override
  public Description matchWhileLoop(WhileLoopTree tree, VisitorState state) {
    return check(tree, tree.getStatement(), state);
  }

  @Override
  public Description matchDoWhileLoop(DoWhileLoopTree tree, VisitorState state) {
    return check(tree, tree.getStatement(), state);
  }

  /** Counted loops (`i < n`) are bounded iteration; only flag ones whose condition runs guest code. */
  @Override
  public Description matchForLoop(ForLoopTree tree, VisitorState state) {
    var condition = tree.getCondition();
    if (condition == null || !(condition.toString().contains("execute"))) return Description.NO_MATCH;
    return check(tree, tree.getStatement(), state);
  }

  private Description check(Tree loop, StatementTree body, VisitorState state) {
    var method = ASTHelpers.findEnclosingNode(state.getPath(), MethodTree.class);
    var symbol = method == null ? null : ASTHelpers.getSymbol(method);
    if (symbol == null || ASTHelpers.hasAnnotation(symbol, Truffle.EXPLODE_LOOP, state)) return Description.NO_MATCH;
    var owner = ASTHelpers.findEnclosingNode(state.getPath(), ClassTree.class);
    var type = owner == null ? null : ASTHelpers.getSymbol(owner);
    if (type == null || Truffle.isSubtype(type.type, REPEATING, state)) return Description.NO_MATCH;
    var executes = new boolean[1];
    var polls = new boolean[1];
    body.accept(
        new TreeScanner<Void, Void>() {
          @Override
          public Void visitMethodInvocation(MethodInvocationTree call, Void unused) {
            var target = ASTHelpers.getSymbol(call);
            if (target != null) {
              var name = target.getSimpleName().toString();
              var ownerName = target.owner.getQualifiedName().toString();
              if (name.startsWith("execute") && Truffle.isNode(target.owner.type, state)) executes[0] = true;
              if ((ownerName.equals("com.oracle.truffle.api.TruffleSafepoint") && name.equals("poll"))
                  || (ownerName.equals("com.oracle.truffle.api.nodes.LoopNode") && name.equals("reportLoopCount"))
                  || (ownerName.equals("com.oracle.truffle.api.nodes.BytecodeOSRNode") && name.startsWith("pollOSRBackEdge"))) {
                polls[0] = true;
              }
            }
            return super.visitMethodInvocation(call, unused);
          }
        },
        null);
    if (!executes[0] || polls[0] || skip(state) || !Truffle.inPartialEvaluation(state)) return Description.NO_MATCH;
    return report(loop, "This loop executes guest code.");
  }
}
