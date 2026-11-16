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
import com.sun.source.util.TreeScanner;
import javax.inject.Inject;

/**
 * A {@code @TruffleBoundary} that throws guest exceptions as ordinary control flow without {@code
 * transferToInterpreterOnException = false}: by default, the first exception thrown out of a
 * boundary deoptimizes the caller, so an expected guest error costs a deoptimization.
 */
@BugPattern(
    name = "TruffleBoundaryExceptionPolicy",
    summary = "Boundaries throwing guest exceptions should not deoptimize on them",
    severity = BugPattern.SeverityLevel.SUGGESTION)
public final class TruffleBoundaryExceptionPolicy extends TruffleChecker implements BugChecker.MethodTreeMatcher {
  private static final String GUEST = "com.oracle.truffle.api.exception.AbstractTruffleException";

  /** For {@link java.util.ServiceLoader}; Error Prone itself injects the flags constructor. */
  public TruffleBoundaryExceptionPolicy() {
    this(ErrorProneFlags.empty());
  }

  @Inject
  public TruffleBoundaryExceptionPolicy(ErrorProneFlags flags) {
    super(flags, "boundaryexception");
  }

  @Override
  public Description matchMethod(MethodTree tree, VisitorState state) {
    var symbol = ASTHelpers.getSymbol(tree);
    if (symbol == null || tree.getBody() == null) return Description.NO_MATCH;
    for (var annotation : symbol.getAnnotationMirrors()) {
      if (!annotation.getAnnotationType().toString().equals(Truffle.BOUNDARY)) continue;
      for (var entry : annotation.getElementValues().entrySet()) {
        if (entry.getKey().getSimpleName().contentEquals("transferToInterpreterOnException")) {
          return Description.NO_MATCH;
        }
      }
      var throwsGuest = new boolean[1];
      tree.getBody().accept(
          new TreeScanner<Void, Void>() {
            @Override
            public Void visitThrow(ThrowTree thrown, Void unused) {
              if (thrown.getExpression() instanceof NewClassTree creation
                  && Truffle.isSubtype(ASTHelpers.getType(creation), GUEST, state)) {
                throwsGuest[0] = true;
              }
              return super.visitThrow(thrown, unused);
            }
          },
          null);
      if (!throwsGuest[0] || skip(state)) return Description.NO_MATCH;
      return report(tree, "This boundary throws guest exceptions.");
    }
    return Description.NO_MATCH;
  }
}
