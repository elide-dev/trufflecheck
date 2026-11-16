/*
 * Copyright (c) 2024-2026 Elide Technologies, Inc.
 * SPDX-License-Identifier: Apache-2.0 OR MIT
 */

package dev.elide.trufflecheck.errorprone;

import com.google.errorprone.BugPattern;
import com.google.errorprone.VisitorState;
import com.google.errorprone.bugpatterns.BugChecker;
import com.google.errorprone.matchers.Description;
import com.sun.source.tree.CompilationUnitTree;
import java.util.Set;
import dev.elide.trufflecheck.Policy;
import javax.inject.Inject;
import com.google.errorprone.ErrorProneFlags;

@BugPattern(
    name = "TruffleHostInterop",
    altNames = "ElideHostInterop",
    summary = "Guest implementations must not use the polyglot embedding API",
    severity = BugPattern.SeverityLevel.ERROR)
public final class TruffleHostInterop extends BugChecker
    implements BugChecker.CompilationUnitTreeMatcher {
  private final Policy policy;
  private final Set<String> owners;
  private final Set<String> scope;

  /** For {@link java.util.ServiceLoader}; Error Prone itself injects the flags constructor. */
  public TruffleHostInterop() {
    this(ErrorProneFlags.empty());
  }

  @Inject
  public TruffleHostInterop(ErrorProneFlags flags) {
    this.policy = Policies.of(flags);
    this.owners = policy.owners("hostinterop");
    this.scope = policy.scope("hostinterop");
  }

  @Override
  public Description matchCompilationUnit(CompilationUnitTree tree, VisitorState state) {
    var pkg = tree.getPackageName();
    if (pkg == null || !Policy.inPackages(pkg.toString(), scope)) return Description.NO_MATCH;
    for (var imported : tree.getImports()) {
      var name = imported.getQualifiedIdentifier().toString();
      if (Policy.inPackages(
          name.endsWith(".*") ? name.substring(0, name.length() - 2) : name, owners)) {
        state.reportMatch(
            buildDescription(imported).setMessage(policy.message("hostinterop")).build());
      }
    }
    return Description.NO_MATCH;
  }
}
