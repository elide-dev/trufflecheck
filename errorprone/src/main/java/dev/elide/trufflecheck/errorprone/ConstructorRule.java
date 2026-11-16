/*
 * Copyright (c) 2024-2026 Elide Technologies, Inc.
 * SPDX-License-Identifier: Apache-2.0 OR MIT
 */

package dev.elide.trufflecheck.errorprone;

import com.google.errorprone.ErrorProneFlags;
import com.google.errorprone.VisitorState;
import com.google.errorprone.bugpatterns.BugChecker;
import com.google.errorprone.matchers.Description;
import com.google.errorprone.util.ASTHelpers;
import com.sun.source.tree.ClassTree;
import com.sun.source.tree.ExpressionTree;
import com.sun.source.tree.MemberReferenceTree;
import com.sun.source.tree.MethodInvocationTree;
import com.sun.source.tree.MethodTree;
import com.sun.source.tree.NewClassTree;
import com.sun.source.tree.Tree;
import com.sun.tools.javac.code.Flags;
import com.sun.tools.javac.code.Symbol;
import dev.elide.trufflecheck.Policy;
import java.util.List;
import java.util.Set;
import dev.elide.trufflecheck.Policy;
import com.google.errorprone.ErrorProneFlags;

abstract class ConstructorRule extends BugChecker
    implements BugChecker.NewClassTreeMatcher,
        BugChecker.MethodInvocationTreeMatcher,
        BugChecker.MemberReferenceTreeMatcher,
        BugChecker.ClassTreeMatcher {
  private final String rule;
  private final Set<String> owners;

  private final Policy policy;

  ConstructorRule(ErrorProneFlags flags, String rule) {
    this.policy = Policies.of(flags);
    this.rule = rule;
    this.owners = policy.owners(rule);
  }

  private Description check(Tree tree, Symbol symbol, List<? extends ExpressionTree> args) {
    if (!(symbol instanceof Symbol.MethodSymbol method) || !method.isConstructor()) {
      return Description.NO_MATCH;
    }
    var owner = method.owner;
    if (tree instanceof NewClassTree creation && creation.getClassBody() != null) {
      owner = ASTHelpers.getType(creation.getIdentifier()).tsym;
    }
    if (!owners.contains(owner.getQualifiedName().toString())) return Description.NO_MATCH;
    if (rule.equals("stackful")
        && args != null
        && args.size() == 4
        && Boolean.FALSE.equals(ASTHelpers.constValue(args.get(3)))) {
      return Description.NO_MATCH;
    }
    return buildDescription(tree).setMessage(policy.message(rule)).build();
  }

  @Override
  public Description matchClass(ClassTree tree, VisitorState state) {
    if (tree.getSimpleName().length() == 0 || tree.getExtendsClause() == null)
      return Description.NO_MATCH;
    var parent = ASTHelpers.getType(tree.getExtendsClause());
    if (parent == null || !owners.contains(parent.tsym.getQualifiedName().toString()))
      return Description.NO_MATCH;
    for (var member : tree.getMembers()) {
      if (member instanceof MethodTree method
          && (ASTHelpers.getSymbol(method).flags() & Flags.GENERATEDCONSTR) != 0) {
        return buildDescription(tree).setMessage(policy.message(rule)).build();
      }
    }
    return Description.NO_MATCH;
  }

  @Override
  public Description matchNewClass(NewClassTree tree, VisitorState state) {
    return check(tree, ASTHelpers.getSymbol(tree), tree.getArguments());
  }

  @Override
  public Description matchMethodInvocation(MethodInvocationTree tree, VisitorState state) {
    return check(tree, ASTHelpers.getSymbol(tree), tree.getArguments());
  }

  @Override
  public Description matchMemberReference(MemberReferenceTree tree, VisitorState state) {
    return check(tree, ASTHelpers.getSymbol(tree), null);
  }
}
