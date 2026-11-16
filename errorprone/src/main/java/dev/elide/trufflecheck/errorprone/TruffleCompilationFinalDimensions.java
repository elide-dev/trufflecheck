/*
 * Copyright (c) 2024-2026 Elide Technologies, Inc.
 * SPDX-License-Identifier: Apache-2.0 OR MIT
 */

package dev.elide.trufflecheck.errorprone;

import com.google.errorprone.BugPattern;
import com.google.errorprone.VisitorState;
import com.google.errorprone.bugpatterns.BugChecker;
import com.google.errorprone.matchers.Description;
import com.google.errorprone.util.ASTHelpers;
import com.sun.source.tree.AssignmentTree;
import com.sun.source.tree.IdentifierTree;
import com.sun.source.tree.VariableTree;
import com.sun.tools.javac.code.Type;
import dev.elide.trufflecheck.Policy;
import javax.inject.Inject;
import com.google.errorprone.ErrorProneFlags;

@BugPattern(
    name = "TruffleCompilationFinalDimensions",
    altNames = "ElideCompilationFinalDimensions",
    summary = "@CompilationFinal array fields must declare dimensions",
    severity = BugPattern.SeverityLevel.ERROR)
public final class TruffleCompilationFinalDimensions extends BugChecker
    implements BugChecker.VariableTreeMatcher {
  private final Policy policy;

  /** For {@link java.util.ServiceLoader}; Error Prone itself injects the flags constructor. */
  public TruffleCompilationFinalDimensions() {
    this(ErrorProneFlags.empty());
  }

  @Inject
  public TruffleCompilationFinalDimensions(ErrorProneFlags flags) {
    this.policy = Policies.of(flags);
  }

  private static final String ANNOTATION =
      "com.oracle.truffle.api.CompilerDirectives.CompilationFinal";

  @Override
  public Description matchVariable(VariableTree tree, VisitorState state) {
    if (!(ASTHelpers.getType(tree) instanceof Type.ArrayType)) return Description.NO_MATCH;
    for (var annotation : tree.getModifiers().getAnnotations()) {
      var type = ASTHelpers.getType(annotation);
      if (type == null || !type.tsym.getQualifiedName().contentEquals(ANNOTATION)) continue;
      for (var argument : annotation.getArguments()) {
        if (argument instanceof AssignmentTree assignment
            && assignment.getVariable() instanceof IdentifierTree name
            && name.getName().contentEquals("dimensions")) {
          return Description.NO_MATCH;
        }
      }
      return buildDescription(annotation).setMessage(policy.message("compilationfinal")).build();
    }
    return Description.NO_MATCH;
  }
}
