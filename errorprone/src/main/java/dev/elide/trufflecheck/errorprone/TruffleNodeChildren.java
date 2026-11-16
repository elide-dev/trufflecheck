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
import com.sun.source.tree.IdentifierTree;
import com.sun.source.tree.VariableTree;
import com.sun.tools.javac.code.Symbol;
import com.sun.tools.javac.code.Type;
import javax.inject.Inject;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.Modifier;

/**
 * Node-typed state in a node must be adopted: {@code @Child} for one node, {@code @Children} for a
 * one-dimensional array. {@code final} fields are references to nodes owned elsewhere (Truffle
 * rejects {@code @Child} on them) and are left alone; collections and nested arrays of nodes can't
 * be adopted at all. {@code RootNode}s are never children ({@code insert} on a root is illegal): a
 * field holding one references another call target, so root-typed fields, arrays and captures are
 * exempt.
 */
@BugPattern(
    name = "TruffleNodeChildren",
    altNames = "ElideNodeChildren",
    summary = "Truffle child nodes must be @Child/@Children fields",
    severity = BugPattern.SeverityLevel.ERROR)
public final class TruffleNodeChildren extends TruffleChecker
    implements BugChecker.VariableTreeMatcher, BugChecker.IdentifierTreeMatcher {
  private static final String ITERABLE = "java.lang.Iterable";
  private static final String MAP = "java.util.Map";

  /** For {@link java.util.ServiceLoader}; Error Prone itself injects the flags constructor. */
  public TruffleNodeChildren() {
    this(ErrorProneFlags.empty());
  }

  @Inject
  public TruffleNodeChildren(ErrorProneFlags flags) {
    super(flags, "children");
  }

  /** A node that could be adopted: not a RootNode. */
  private static boolean isChild(Type type, VisitorState state) {
    return Truffle.isNode(type, state) && !Truffle.isSubtype(type, Truffle.ROOT_NODE, state);
  }

  private static boolean isNodeArray(Type type, VisitorState state) {
    return type instanceof Type.ArrayType array && isChild(array.elemtype, state);
  }

  private static boolean isNestedNodeArray(Type type, VisitorState state) {
    return type instanceof Type.ArrayType array
        && (isNodeArray(array.elemtype, state) || isNestedNodeArray(array.elemtype, state));
  }

  /** {@code List<Leaf>}, {@code Map<K, Leaf>} and the like. */
  private static boolean isNodeCollection(Type type, VisitorState state) {
    if (!Truffle.isSubtype(state.getTypes().erasure(type), ITERABLE, state)
        && !Truffle.isSubtype(state.getTypes().erasure(type), MAP, state)) {
      return false;
    }
    for (var argument : type.getTypeArguments()) {
      var bound = argument instanceof Type.WildcardType wildcard ? wildcard.getExtendsBound() : argument;
      if (bound != null && (isChild(bound, state) || isNodeArray(bound, state))) return true;
    }
    return false;
  }

  @Override
  public Description matchVariable(VariableTree tree, VisitorState state) {
    var symbol = ASTHelpers.getSymbol(tree);
    if (symbol == null
        || symbol.getKind() != ElementKind.FIELD
        || symbol.getModifiers().contains(Modifier.STATIC)
        || !Truffle.isSubtype(symbol.owner.type, Truffle.NODE, state)
        || skip(state)) {
      return Description.NO_MATCH;
    }
    var type = symbol.type;
    if (isNestedNodeArray(type, state) || isNodeCollection(type, state)) {
      return report(
          tree,
          "Collections and nested arrays of nodes can't be adopted; hold children in one"
              + " @Children Node[] field.");
    }
    if (symbol.getModifiers().contains(Modifier.FINAL)) return Description.NO_MATCH;
    if (isNodeArray(type, state) && !ASTHelpers.hasAnnotation(symbol, Truffle.CHILDREN, state)) {
      return report(tree, "Node array fields must be @Children.");
    }
    if (isChild(type, state) && !ASTHelpers.hasAnnotation(symbol, Truffle.CHILD, state)) {
      return report(tree, "Node fields must be @Child.");
    }
    return Description.NO_MATCH;
  }

  /** Anonymous or local node classes capturing an enclosing node hold it in a synthetic field. */
  @Override
  public Description matchIdentifier(IdentifierTree tree, VisitorState state) {
    if (!(ASTHelpers.getSymbol(tree) instanceof Symbol.VarSymbol variable)
        || (variable.getKind() != ElementKind.LOCAL_VARIABLE
            && variable.getKind() != ElementKind.PARAMETER)
        || !(isChild(variable.type, state) || isNodeArray(variable.type, state))) {
      return Description.NO_MATCH;
    }
    var owner = ASTHelpers.findEnclosingNode(state.getPath(), ClassTree.class);
    var type = owner == null ? null : ASTHelpers.getSymbol(owner);
    if (type == null
        || !(type.isAnonymous() || type.isDirectlyOrIndirectlyLocal())
        || !Truffle.isSubtype(type.type, Truffle.NODE, state)) {
      return Description.NO_MATCH;
    }
    // Captured from outside the class: its owner is not one of the class's members.
    for (Symbol scope = variable.owner; scope != null; scope = scope.owner) {
      if (scope == type) return Description.NO_MATCH;
    }
    // Initializing an annotated child field is how the capture becomes owned.
    var field = ASTHelpers.findEnclosingNode(state.getPath(), VariableTree.class);
    var fieldSymbol = field == null ? null : ASTHelpers.getSymbol(field);
    if (fieldSymbol != null
        && fieldSymbol.getKind() == ElementKind.FIELD
        && (ASTHelpers.hasAnnotation(fieldSymbol, Truffle.CHILD, state)
            || ASTHelpers.hasAnnotation(fieldSymbol, Truffle.CHILDREN, state))) {
      return Description.NO_MATCH;
    }
    return skip(state)
        ? Description.NO_MATCH
        : report(tree, "Captured child nodes must be stored in a @Child/@Children field.");
  }
}
