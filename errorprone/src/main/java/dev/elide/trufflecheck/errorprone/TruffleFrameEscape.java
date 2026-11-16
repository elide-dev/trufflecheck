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
import com.sun.source.tree.AssignmentTree;
import com.sun.source.tree.ClassTree;
import com.sun.source.tree.IdentifierTree;
import com.sun.source.tree.LambdaExpressionTree;
import com.sun.source.tree.MemberSelectTree;
import com.sun.source.tree.MethodInvocationTree;
import com.sun.source.tree.VariableTree;
import com.sun.tools.javac.code.Symbol;
import javax.inject.Inject;
import javax.lang.model.element.ElementKind;

/**
 * A {@code VirtualFrame} must stay virtual: storing it in a field, or capturing it in a lambda or
 * inner class, escapes it, and partial evaluation aborts (VerifyFrameDoesNotEscapePhase). Also flags
 * stack walks that request {@code FrameAccess.READ_WRITE}/{@code MATERIALIZE} in compiled code,
 * which force materialization (and pin virtual threads in Elide's GraalVM).
 */
@BugPattern(
    name = "TruffleFrameEscape",
    summary = "VirtualFrame must not escape",
    severity = BugPattern.SeverityLevel.ERROR)
public final class TruffleFrameEscape extends TruffleChecker
    implements BugChecker.VariableTreeMatcher,
        BugChecker.AssignmentTreeMatcher,
        BugChecker.IdentifierTreeMatcher,
        BugChecker.MethodInvocationTreeMatcher {
  private static final String FRAME_INSTANCE = "com.oracle.truffle.api.frame.FrameInstance";

  /** For {@link java.util.ServiceLoader}; Error Prone itself injects the flags constructor. */
  public TruffleFrameEscape() {
    this(ErrorProneFlags.empty());
  }

  @Inject
  public TruffleFrameEscape(ErrorProneFlags flags) {
    super(flags, "frame");
  }

  private static final String MATERIALIZED = "com.oracle.truffle.api.frame.MaterializedFrame";

  /** A VirtualFrame that isn't a MaterializedFrame (which extends it, and is meant to be stored). */
  private static boolean virtualFrame(com.sun.tools.javac.code.Type type, VisitorState state) {
    return type != null
        && type.getKind() != javax.lang.model.type.TypeKind.NULL
        && Truffle.isSubtype(type, Truffle.VIRTUAL_FRAME, state)
        && !Truffle.isSubtype(type, MATERIALIZED, state);
  }

  @Override
  public Description matchVariable(VariableTree tree, VisitorState state) {
    var symbol = ASTHelpers.getSymbol(tree);
    if (symbol == null || symbol.getKind() != ElementKind.FIELD || !virtualFrame(symbol.type, state)) {
      return Description.NO_MATCH;
    }
    return skip(state) ? Description.NO_MATCH : report(tree, "A VirtualFrame field escapes the frame.");
  }

  @Override
  public Description matchAssignment(AssignmentTree tree, VisitorState state) {
    var field = Truffle.field(tree.getVariable());
    if (field == null || !virtualFrame(ASTHelpers.getType(tree.getExpression()), state)) return Description.NO_MATCH;
    return skip(state) ? Description.NO_MATCH : report(tree, "Storing the frame in a field escapes it.");
  }

  /** A VirtualFrame local or parameter referenced inside a lambda or inner class declared after it. */
  @Override
  public Description matchIdentifier(IdentifierTree tree, VisitorState state) {
    if (!(ASTHelpers.getSymbol(tree) instanceof Symbol.VarSymbol variable)
        || (variable.getKind() != ElementKind.PARAMETER && variable.getKind() != ElementKind.LOCAL_VARIABLE)
        || !virtualFrame(variable.type, state)) {
      return Description.NO_MATCH;
    }
    for (var parent : state.getPath()) {
      if (parent instanceof LambdaExpressionTree lambda) {
        for (var parameter : lambda.getParameters()) {
          if (ASTHelpers.getSymbol(parameter) == variable) return Description.NO_MATCH;
        }
        return skip(state) ? Description.NO_MATCH : report(tree, "Capturing the frame in a lambda escapes it.");
      }
      if (parent instanceof ClassTree type) {
        for (Symbol scope = variable.owner; scope != null; scope = scope.owner) {
          if (scope == ASTHelpers.getSymbol(type)) return Description.NO_MATCH;
        }
        return skip(state) ? Description.NO_MATCH : report(tree, "Capturing the frame in an inner class escapes it.");
      }
      // Reached the method declaring the variable without crossing a lambda or class: no capture.
      if (parent instanceof com.sun.source.tree.MethodTree method && ASTHelpers.getSymbol(method) == variable.owner) {
        return Description.NO_MATCH;
      }
    }
    return Description.NO_MATCH;
  }

  /** Inside a node class (stack walks are usually lambdas, so not "partially evaluated"), outside boundaries. */
  private static boolean inNode(VisitorState state) {
    var node = false;
    for (var tree : state.getPath()) {
      if (tree instanceof com.sun.source.tree.MethodTree method
          && ASTHelpers.hasAnnotation(ASTHelpers.getSymbol(method), Truffle.BOUNDARY, state)) {
        return false;
      }
      if (tree instanceof ClassTree type && Truffle.isSubtype(ASTHelpers.getSymbol(type).type, Truffle.NODE, state)) {
        node = true;
      }
    }
    return node;
  }

  /** {@code frameInstance.getFrame(FrameAccess.READ_WRITE | MATERIALIZE)} in a node. */
  @Override
  public Description matchMethodInvocation(MethodInvocationTree tree, VisitorState state) {
    var symbol = ASTHelpers.getSymbol(tree);
    if (symbol == null
        || !symbol.getSimpleName().contentEquals("getFrame")
        || !Truffle.isSubtype(symbol.owner.type, FRAME_INSTANCE, state)
        || tree.getArguments().size() != 1
        || !(tree.getArguments().get(0) instanceof MemberSelectTree access
            || tree.getArguments().get(0) instanceof IdentifierTree)) {
      return Description.NO_MATCH;
    }
    var name = tree.getArguments().get(0).toString();
    if (!name.endsWith("READ_WRITE") && !name.endsWith("MATERIALIZE")) return Description.NO_MATCH;
    if (skip(state) || !inNode(state)) return Description.NO_MATCH;
    return report(tree, "Requesting a writable or materialized caller frame materializes it (and pins virtual threads).");
  }
}
