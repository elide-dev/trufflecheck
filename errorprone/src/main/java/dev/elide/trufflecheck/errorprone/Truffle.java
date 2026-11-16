/*
 * Copyright (c) 2024-2026 Elide Technologies, Inc.
 * SPDX-License-Identifier: Apache-2.0 OR MIT
 */

package dev.elide.trufflecheck.errorprone;

import com.google.errorprone.VisitorState;
import com.google.errorprone.util.ASTHelpers;
import com.sun.source.tree.BlockTree;
import com.sun.source.tree.ClassTree;
import com.sun.source.tree.ExpressionStatementTree;
import com.sun.source.tree.ExpressionTree;
import com.sun.source.tree.IfTree;
import com.sun.source.tree.LambdaExpressionTree;
import com.sun.source.tree.MethodInvocationTree;
import com.sun.source.tree.MethodTree;
import com.sun.source.tree.ParenthesizedTree;
import com.sun.source.tree.StatementTree;
import com.sun.source.tree.Tree;
import com.sun.source.tree.UnaryTree;
import com.sun.source.util.TreePath;
import com.sun.source.tree.ArrayAccessTree;
import com.sun.source.tree.AssignmentTree;
import com.sun.source.tree.IdentifierTree;
import com.sun.source.tree.MemberSelectTree;
import com.sun.tools.javac.code.Symbol;
import com.sun.tools.javac.code.Type;
import java.util.function.Predicate;

/** Truffle API names and the shared notion of "code partial evaluation may reach". */
final class Truffle {
  static final String NODE = "com.oracle.truffle.api.nodes.Node";
  static final String NODE_INTERFACE = "com.oracle.truffle.api.nodes.NodeInterface";
  static final String ROOT_NODE = "com.oracle.truffle.api.nodes.RootNode";
  static final String CHILD = "com.oracle.truffle.api.nodes.Node.Child";
  static final String CHILDREN = "com.oracle.truffle.api.nodes.Node.Children";
  static final String DIRECTIVES = "com.oracle.truffle.api.CompilerDirectives";
  static final String ASSERTS = "com.oracle.truffle.api.CompilerAsserts";
  static final String HOST_DIRECTIVES = "com.oracle.truffle.api.HostCompilerDirectives";
  static final String SPECIALIZATION = "com.oracle.truffle.api.dsl.Specialization";
  static final String FALLBACK = "com.oracle.truffle.api.dsl.Fallback";
  static final String BOUNDARY = DIRECTIVES + ".TruffleBoundary";
  static final String COMPILATION_FINAL = DIRECTIVES + ".CompilationFinal";
  static final String EXPLODE_LOOP = "com.oracle.truffle.api.nodes.ExplodeLoop";
  static final String EXPORT_LIBRARY = "com.oracle.truffle.api.library.ExportLibrary";
  static final String EXPORT_MESSAGE = "com.oracle.truffle.api.library.ExportMessage";
  static final String GENERATED_BY = "com.oracle.truffle.api.dsl.GeneratedBy";
  static final String VIRTUAL_FRAME = "com.oracle.truffle.api.frame.VirtualFrame";

  private Truffle() {}

  static boolean isSubtype(Type type, String supertype, VisitorState state) {
    var target = state.getTypeFromString(supertype);
    return type != null && target != null && ASTHelpers.isSubtype(type, target, state);
  }

  /** Nodes and node interfaces: anything a {@code @Child} field may hold. */
  static boolean isNode(Type type, VisitorState state) {
    return isSubtype(type, NODE_INTERFACE, state) || isSubtype(type, NODE, state);
  }

  /** Inside a class carrying Truffle DSL's {@code @GeneratedBy}. */
  static boolean inGeneratedCode(VisitorState state) {
    for (var tree : state.getPath()) {
      if (tree instanceof ClassTree type
          && ASTHelpers.hasAnnotation(ASTHelpers.getSymbol(type), GENERATED_BY, state)) {
        return true;
      }
    }
    return false;
  }

  /** {@code CompilerDirectives.<name>()} with no receiver other than the class. */
  static boolean isDirective(ExpressionTree tree, String owner, VisitorState state, String... names) {
    if (!(tree instanceof MethodInvocationTree call)) return false;
    var symbol = ASTHelpers.getSymbol(call);
    if (symbol == null || !symbol.owner.getQualifiedName().contentEquals(owner)) return false;
    for (var name : names) {
      if (symbol.getSimpleName().contentEquals(name)) return true;
    }
    return false;
  }

