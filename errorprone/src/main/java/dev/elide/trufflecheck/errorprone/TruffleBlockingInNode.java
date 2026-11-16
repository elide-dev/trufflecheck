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
import com.google.errorprone.matchers.Matcher;
import com.sun.source.tree.ExpressionTree;
import com.sun.source.tree.MethodInvocationTree;
import com.sun.source.tree.MethodTree;
import com.sun.source.tree.SynchronizedTree;
import com.sun.source.util.TreePath;
import javax.inject.Inject;
import javax.lang.model.element.Modifier;

@BugPattern(
    name = "TruffleBlockingInNode",
    altNames = "ElideBlockingInNode",
    summary = "Blocking or thread-local operations reachable by partial evaluation need @TruffleBoundary",
    severity = BugPattern.SeverityLevel.ERROR)
public final class TruffleBlockingInNode extends TruffleChecker
    implements BugChecker.MethodInvocationTreeMatcher,
        BugChecker.SynchronizedTreeMatcher,
        BugChecker.MethodTreeMatcher {
  private final Matcher<ExpressionTree> calls;

  /** For {@link java.util.ServiceLoader}; Error Prone itself injects the flags constructor. */
  public TruffleBlockingInNode() {
    this(ErrorProneFlags.empty());
  }

  @Inject
  public TruffleBlockingInNode(ErrorProneFlags flags) {
    super(flags, "blocking");
    this.calls = Calls.matcher(policy, "blocking");
  }

  @Override
  public Description matchMethodInvocation(MethodInvocationTree tree, VisitorState state) {
    if (!calls.matches(tree, state) || skip(state) || !Truffle.inPartialEvaluation(state)) {
      return Description.NO_MATCH;
    }
    return report(tree);
  }

  @Override
  public Description matchSynchronized(SynchronizedTree tree, VisitorState state) {
    return skip(state) || !Truffle.inPartialEvaluation(state) ? Description.NO_MATCH : report(tree);
  }

  @Override
  public Description matchMethod(MethodTree tree, VisitorState state) {
    if (!tree.getModifiers().getFlags().contains(Modifier.SYNCHRONIZED)
        || tree.getBody() == null
        || skip(state)) {
      return Description.NO_MATCH;
    }
    // A synchronized method is PE-reachable when its body is.
    var body = new TreePath(state.getPath(), tree.getBody());
    return Truffle.inPartialEvaluation(body, state) ? report(tree) : Description.NO_MATCH;
  }
}
