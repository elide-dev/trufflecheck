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
import com.sun.source.tree.ClassTree;
import com.sun.source.tree.MemberReferenceTree;
import com.sun.source.tree.NewClassTree;
import com.sun.source.tree.Tree;
import com.sun.tools.javac.code.Symbol;
import java.util.Set;
import javax.inject.Inject;

@BugPattern(
    name = "TruffleForbiddenCollection",
    altNames = "ElideForbiddenCollection",
    summary = "Avoid prohibited collection implementations",
    severity = BugPattern.SeverityLevel.ERROR)
public final class TruffleForbiddenCollection extends TruffleChecker
    implements BugChecker.NewClassTreeMatcher,
        BugChecker.MemberReferenceTreeMatcher,
        BugChecker.ClassTreeMatcher {
  private final Set<String> owners;

  /** For {@link java.util.ServiceLoader}; Error Prone itself injects the flags constructor. */
  public TruffleForbiddenCollection() {
    this(ErrorProneFlags.empty());
  }

  @Inject
  public TruffleForbiddenCollection(ErrorProneFlags flags) {
    super(flags, "collections");
    this.owners = policy.owners("collections");
  }

  private boolean forbidden(Symbol type) {
    return type != null && owners.contains(type.getQualifiedName().toString());
  }

  private Description check(Tree tree, Symbol constructor, VisitorState state) {
    if (!(constructor instanceof Symbol.MethodSymbol method) || !method.isConstructor()) {
      return Description.NO_MATCH;
    }
    var owner = method.owner;
    if (tree instanceof NewClassTree creation && creation.getClassBody() != null) {
      owner = ASTHelpers.getType(creation.getIdentifier()).tsym;
    }
    return forbidden(owner) && !skip(state) ? report(tree) : Description.NO_MATCH;
  }

  @Override
  public Description matchNewClass(NewClassTree tree, VisitorState state) {
    return check(tree, ASTHelpers.getSymbol(tree), state);
  }

  @Override
  public Description matchMemberReference(MemberReferenceTree tree, VisitorState state) {
    return check(tree, ASTHelpers.getSymbol(tree), state);
  }

  /** {@code class Q extends LinkedList<X> {}}: subclasses construct the parent implicitly. */
  @Override
  public Description matchClass(ClassTree tree, VisitorState state) {
    if (tree.getSimpleName().length() == 0 || tree.getExtendsClause() == null) {
      return Description.NO_MATCH;
    }
    var parent = ASTHelpers.getType(tree.getExtendsClause());
    return parent != null && forbidden(parent.tsym) && !skip(state)
        ? report(tree)
        : Description.NO_MATCH;
  }
}
