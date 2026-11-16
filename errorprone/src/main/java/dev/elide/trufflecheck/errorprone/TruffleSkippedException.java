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
import com.sun.source.tree.MethodTree;
import com.sun.source.tree.NewClassTree;
import com.sun.source.tree.ThrowTree;
import java.util.Set;
import javax.inject.Inject;

/**
 * {@code throw new X(…)} in code partial evaluation reaches, where X is one of Graal's "skipped"
 * exception types (KnownTruffleTypes): compiled code never handles them; throwing one always
 * deoptimizes and invalidates, and catching it doesn't help. Use a guest (AbstractTruffleException)
 * error, a ControlFlowException, or transfer to the interpreter first.
 */
@BugPattern(
    name = "TruffleSkippedException",
    summary = "Exceptions compiled code doesn't handle always deoptimize",
    severity = BugPattern.SeverityLevel.ERROR)
public final class TruffleSkippedException extends TruffleChecker
    implements BugChecker.ThrowTreeMatcher {
  private static final String UNEXPECTED = "com.oracle.truffle.api.nodes.UnexpectedResultException";

  private final Set<String> skipped;

  /** For {@link java.util.ServiceLoader}; Error Prone itself injects the flags constructor. */
  public TruffleSkippedException() {
    this(ErrorProneFlags.empty());
  }

  @Inject
  public TruffleSkippedException(ErrorProneFlags flags) {
    super(flags, "skipped");
    this.skipped = policy.owners("skipped");
  }

  @Override
  public Description matchThrow(ThrowTree tree, VisitorState state) {
    if (!(tree.getExpression() instanceof NewClassTree creation)) return Description.NO_MATCH;
    var type = ASTHelpers.getType(creation.getIdentifier());
    if (type == null) return Description.NO_MATCH;
    String match = null;
    for (var name : skipped) {
      if (Truffle.isSubtype(type, name, state)) {
        match = name;
        break;
      }
    }
    if (match == null) return Description.NO_MATCH;
    // The DSL's own protocol: typed execute methods rewrite on UnexpectedResultException.
    if (Truffle.isSubtype(type, UNEXPECTED, state)) {
      var method = ASTHelpers.findEnclosingNode(state.getPath(), MethodTree.class);
      if (method != null && method.getName().toString().startsWith("execute")) return Description.NO_MATCH;
    }
    if (skip(state) || !Truffle.inPartialEvaluation(state)) return Description.NO_MATCH;
    return report(tree, "'" + type.tsym.getSimpleName() + "' is never handled in compiled code.");
  }
}
