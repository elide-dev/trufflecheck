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
import com.sun.source.tree.AnnotationTree;
import com.sun.source.tree.AssignmentTree;
import com.sun.source.tree.ExpressionTree;
import com.sun.source.tree.LiteralTree;
import com.sun.source.tree.NewArrayTree;
import java.util.ArrayList;
import java.util.List;
import javax.inject.Inject;

/**
 * {@code @SuppressWarnings("truffle")} or {@code ("all")} in Truffle code. The DSL's warnings
 * (missing {@code @Shared}/{@code @Exclusive}, {@code neverDefault}, inlining candidates, cache
 * limits, …) are performance and footprint findings; suppress the specific key ({@code
 * "truffle-sharing"}, {@code "truffle-inlining"}, … from TruffleSuppressedWarnings) instead.
 */
@BugPattern(
    name = "TruffleDslSuppressions",
    summary = "Suppress specific Truffle DSL warnings, not all of them",
    severity = BugPattern.SeverityLevel.ERROR)
public final class TruffleDslSuppressions extends TruffleChecker implements BugChecker.CompilationUnitTreeMatcher {
  /** For {@link java.util.ServiceLoader}; Error Prone itself injects the flags constructor. */
  public TruffleDslSuppressions() {
    this(ErrorProneFlags.empty());
  }

  @Inject
  public TruffleDslSuppressions(ErrorProneFlags flags) {
    super(flags, "suppressions");
  }

  /**
   * Scans from the compilation unit: {@code @SuppressWarnings("all")} would otherwise suppress this
   * check on the very element it annotates.
   */
  @Override
  public Description matchCompilationUnit(com.sun.source.tree.CompilationUnitTree unit, VisitorState state) {
    var truffle =
        unit.getImports().stream()
            .anyMatch(i -> i.getQualifiedIdentifier().toString().startsWith("com.oracle.truffle."));
    if (!truffle || skip(state)) return Description.NO_MATCH;
    unit.accept(
        new com.sun.source.util.TreeScanner<Void, Void>() {
          @Override
          public Void visitAnnotation(AnnotationTree tree, Void unused) {
            var type = ASTHelpers.getType(tree);
            if (type != null && type.tsym.getQualifiedName().contentEquals("java.lang.SuppressWarnings")) {
              for (var value : values(tree)) {
                if (value.equals("truffle") || value.equals("all")) {
                  state.reportMatch(report(tree, "@SuppressWarnings(\"" + value + "\") hides every Truffle DSL warning."));
                  break;
                }
              }
            }
            return super.visitAnnotation(tree, unused);
          }
        },
        null);
    return Description.NO_MATCH;
  }

  private static List<String> values(AnnotationTree tree) {
    var out = new ArrayList<String>();
    for (var argument : tree.getArguments()) {
      ExpressionTree value = argument instanceof AssignmentTree assignment ? assignment.getExpression() : argument;
      if (value instanceof NewArrayTree array) {
        for (var element : array.getInitializers()) {
          if (element instanceof LiteralTree literal) out.add(String.valueOf(literal.getValue()));
        }
      } else if (value instanceof LiteralTree literal) {
        out.add(String.valueOf(literal.getValue()));
      }
    }
    return out;
  }
}
