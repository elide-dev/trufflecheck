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
import com.sun.source.tree.IdentifierTree;
import com.sun.source.tree.ImportTree;
import com.sun.source.tree.MemberSelectTree;
import com.sun.source.tree.Tree;
import com.sun.tools.javac.code.Symbol;
import dev.elide.trufflecheck.Policy;
import java.util.Set;
import javax.inject.Inject;

/**
 * The polyglot embedding API in guest-implementation packages ({@code hostinterop.scope}): every
 * reference to a banned type, by simple or fully qualified name, but not imports. Reporting uses
 * means a reviewed crossing (say, {@code Context.getCurrent()} in one boundary method) can be
 * suppressed with {@code @SuppressWarnings("TruffleHostInterop")} where the reason lives, while
 * other uses in the file stay reported. {@code hostinterop.allow} lists types guest code
 * legitimately names (e.g. {@code SandboxPolicy} in a language registration).
 */
@BugPattern(
    name = "TruffleHostInterop",
    altNames = "ElideHostInterop",
    summary = "Guest implementations must not use the polyglot embedding API",
    severity = BugPattern.SeverityLevel.ERROR)
public final class TruffleHostInterop extends TruffleChecker
    implements BugChecker.IdentifierTreeMatcher, BugChecker.MemberSelectTreeMatcher {
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

  @Override
  public Description matchIdentifier(IdentifierTree tree, VisitorState state) {
    return check(tree, state);
  }

  @Override
  public Description matchMemberSelect(MemberSelectTree tree, VisitorState state) {
    return check(tree, state);
  }

  private Description check(Tree tree, VisitorState state) {
    if (!(ASTHelpers.getSymbol(tree) instanceof Symbol.ClassSymbol type)) return Description.NO_MATCH;
    var name = type.getQualifiedName().toString();
    if (!Policy.inPackages(name, owners) || allow.contains(name)) return Description.NO_MATCH;
    // One finding per reference: `Value.Builder` reports the outermost type only.
    var parent = state.getPath().getParentPath().getLeaf();
    if (parent instanceof MemberSelectTree select
        && select.getExpression() == tree
        && ASTHelpers.getSymbol(select) instanceof Symbol.ClassSymbol) {
      return Description.NO_MATCH;
    }
    for (var enclosing : state.getPath()) {
      if (enclosing instanceof ImportTree) return Description.NO_MATCH;
    }
    var pkg = state.getPath().getCompilationUnit().getPackageName();
    if (pkg == null || !Policy.inPackages(pkg.toString(), scope) || skip(state)) return Description.NO_MATCH;
    return report(tree, "'" + type.getSimpleName() + "' is part of the embedding API.");
  }
}
