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
import com.sun.source.tree.CatchTree;
import com.sun.source.tree.IdentifierTree;
import com.sun.source.tree.IfTree;
import com.sun.source.tree.InstanceOfTree;
import com.sun.source.tree.StatementTree;
import com.sun.source.tree.ThrowTree;
import com.sun.source.tree.TryTree;
import com.sun.source.tree.UnionTypeTree;
import com.sun.tools.javac.code.Type;
import java.util.List;
import java.util.Set;
import javax.inject.Inject;

/**
 * Catch-alls ({@code Throwable}, {@code Exception}, {@code RuntimeException}, {@code Error}, {@code
 * StackOverflowError}) in compiled code that don't let control-flow and guest exceptions through:
 * they swallow {@code ControlFlowException} (returns, breaks, yields), guest errors and
 * cancellation. Catch {@code ControlFlowException} / {@code AbstractTruffleException} first and
 * rethrow, or rethrow the caught exception.
 */
@BugPattern(
    name = "TruffleGenericCatch",
    summary = "Catch-alls in compiled code swallow control-flow and guest exceptions",
    severity = BugPattern.SeverityLevel.ERROR)
public final class TruffleGenericCatch extends TruffleChecker implements BugChecker.CatchTreeMatcher {
  private static final Set<String> GENERIC =
      Set.of(
          "java.lang.Throwable",
          "java.lang.Exception",
          "java.lang.RuntimeException",
          "java.lang.Error",
          "java.lang.StackOverflowError");
  private static final List<String> PASS_THROUGH =
      List.of(
          "com.oracle.truffle.api.nodes.ControlFlowException",
          "com.oracle.truffle.api.exception.AbstractTruffleException");

  /** For {@link java.util.ServiceLoader}; Error Prone itself injects the flags constructor. */
  public TruffleGenericCatch() {
    this(ErrorProneFlags.empty());
  }

  @Inject
  public TruffleGenericCatch(ErrorProneFlags flags) {
    super(flags, "catch");
  }

  @Override
  public Description matchCatch(CatchTree tree, VisitorState state) {
    var declared = tree.getParameter().getType();
    var alternatives =
        declared instanceof UnionTypeTree union ? union.getTypeAlternatives() : List.of(declared);
    var generic = false;
    for (var alternative : alternatives) {
      var type = ASTHelpers.getType(alternative);
      if (type != null && GENERIC.contains(type.tsym.getQualifiedName().toString())) generic = true;
    }
    if (!generic || rethrows(tree) || handledEarlier(tree, state)) return Description.NO_MATCH;
    if (skip(state) || !Truffle.inPartialEvaluation(state)) return Description.NO_MATCH;
    return report(tree, "This catch-all swallows ControlFlowException and guest exceptions.");
  }

  /** A preceding catch clause in the same try for ControlFlowException / AbstractTruffleException. */
  private static boolean handledEarlier(CatchTree tree, VisitorState state) {
    if (!(state.getPath().getParentPath().getLeaf() instanceof TryTree attempt)) return false;
    for (var clause : attempt.getCatches()) {
      if (clause == tree) break;
      var type = ASTHelpers.getType(clause.getParameter().getType());
      for (var pass : PASS_THROUGH) {
        if (type instanceof Type.UnionClassType union) {
          for (var alternative : union.getAlternativeTypes()) {
            if (Truffle.isSubtype(alternative, pass, state)) return true;
          }
        } else if (Truffle.isSubtype(type, pass, state)) {
          return true;
        }
      }
    }
    return false;
  }

  /** {@code throw e;} at the top of the handler, or {@code if (e instanceof …) throw e;} first. */
  private static boolean rethrows(CatchTree tree) {
    var name = tree.getParameter().getName();
    for (StatementTree statement : tree.getBlock().getStatements()) {
      if (statement instanceof ThrowTree thrown
          && thrown.getExpression() instanceof IdentifierTree id
          && id.getName().equals(name)) {
        return true;
      }
      if (statement instanceof IfTree branch
          && branch.getCondition().toString().contains("instanceof")
          && branch.getThenStatement().toString().contains("throw " + name)) {
        return true;
      }
    }
    return false;
  }
}
