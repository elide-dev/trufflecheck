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
import com.google.errorprone.matchers.Matcher;
import com.google.errorprone.util.ASTHelpers;
import com.sun.source.tree.BlockTree;
import com.sun.source.tree.ClassTree;
import com.sun.source.tree.ExpressionTree;
import com.sun.source.tree.LambdaExpressionTree;
import com.sun.source.tree.MemberReferenceTree;
import com.sun.source.tree.MethodInvocationTree;
import com.sun.source.tree.MethodTree;
import com.sun.source.tree.NewClassTree;
import com.sun.source.tree.Tree;
import com.sun.source.tree.VariableTree;
import com.sun.source.util.TreePath;
import com.sun.source.util.TreePathScanner;
import com.sun.tools.javac.api.JavacTrees;
import com.sun.tools.javac.code.Symbol;
import javax.inject.Inject;
import javax.lang.model.element.Modifier;

/**
 * Build-host state sealed into the image heap: a {@code hostvalue} call or construction evaluated
 * by the static initializer of a class Native Image initializes at build time. Covers static field
 * initializers, static blocks, initializers of anonymous classes created there, and one hop into
 * constructors and methods of the same file that static initialization calls (e.g. {@code static
 * final Foo INSTANCE = new Foo()} whose constructor reads the clock, or an enum constant's
 * constructor).
 */
@BugPattern(
    name = "TruffleHostValueInImageHeap",
    altNames = "ElideHostValueInImageHeap",
    summary = "Build-time static initializers must not capture build-host state",
    severity = BugPattern.SeverityLevel.ERROR)
public final class TruffleHostValueInImageHeap extends TruffleChecker
    implements BugChecker.MethodInvocationTreeMatcher, BugChecker.NewClassTreeMatcher {
  private final Matcher<ExpressionTree> calls;

  /** For {@link java.util.ServiceLoader}; Error Prone itself injects the flags constructor. */
  public TruffleHostValueInImageHeap() {
    this(ErrorProneFlags.empty());
  }

  @Inject
  public TruffleHostValueInImageHeap(ErrorProneFlags flags) {
    super(flags, "hostvalue");
    this.calls = Calls.matcher(policy, "hostvalue");
  }

  @Override
  public Description matchMethodInvocation(MethodInvocationTree tree, VisitorState state) {
    return check(tree, ASTHelpers.getSymbol(tree), state);
  }

  @Override
  public Description matchNewClass(NewClassTree tree, VisitorState state) {
    return check(tree, ASTHelpers.getSymbol(tree), state);
  }

  private Description check(ExpressionTree tree, Symbol.MethodSymbol target, VisitorState state) {
    var owner = staticInitOwner(state.getPath());
    if (owner == null || !policy.initializedAtBuildTime(Truffle.binaryName(owner))) {
      return Description.NO_MATCH;
    }
    if (calls.matches(tree, state)) {
      return skip(state) ? Description.NO_MATCH : report(tree);
    }
    // One hop: a constructor or method of this file that static initialization runs.
    var reached = reachedHostValue(target, state);
    if (reached == null || skip(state)) return Description.NO_MATCH;
    return report(
        tree,
        "Static initialization calls '" + target.owner.getSimpleName() + "."
            + (target.isConstructor() ? "<init>" : target.getSimpleName()) + "', which reads '"
            + reached + "'.");
  }

  /**
   * The class whose static initializer evaluates {@code path}, or null. Anonymous classes created
   * during static initialization run their initializers then too; their methods don't.
   */
  private static Symbol.ClassSymbol staticInitOwner(TreePath path) {
    for (var parent = path.getParentPath(); parent != null; parent = parent.getParentPath()) {
      var tree = parent.getLeaf();
      if (tree instanceof LambdaExpressionTree || tree instanceof MemberReferenceTree) return null;
      if (tree instanceof MethodTree) return null;
      if (tree instanceof VariableTree variable) {
        var symbol = ASTHelpers.getSymbol(variable);
        if (symbol != null
            && symbol.getKind().isField()
            && symbol.getModifiers().contains(Modifier.STATIC)) {
          return enclosingNamed(parent);
        }
      }
      if (tree instanceof BlockTree block
          && block.isStatic()
          && parent.getParentPath().getLeaf() instanceof ClassTree) {
        return enclosingNamed(parent);
      }
      if (tree instanceof ClassTree type) {
        var symbol = ASTHelpers.getSymbol(type);
        if (symbol == null || !symbol.isAnonymous()) return null;
      }
    }
    return null;
  }

  private static Symbol.ClassSymbol enclosingNamed(TreePath path) {
    for (var parent = path; parent != null; parent = parent.getParentPath()) {
      if (parent.getLeaf() instanceof ClassTree type) {
        var symbol = ASTHelpers.getSymbol(type);
        if (symbol != null && !symbol.isAnonymous()) return symbol;
      }
    }
    return null;
  }

  /** The first host-value call in {@code target}'s body, when declared in this file. */
  private String reachedHostValue(Symbol.MethodSymbol target, VisitorState state) {
    if (target == null) return null;
    var unit = state.getPath().getCompilationUnit();
    var declaration = JavacTrees.instance(state.context).getTree(target);
    if (!(declaration instanceof MethodTree method) || method.getBody() == null) return null;
    var path = TreePath.getPath(unit, method.getBody());
    if (path == null) return null;
    var found = new String[1];
    new TreePathScanner<Void, Void>() {
      @Override
      public Void scan(Tree tree, Void unused) {
        if (found[0] != null
            || tree instanceof LambdaExpressionTree
            || tree instanceof ClassTree) {
          return null;
        }
        return super.scan(tree, unused);
      }

      @Override
      public Void visitMethodInvocation(MethodInvocationTree call, Void unused) {
        visit(call);
        return super.visitMethodInvocation(call, unused);
      }

      @Override
      public Void visitNewClass(NewClassTree creation, Void unused) {
        visit(creation);
        return super.visitNewClass(creation, unused);
      }

      private void visit(ExpressionTree call) {
        if (calls.matches(call, state.withPath(getCurrentPath()))) {
          var symbol = ASTHelpers.getSymbol(call);
          found[0] =
              symbol == null
                  ? call.toString()
                  : symbol.owner.getSimpleName() + "."
                      + (symbol.isConstructor() ? "<init>" : symbol.getSimpleName());
        }
      }
    }.scan(path, null);
    return found[0];
  }
}
