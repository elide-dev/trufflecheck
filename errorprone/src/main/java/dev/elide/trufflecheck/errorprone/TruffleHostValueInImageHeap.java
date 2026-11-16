/*
 * Copyright (c) 2024-2026 Elide Technologies, Inc.
 * SPDX-License-Identifier: Apache-2.0 OR MIT
 */

package dev.elide.trufflecheck.errorprone;

import com.google.errorprone.BugPattern;
import com.google.errorprone.VisitorState;
import com.google.errorprone.bugpatterns.BugChecker;
import com.google.errorprone.matchers.Description;
import com.google.errorprone.matchers.Matcher;
import com.google.errorprone.util.ASTHelpers;
import com.sun.source.tree.BlockTree;
import com.sun.source.tree.ClassTree;
import com.sun.source.tree.ExpressionTree;
import com.sun.source.tree.LambdaExpressionTree;
import com.sun.source.tree.MemberReferenceTree;
import com.sun.source.tree.MethodInvocationTree;
import com.sun.source.tree.MethodTree;
import com.sun.source.tree.VariableTree;
import com.sun.tools.javac.code.Symbol;
import javax.lang.model.element.Modifier;
import dev.elide.trufflecheck.Policy;
import javax.inject.Inject;
import com.google.errorprone.ErrorProneFlags;

@BugPattern(
    name = "TruffleHostValueInImageHeap",
    altNames = "ElideHostValueInImageHeap",
    summary = "Build-time static initializers must not capture build-host state",
    severity = BugPattern.SeverityLevel.ERROR)
public final class TruffleHostValueInImageHeap extends BugChecker
    implements BugChecker.MethodInvocationTreeMatcher {
  private final Policy policy;
  private final Matcher<ExpressionTree> calls;

  /** For {@link java.util.ServiceLoader}; Error Prone itself injects the flags constructor. */
  public TruffleHostValueInImageHeap() {
    this(ErrorProneFlags.empty());
  }

  @Inject
  public TruffleHostValueInImageHeap(ErrorProneFlags flags) {
    this.policy = Policies.of(flags);
    this.calls = Calls.matcher(policy, "hostvalue");
  }

  @Override
  public Description matchMethodInvocation(MethodInvocationTree tree, VisitorState state) {
    if (!calls.matches(tree, state)) return Description.NO_MATCH;
    for (var parent : state.getPath().getParentPath()) {
      if (parent instanceof LambdaExpressionTree
          || parent instanceof MemberReferenceTree
          || parent instanceof MethodTree
          || parent instanceof ClassTree) {
        return Description.NO_MATCH;
      }
      if ((parent instanceof VariableTree field && isStaticField(field))
          || (parent instanceof BlockTree block && block.isStatic())) {
        return report(tree, state);
      }
    }
    return Description.NO_MATCH;
  }

  private static boolean isStaticField(VariableTree tree) {
    var symbol = ASTHelpers.getSymbol(tree);
    return symbol != null
        && symbol.getKind().isField()
        && symbol.getModifiers().contains(Modifier.STATIC);
  }

  private Description report(MethodInvocationTree tree, VisitorState state) {
    var owner = ASTHelpers.findEnclosingNode(state.getPath(), ClassTree.class);
    Symbol.ClassSymbol symbol = owner == null ? null : ASTHelpers.getSymbol(owner);
    if (symbol == null || !policy.initializedAtBuildTime(symbol.flatName().toString())) {
      return Description.NO_MATCH;
    }
    return buildDescription(tree).setMessage(policy.message("hostvalue")).build();
  }
}
