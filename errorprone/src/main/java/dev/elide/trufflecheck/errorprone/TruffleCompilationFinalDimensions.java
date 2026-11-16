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
import com.sun.source.tree.IdentifierTree;
import com.sun.source.tree.VariableTree;
import com.sun.tools.javac.code.Type;
import javax.inject.Inject;

@BugPattern(
    name = "TruffleCompilationFinalDimensions",
    // VerifyCompilationFinal: the Truffle DSL processor's own suppression key for this warning.
    altNames = {"ElideCompilationFinalDimensions", "VerifyCompilationFinal"},
    summary = "@CompilationFinal array fields must declare dimensions explicitly",
    severity = BugPattern.SeverityLevel.ERROR)
public final class TruffleCompilationFinalDimensions extends TruffleChecker
    implements BugChecker.VariableTreeMatcher {
  /** For {@link java.util.ServiceLoader}; Error Prone itself injects the flags constructor. */
  public TruffleCompilationFinalDimensions() {
    this(ErrorProneFlags.empty());
  }

  @Inject
  public TruffleCompilationFinalDimensions(ErrorProneFlags flags) {
    super(flags, "compilationfinal");
  }

  @Override
  public Description matchVariable(VariableTree tree, VisitorState state) {
    if (!(ASTHelpers.getType(tree) instanceof Type.ArrayType) || skip(state)) {
      return Description.NO_MATCH;
    }
    for (var annotation : tree.getModifiers().getAnnotations()) {
      var type = ASTHelpers.getType(annotation);
      if (type == null || !type.tsym.getQualifiedName().contentEquals(Truffle.COMPILATION_FINAL)) {
        continue;
      }
      for (var argument : annotation.getArguments()) {
        if (argument instanceof AssignmentTree assignment
            && assignment.getVariable() instanceof IdentifierTree name
            && name.getName().contentEquals("dimensions")) {
          return Description.NO_MATCH;
        }
      }
      return report(annotation);
    }
    return Description.NO_MATCH;
  }
}
