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
import com.sun.source.tree.MemberReferenceTree;
import com.sun.source.tree.MemberSelectTree;
import com.sun.source.tree.MethodInvocationTree;
import com.sun.source.tree.NewClassTree;
import com.sun.source.tree.Tree;
import com.sun.tools.javac.code.Symbol;
import dev.elide.trufflecheck.Policy;
import java.util.Set;
import javax.inject.Inject;

/**
 * Uses of {@code @Deprecated} Truffle and GraalVM SDK API ({@code deprecated.scope} packages), with
 * the migration from {@code deprecated.hint.<Type>} or {@code deprecated.hint.<Type>#<member>} when
 * the policy has one. Removed APIs make these upgrade blockers; the hint is the value over javac's
 * own deprecation lint.
 */
@BugPattern(
    name = "TruffleDeprecatedApi",
    summary = "Deprecated Truffle API",
    severity = BugPattern.SeverityLevel.WARNING)
public final class TruffleDeprecatedApi extends TruffleChecker
    implements BugChecker.MethodInvocationTreeMatcher,
        BugChecker.NewClassTreeMatcher,
        BugChecker.MemberReferenceTreeMatcher,
        BugChecker.IdentifierTreeMatcher,
        BugChecker.MemberSelectTreeMatcher {
  private final Set<String> scope;

  /** For {@link java.util.ServiceLoader}; Error Prone itself injects the flags constructor. */
  public TruffleDeprecatedApi() {
    this(ErrorProneFlags.empty());
  }

  @Inject
  public TruffleDeprecatedApi(ErrorProneFlags flags) {
    super(flags, "deprecated");
    this.scope = policy.scope("deprecated");
  }

  private Description check(Tree tree, Symbol symbol, VisitorState state) {
    if (symbol == null || !ASTHelpers.hasAnnotation(symbol, "java.lang.Deprecated", state)) return Description.NO_MATCH;
    var type = symbol instanceof Symbol.ClassSymbol c ? c : symbol.enclClass();
    if (type == null || !Policy.inPackages(type.packge().getQualifiedName().toString(), scope)) return Description.NO_MATCH;
    for (var parent : state.getPath()) {
      if (parent instanceof ImportTree) return Description.NO_MATCH;
    }
    // Inside a deprecated declaration (or the API's own sources): not a migration target.
    if (ASTHelpers.findEnclosingNode(state.getPath(), com.sun.source.tree.ClassTree.class) != null
        && ASTHelpers.hasAnnotation(
            ASTHelpers.getSymbol(ASTHelpers.findEnclosingNode(state.getPath(), com.sun.source.tree.ClassTree.class)),
            "java.lang.Deprecated",
            state)) {
      return Description.NO_MATCH;
    }
    if (skip(state)) return Description.NO_MATCH;
    var typeName = type.getQualifiedName().toString();
    var member = symbol instanceof Symbol.ClassSymbol ? null : symbol.getSimpleName().toString();
    var name = member == null ? type.getSimpleName().toString() : type.getSimpleName() + "." + member;
    var hint = member == null ? null : policy.list("deprecated.hint." + typeName + "#" + member);
    if (hint == null || hint.isEmpty()) hint = policy.list("deprecated.hint." + typeName);
    var detail = "'" + name + "' is deprecated" + (hint.isEmpty() ? "." : ": " + String.join(", ", hint) + ".");
    return report(tree, detail);
  }

  @Override
  public Description matchMethodInvocation(MethodInvocationTree tree, VisitorState state) {
    return check(tree, ASTHelpers.getSymbol(tree), state);
  }

  @Override
  public Description matchNewClass(NewClassTree tree, VisitorState state) {
    return check(tree, ASTHelpers.getSymbol(tree), state);
  }

  @Override
  public Description matchMemberReference(MemberReferenceTree tree, VisitorState state) {
    return check(tree, ASTHelpers.getSymbol(tree), state);
  }

  /** Type references (fields, parameters, locals): deprecated classes only. */
  @Override
  public Description matchIdentifier(IdentifierTree tree, VisitorState state) {
    var symbol = ASTHelpers.getSymbol(tree);
    return symbol instanceof Symbol.ClassSymbol ? check(tree, symbol, state) : Description.NO_MATCH;
  }

  @Override
  public Description matchMemberSelect(MemberSelectTree tree, VisitorState state) {
    var symbol = ASTHelpers.getSymbol(tree);
    return symbol instanceof Symbol.ClassSymbol ? check(tree, symbol, state) : Description.NO_MATCH;
  }
}