  static boolean isTransfer(StatementTree statement, VisitorState state) {
    return statement instanceof ExpressionStatementTree expression
        && isDirective(
            expression.getExpression(),
            DIRECTIVES,
            state,
            "transferToInterpreter",
            "transferToInterpreterAndInvalidate");
  }

  private static ExpressionTree strip(ExpressionTree tree) {
    while (tree instanceof ParenthesizedTree parens) tree = parens.getExpression();
    return tree;
  }

  /**
   * {@code inInterpreter()}, {@code HostCompilerDirectives.inInterpreterFastPath()} or {@code
   * !inCompiledCode()}: the guarded branch never compiles.
   */
  private static boolean interpreterOnly(ExpressionTree condition, VisitorState state) {
    condition = strip(condition);
    if (isDirective(condition, DIRECTIVES, state, "inInterpreter")
        || isDirective(condition, HOST_DIRECTIVES, state, "inInterpreterFastPath")) {
      return true;
    }
    return condition instanceof UnaryTree not
        && not.getKind() == Tree.Kind.LOGICAL_COMPLEMENT
        && isDirective(strip(not.getExpression()), DIRECTIVES, state, "inCompiledCode");
  }

  /** {@code if (inCompiledCode()) … else <interpreter-only>}. */
  private static boolean compiledOnly(ExpressionTree condition, VisitorState state) {
    return isDirective(strip(condition), DIRECTIVES, state, "inCompiledCode");
  }

  /** The method or lambda whose body holds the current path, or null. */
  static MethodTree enclosingMethod(VisitorState state) {
    for (var tree : state.getPath()) {
      if (tree instanceof LambdaExpressionTree) return null;
      if (tree instanceof MethodTree method) return method;
      if (tree instanceof ClassTree) return null;
    }
    return null;
  }

  /**
   * Whether partial evaluation may reach the current path: a method of a {@code Node} (static or
   * not), or an {@code @ExportMessage} method, that is not a {@code @TruffleBoundary}, outside a
   * lambda, not after {@code transferToInterpreter*()} in an enclosing block, and not in a branch
   * guarded by {@code inInterpreter()}.
   */
  static boolean inPartialEvaluation(VisitorState state) {
    return inPartialEvaluation(state.getPath(), state);
  }

  static boolean inPartialEvaluation(TreePath path, VisitorState state) {
    MethodTree method = null;
    ClassTree owner = null;
    Tree child = path.getLeaf();
    for (var parent = path.getParentPath(); parent != null; parent = parent.getParentPath()) {
      var tree = parent.getLeaf();
      if (tree instanceof LambdaExpressionTree) return false;
      if (method == null && tree instanceof BlockTree block) {
        for (var statement : block.getStatements()) {
          if (statement == child) break;
          if (isTransfer(statement, state)) return false;
        }
      }
      if (method == null
          && tree instanceof IfTree branch
          && ((branch.getThenStatement() == child && interpreterOnly(branch.getCondition(), state))
              || (branch.getElseStatement() == child && compiledOnly(branch.getCondition(), state)))) {
        return false;
      }
      // Arguments of `throw CompilerDirectives.shouldNotReachHere(…)` only run on a dead path.
      if (method == null
          && tree instanceof MethodInvocationTree call
          && call != path.getLeaf()
          && isDirective(call, DIRECTIVES, state, "shouldNotReachHere")) {
        return false;
      }
      if (method == null && tree instanceof MethodTree found) method = found;
      if (tree instanceof ClassTree found) {
        owner = found;
        break;
      }
      child = tree;
    }
    if (method == null || owner == null) return false;
    var symbol = ASTHelpers.getSymbol(method);
    if (symbol == null || ASTHelpers.hasAnnotation(symbol, BOUNDARY, state)) return false;
    if (neverCompiled(method, state)) return false;
    var type = ASTHelpers.getSymbol(owner);
    if (type == null) return false;
    if (isNode(type.type, state)) return true;
    // DSL specializations outside Node subclasses (e.g. Bytecode DSL operations) still compile.
    if (ASTHelpers.hasAnnotation(symbol, SPECIALIZATION, state)
        || ASTHelpers.hasAnnotation(symbol, FALLBACK, state)) {
      return true;
    }
    return ASTHelpers.hasAnnotation(symbol, EXPORT_MESSAGE, state)
        || (ASTHelpers.hasAnnotation(type, EXPORT_MESSAGE, state) && !symbol.isStatic())
        || (ASTHelpers.hasAnnotation(type, EXPORT_LIBRARY, state)
            && ASTHelpers.hasAnnotation(symbol, EXPORT_MESSAGE, state));
  }

