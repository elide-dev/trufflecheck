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
import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.tree.ImportTree;
import com.sun.source.tree.MemberSelectTree;
import com.sun.tools.javac.code.Symbol;
import dev.elide.trufflecheck.Policy;
import java.util.Set;
import javax.inject.Inject;

/**
 * The polyglot embedding API in guest-implementation packages ({@code hostinterop.scope}): imports,
 * and fully qualified references that skip the import. {@code hostinterop.allow} lists types guest
 * code legitimately names (e.g. {@code SandboxPolicy} in a language registration).
 */
@BugPattern(
    name = "TruffleHostInterop",
    altNames = "ElideHostInterop",
    summary = "Guest implementations must not use the polyglot embedding API",
    severity = BugPattern.SeverityLevel.ERROR)
public final class TruffleHostInterop extends TruffleChecker
    implements BugChecker.CompilationUnitTreeMatcher, BugChecker.MemberSelectTreeMatcher {
  private final Set<String> owners;
  private final Set<String> scope;
  private final Set<String> allow;

  /** For {@link java.util.ServiceLoader}; Error Prone itself injects the flags constructor. */
  public TruffleHostInterop() {
    this(ErrorProneFlags.empty());
  }

  @Inject
  public TruffleHostInterop(ErrorProneFlags flags) {
    super(flags, "hostinterop");
    this.owners = policy.owners("hostinterop");
    this.scope = policy.scope("hostinterop");
    this.allow = policy.list("hostinterop.allow");
  }

  private boolean banned(String name) {
    return Policy.inPackages(name, owners) && !allow.contains(name);
  }

  private boolean inScope(VisitorState state) {
    var pkg = state.getPath().getCompilationUnit().getPackageName();
    return pkg != null && Policy.inPackages(pkg.toString(), scope);
  }

  @Override
  public Description matchCompilationUnit(CompilationUnitTree tree, VisitorState state) {
    if (!inScope(state) || skip(state)) return Description.NO_MATCH;
    for (var imported : tree.getImports()) {
      var name = imported.getQualifiedIdentifier().toString();
      if (banned(name.endsWith(".*") ? name.substring(0, name.length() - 2) : name)) {
        state.reportMatch(report(imported));
      }
    }
    return Description.NO_MATCH;
  }

  /** {@code org.graalvm.polyglot.Value v;}: the outermost qualified type reference, not imports. */
  @Override
  public Description matchMemberSelect(MemberSelectTree tree, VisitorState state) {
    if (!(ASTHelpers.getSymbol(tree) instanceof Symbol.ClassSymbol type)
        || !(ASTHelpers.getSymbol(tree.getExpression()) instanceof Symbol.PackageSymbol)) {
      return Description.NO_MATCH;
    }
    for (var parent : state.getPath()) {
      if (parent instanceof ImportTree) return Description.NO_MATCH;
    }
    if (!banned(type.getQualifiedName().toString()) || !inScope(state) || skip(state)) {
      return Description.NO_MATCH;
    }
    return report(tree);
  }
}
