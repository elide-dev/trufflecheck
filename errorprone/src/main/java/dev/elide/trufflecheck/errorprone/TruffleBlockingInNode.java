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
import com.sun.source.tree.ClassTree;
import com.sun.source.tree.ExpressionTree;
import com.sun.source.tree.MethodInvocationTree;
import com.sun.source.tree.MethodTree;
import com.sun.source.tree.SynchronizedTree;
import com.sun.source.tree.Tree;
import javax.lang.model.element.Modifier;
import dev.elide.trufflecheck.Policy;
import javax.inject.Inject;
import com.google.errorprone.ErrorProneFlags;

@BugPattern(
    name = "TruffleBlockingInNode",
    altNames = "ElideBlockingInNode",
    summary = "Blocking or thread-local operations in Truffle nodes need @TruffleBoundary",
    severity = BugPattern.SeverityLevel.ERROR)
public final class TruffleBlockingInNode extends BugChecker
    implements BugChecker.MethodInvocationTreeMatcher,
        BugChecker.SynchronizedTreeMatcher,
        BugChecker.MethodTreeMatcher {
  private static final String NODE = "com.oracle.truffle.api.nodes.Node";
  private static final String BOUNDARY =
      "com.oracle.truffle.api.CompilerDirectives.TruffleBoundary";
  private final Policy policy;
  private final Matcher<ExpressionTree> calls;

  /** For {@link java.util.ServiceLoader}; Error Prone itself injects the flags constructor. */
  public TruffleBlockingInNode() {
    this(ErrorProneFlags.empty());
  }

  @Inject
  public TruffleBlockingInNode(ErrorProneFlags flags) {
    this.policy = Policies.of(flags);
    this.calls = Calls.matcher(policy, "blocking");
  }

  private Description check(Tree tree, MethodTree method, VisitorState state) {
    if (method != null && ASTHelpers.hasAnnotation(method, BOUNDARY, state)) {
      return Description.NO_MATCH;
    }
    var owner = ASTHelpers.findEnclosingNode(state.getPath(), ClassTree.class);
    var node = state.getTypeFromString(NODE);
    var type = owner == null ? null : ASTHelpers.getType(owner);
    if (node == null || type == null || !ASTHelpers.isSubtype(type, node, state)) {
      return Description.NO_MATCH;
    }
    return buildDescription(tree).setMessage(policy.message("blocking")).build();
  }

  private Description check(Tree tree, VisitorState state) {
    return check(tree, ASTHelpers.findEnclosingNode(state.getPath(), MethodTree.class), state);
  }

  @Override
  public Description matchMethodInvocation(MethodInvocationTree tree, VisitorState state) {
    return calls.matches(tree, state) ? check(tree, state) : Description.NO_MATCH;
  }

  @Override
  public Description matchSynchronized(SynchronizedTree tree, VisitorState state) {
    return check(tree, state);
  }

  @Override
  public Description matchMethod(MethodTree tree, VisitorState state) {
    return tree.getModifiers().getFlags().contains(Modifier.SYNCHRONIZED)
        ? check(tree, tree, state)
        : Description.NO_MATCH;
  }
}