  /** A method that opens with {@code CompilerAsserts.neverPartOfCompilation()}. */
  private static boolean neverCompiled(MethodTree method, VisitorState state) {
    var body = method.getBody();
    if (body == null || body.getStatements().isEmpty()) return false;
    return body.getStatements().get(0) instanceof ExpressionStatementTree first
        && isDirective(first.getExpression(), ASSERTS, state, "neverPartOfCompilation");
  }

  /**
   * Whether a statement before the current one, in any enclosing block up to the method, satisfies
   * {@code test} (e.g. a dominating {@code transferToInterpreterAndInvalidate()}).
   */
  static boolean precededBy(TreePath path, Predicate<StatementTree> test) {
    Tree child = path.getLeaf();
    for (var parent = path.getParentPath(); parent != null; parent = parent.getParentPath()) {
      var tree = parent.getLeaf();
      if (tree instanceof MethodTree || tree instanceof ClassTree || tree instanceof LambdaExpressionTree) {
        return false;
      }
      if (tree instanceof BlockTree block) {
        for (var statement : block.getStatements()) {
          if (statement == child) break;
          if (test.test(statement)) return true;
        }
      }
      child = tree;
    }
    return false;
  }

  /** Whether a statement is, or is an {@code if} whose then-branch holds, a statement matching {@code test}. */
  static boolean containsStatement(StatementTree statement, Predicate<StatementTree> test) {
    if (test.test(statement)) return true;
    if (statement instanceof IfTree branch) {
      var then = branch.getThenStatement();
      if (then instanceof BlockTree block) {
        for (var inner : block.getStatements()) {
          if (containsStatement(inner, test)) return true;
        }
        return false;
      }
      return containsStatement(then, test);
    }
    return false;
  }

  /** The field a (possibly qualified) reference names, or null. */
  static Symbol.VarSymbol field(ExpressionTree tree) {
    tree = strip(tree);
    if (!(tree instanceof IdentifierTree) && !(tree instanceof MemberSelectTree)) return null;
    return ASTHelpers.getSymbol(tree) instanceof Symbol.VarSymbol variable
            && variable.getKind().isField()
        ? variable
        : null;
  }

  /** For {@code a[i][j]}: the array expression at the root and the element depth (2). */
  record ArrayRoot(ExpressionTree root, int depth) {}

  static ArrayRoot arrayRoot(ExpressionTree tree) {
    var depth = 0;
    tree = strip(tree);
    while (tree instanceof ArrayAccessTree access) {
      depth++;
      tree = strip(access.getExpression());
    }
    return new ArrayRoot(tree, depth);
  }

  /** {@code @CompilationFinal(dimensions = n)}: n, -1 when omitted (full depth), or null without it. */
  static Integer compilationFinalDimensions(Symbol symbol, VisitorState state) {
    for (var compound : symbol.getAnnotationMirrors()) {
      if (!compound.getAnnotationType().toString().equals(COMPILATION_FINAL)) continue;
      for (var entry : compound.getElementValues().entrySet()) {
        if (entry.getKey().getSimpleName().contentEquals("dimensions")
            && entry.getValue().getValue() instanceof Integer value) {
          return value;
        }
      }
      return -1;
    }
    return null;
  }

  /** Inside a constructor or initializer of {@code owner} (instance or static). */
  static boolean inInitializerOf(TreePath path, Symbol owner) {
    for (var parent = path.getParentPath(); parent != null; parent = parent.getParentPath()) {
      var tree = parent.getLeaf();
      if (tree instanceof LambdaExpressionTree) return false;
      if (tree instanceof MethodTree method) {
        var symbol = ASTHelpers.getSymbol(method);
        return symbol != null && symbol.isConstructor() && symbol.owner == owner;
      }
      if (tree instanceof ClassTree type) return ASTHelpers.getSymbol(type) == owner;
    }
    return false;
  }

  /** The binary name of a class symbol. */
  static String binaryName(Symbol.ClassSymbol symbol) {
    return symbol.flatName().toString();
  }
}
