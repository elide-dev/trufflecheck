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
import com.sun.source.tree.ExpressionStatementTree;
import com.sun.source.tree.MethodInvocationTree;
import com.sun.source.tree.MethodTree;
import com.sun.source.tree.NewClassTree;
import com.sun.tools.javac.code.Symbol;
import com.sun.tools.javac.code.Type;
import dev.elide.trufflecheck.Policy;
import java.util.Set;
import javax.inject.Inject;

/**
 * Stack-capturing exceptions. Library exceptions ({@code stackful.library} packages, the JDK by
 * default) are flagged where they're created; a project's own exception types are flagged once, at
 * their declaration, when they extend a stackful library exception without opting out.
 *
 * <p>Stackless means: a {@code fillInStackTrace()} override on the type or a supertype, a subtype of
 * a {@code stackful.exempt} type (Truffle's own exception bases by default), or, for a declared
 * type, every constructor delegating to the {@code (message, cause, suppression, false)} form.
 */
@BugPattern(
    name = "TruffleStackfulException",
    altNames = "ElideStackfulException",
    summary = "Avoid stack-capturing exceptions",
    severity = BugPattern.SeverityLevel.ERROR)
public final class TruffleStackfulException extends TruffleChecker
    implements BugChecker.NewClassTreeMatcher, BugChecker.ClassTreeMatcher {
  private static final String THROWABLE = "java.lang.Throwable";

  private final Set<String> library;
  private final Set<String> exempt;

  /** For {@link java.util.ServiceLoader}; Error Prone itself injects the flags constructor. */
  public TruffleStackfulException() {
    this(ErrorProneFlags.empty());
  }

  @Inject
  public TruffleStackfulException(ErrorProneFlags flags) {
    super(flags, "stackful");
    this.library = policy.list("stackful.library");
    this.exempt = policy.list("stackful.exempt");
  }

  private boolean isLibrary(Symbol.ClassSymbol type) {
    return Policy.inPackages(type.packge().getQualifiedName().toString(), library);
  }

  /** A supertype chain that is Throwable, without a stackless opt-out along the way. */
  private boolean stackful(Type type, VisitorState state) {
    if (type == null || !Truffle.isSubtype(type, THROWABLE, state)) return false;
    for (var current = type; current != null && current.tsym != null; ) {
      var symbol = (Symbol.ClassSymbol) current.tsym;
      var name = symbol.getQualifiedName().toString();
      if (name.equals(THROWABLE)) return true;
      if (exempt.contains(name) || overridesFillIn(symbol)) return false;
      current = symbol.getSuperclass() instanceof Type.ClassType parent ? parent : null;
    }
    return true;
  }

  private static boolean overridesFillIn(Symbol.ClassSymbol type) {
    for (var member : type.getEnclosedElements()) {
      if (member instanceof Symbol.MethodSymbol method
          && method.getSimpleName().contentEquals("fillInStackTrace")
          && method.getParameters().isEmpty()) {
        return true;
      }
    }
    return false;
  }

  @Override
  public Description matchNewClass(NewClassTree tree, VisitorState state) {
    var type = ASTHelpers.getType(tree.getIdentifier());
    if (type == null || !(type.tsym instanceof Symbol.ClassSymbol symbol) || !isLibrary(symbol)) {
      return Description.NO_MATCH;
    }
    if (tree.getClassBody() != null
        && overridesFillIn(ASTHelpers.getSymbol(tree.getClassBody()))) {
      return Description.NO_MATCH;
    }
    return stackful(type, state) && !skip(state) ? report(tree) : Description.NO_MATCH;
  }

  /** Project exception types extending a stackful library exception. */
  @Override
  public Description matchClass(ClassTree tree, VisitorState state) {
    var symbol = ASTHelpers.getSymbol(tree);
    if (symbol == null || tree.getSimpleName().length() == 0 || tree.getExtendsClause() == null) {
      return Description.NO_MATCH;
    }
    var parent = ASTHelpers.getType(tree.getExtendsClause());
    if (parent == null
        || !(parent.tsym instanceof Symbol.ClassSymbol parentSymbol)
        || !isLibrary(parentSymbol)
        || overridesFillIn(symbol)
        || !stackful(parent, state)
        || allConstructorsStackless(tree)
        || skip(state)) {
      return Description.NO_MATCH;
    }
    return report(tree, "Exception type '" + symbol.getSimpleName() + "' captures a stack.");
  }

  /** Every explicit constructor calls {@code super(message, cause, suppression, false)}. */
  private static boolean allConstructorsStackless(ClassTree tree) {
    var any = false;
    for (var member : tree.getMembers()) {
      if (!(member instanceof MethodTree method)
          || !method.getName().contentEquals("<init>")
          || method.getBody() == null) {
        continue;
      }
      var symbol = ASTHelpers.getSymbol(method);
      if (symbol != null && (symbol.flags() & com.sun.tools.javac.code.Flags.GENERATEDCONSTR) != 0) {
        return false;
      }
      any = true;
      var statements = method.getBody().getStatements();
      if (statements.isEmpty()
          || !(statements.get(0) instanceof ExpressionStatementTree first)
          || !(first.getExpression() instanceof MethodInvocationTree call)) {
        return false;
      }
      var name = ASTHelpers.getSymbol(call);
      var args = call.getArguments();
      if (name == null || !name.isConstructor()) return false;
      // this(...) defers to a sibling constructor, which is checked on its own.
      if (name.owner == ASTHelpers.getSymbol(tree)) continue;
      if (args.size() != 4 || !Boolean.FALSE.equals(ASTHelpers.constValue(args.get(3)))) {
        return false;
      }
    }
    return any;
  }
}
