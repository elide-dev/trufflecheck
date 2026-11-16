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
import com.sun.source.tree.ArrayAccessTree;
import com.sun.source.tree.ExpressionTree;
import com.sun.source.tree.MethodTree;
import com.sun.source.tree.SwitchExpressionTree;
import com.sun.source.tree.SwitchTree;
import com.sun.source.tree.VariableTree;
import com.sun.source.util.TreeScanner;
import com.sun.tools.javac.code.Symbol;
import java.util.HashSet;
import java.util.Set;
import javax.inject.Inject;

/**
 * A hand-written bytecode dispatch loop (a switch over an element of a {@code @CompilationFinal}
 * array, inside a loop) without {@code @HostCompilerDirectives.BytecodeInterpreterSwitch}: host
 * compilation then doesn't optimize the interpreter's dispatch for the native image.
 */
@BugPattern(
    name = "TruffleBytecodeSwitch",
    summary = "Bytecode dispatch loops should be @BytecodeInterpreterSwitch",
    severity = BugPattern.SeverityLevel.SUGGESTION)
public final class TruffleBytecodeSwitch extends TruffleChecker implements BugChecker.MethodTreeMatcher {
  private static final String SWITCH = "com.oracle.truffle.api.HostCompilerDirectives.BytecodeInterpreterSwitch";

  /** For {@link java.util.ServiceLoader}; Error Prone itself injects the flags constructor. */
  public TruffleBytecodeSwitch() {
    this(ErrorProneFlags.empty());
  }

  @Inject
  public TruffleBytecodeSwitch(ErrorProneFlags flags) {
    super(flags, "bytecodeswitch");
  }

  @Override
  public Description matchMethod(MethodTree tree, VisitorState state) {
    var symbol = ASTHelpers.getSymbol(tree);
    if (symbol == null || tree.getBody() == null || ASTHelpers.hasAnnotation(symbol, SWITCH, state)) {
      return Description.NO_MATCH;
    }
    // Locals loaded from @CompilationFinal arrays (int op = code[bci];), and switches over either.
    Set<Symbol> opcodes = new HashSet<>();
    var dispatch = new boolean[1];
    tree.getBody().accept(
        new TreeScanner<Void, Integer>() {
          private boolean bytecode(ExpressionTree selector) {
            while (selector instanceof com.sun.source.tree.ParenthesizedTree parens) selector = parens.getExpression();
            if (selector instanceof ArrayAccessTree access) {
              var field = Truffle.field(access.getExpression());
              return field != null && Truffle.compilationFinalDimensions(field, state) != null;
            }
            return opcodes.contains(ASTHelpers.getSymbol(selector));
          }

          @Override
          public Void visitVariable(VariableTree variable, Integer loops) {
            if (variable.getInitializer() != null && bytecode(variable.getInitializer())) {
              opcodes.add(ASTHelpers.getSymbol(variable));
            }
            return super.visitVariable(variable, loops);
          }

          @Override
          public Void visitWhileLoop(com.sun.source.tree.WhileLoopTree loop, Integer loops) {
            return super.visitWhileLoop(loop, loops + 1);
          }

          @Override
          public Void visitForLoop(com.sun.source.tree.ForLoopTree loop, Integer loops) {
            return super.visitForLoop(loop, loops + 1);
          }

          @Override
          public Void visitDoWhileLoop(com.sun.source.tree.DoWhileLoopTree loop, Integer loops) {
            return super.visitDoWhileLoop(loop, loops + 1);
          }

          @Override
          public Void visitSwitch(SwitchTree node, Integer loops) {
            if (loops > 0 && bytecode(node.getExpression())) dispatch[0] = true;
            return super.visitSwitch(node, loops);
          }

          @Override
          public Void visitSwitchExpression(SwitchExpressionTree node, Integer loops) {
            if (loops > 0 && bytecode(node.getExpression())) dispatch[0] = true;
            return super.visitSwitchExpression(node, loops);
          }
        },
        0);
    if (!dispatch[0] || skip(state)) return Description.NO_MATCH;
    return report(tree, "'" + symbol.getSimpleName() + "' dispatches bytecodes.");
  }
}
