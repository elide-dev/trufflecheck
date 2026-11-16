/*
 * Copyright (c) 2026 Elide Technologies, Inc.
 * SPDX-License-Identifier: Apache-2.0
 */

package dev.elide.trufflecheck.errorprone;

import com.google.errorprone.BugPattern;
import com.google.errorprone.VisitorState;
import com.google.errorprone.bugpatterns.BugChecker;
import com.google.errorprone.matchers.Description;
import com.google.errorprone.util.ASTHelpers;
import com.sun.source.tree.ClassTree;
import com.sun.source.tree.IdentifierTree;
import com.sun.source.tree.VariableTree;
import com.sun.tools.javac.code.Symbol;
import com.sun.tools.javac.code.Type;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.Modifier;
import dev.elide.trufflecheck.Policy;
import javax.inject.Inject;
import com.google.errorprone.ErrorProneFlags;

@BugPattern(
    name = "TruffleNodeChildren",
    altNames = "ElideNodeChildren",
    summary = "Truffle child nodes must be @Child/@Children fields",
    severity = BugPattern.SeverityLevel.ERROR)
public final class TruffleNodeChildren extends BugChecker
    implements BugChecker.VariableTreeMatcher, BugChecker.IdentifierTreeMatcher {
  private final Policy policy;

  /** For {@link java.util.ServiceLoader}; Error Prone itself injects the flags constructor. */
  public TruffleNodeChildren() {
    this(ErrorProneFlags.empty());
  }

  @Inject
  public TruffleNodeChildren(ErrorProneFlags flags) {
    this.policy = Policies.of(flags);
  }

  private static final String NODE = "com.oracle.truffle.api.nodes.Node";
  private static final String CHILD = "com.oracle.truffle.api.nodes.Node.Child";
  private static final String CHILDREN = "com.oracle.truffle.api.nodes.Node.Children";

  private static boolean isNode(Type type, VisitorState state) {
    var node = state.getTypeFromString(NODE);
    return type != null && node != null && ASTHelpers.isSubtype(type, node, state);
  }

  private static boolean isNodeArray(Type type, VisitorState state) {
    return type instanceof Type.ArrayType array && isNode(array.elemtype, state);
  }

  @Override
  public Description matchVariable(VariableTree tree, VisitorState state) {
    var symbol = ASTHelpers.getSymbol(tree);
    if (symbol == null
        || symbol.getKind() != ElementKind.FIELD
        || symbol.getModifiers().contains(Modifier.STATIC)
        || !isNode(symbol.owner.type, state)) {
      return Description.NO_MATCH;
    }
    var type = symbol.type;
    if (isNodeArray(type, state) && !ASTHelpers.hasAnnotation(symbol, CHILDREN, state)) {
      return describe(tree, "Node array fields must be @Children");
    }
    if (isNode(type, state) && !ASTHelpers.hasAnnotation(symbol, CHILD, state)) {
      return describe(tree, "Node fields must be @Child");
    }
    return Description.NO_MATCH;
  }

  /** Anonymous nodes capturing an enclosing node or node array hold it in an unannotated field. */
  @Override
  public Description matchIdentifier(IdentifierTree tree, VisitorState state) {
    if (!(ASTHelpers.getSymbol(tree) instanceof Symbol.VarSymbol variable)
        || (variable.getKind() != ElementKind.LOCAL_VARIABLE
            && variable.getKind() != ElementKind.PARAMETER)
        || !(isNode(variable.type, state) || isNodeArray(variable.type, state))) {
      return Description.NO_MATCH;
    }
    var owner = ASTHelpers.findEnclosingNode(state.getPath(), ClassTree.class);
    var type = owner == null ? null : ASTHelpers.getSymbol(owner);
    if (type == null || !type.isAnonymous() || !isNode(type.type, state)) {
      return Description.NO_MATCH;
    }
    // Captured from outside the anonymous class: its owner is not one of the class's members.
    for (Symbol scope = variable.owner; scope != null; scope = scope.owner) {
      if (scope == type) return Description.NO_MATCH;
    }
    // Initializing an annotated child field is how the capture becomes owned.
    var field = ASTHelpers.findEnclosingNode(state.getPath(), VariableTree.class);
    var fieldSymbol = field == null ? null : ASTHelpers.getSymbol(field);
    if (fieldSymbol != null
        && fieldSymbol.getKind() == ElementKind.FIELD
        && (ASTHelpers.hasAnnotation(fieldSymbol, CHILD, state)
            || ASTHelpers.hasAnnotation(fieldSymbol, CHILDREN, state))) {
      return Description.NO_MATCH;
    }
    return describe(tree, "Captured child nodes must be stored in a @Child/@Children field");
  }

  private Description describe(com.sun.source.tree.Tree tree, String detail) {
    return buildDescription(tree).setMessage(detail + ". " + policy.message("children")).build();
  }
}
