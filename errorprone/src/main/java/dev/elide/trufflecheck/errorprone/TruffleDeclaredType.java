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
import com.sun.source.tree.MethodTree;
import com.sun.source.tree.Tree;
import com.sun.source.tree.VariableTree;
import com.sun.tools.javac.code.Type;
import java.util.Set;
import javax.inject.Inject;

/**
 * Types that shouldn't appear as declared types of fields, parameters, locals or return values
 * ({@code declaredtypes}, default {@code TruffleObject}, as GraalPy's checkstyle enforces). A
 * {@code TruffleObject}-typed value forces casts and type checks in compiled code for no benefit:
 * interop dispatches through libraries on {@code Object}.
 */
@BugPattern(
    name = "TruffleDeclaredType",
    summary = "Avoid TruffleObject (and other marker types) as declared types",
    severity = BugPattern.SeverityLevel.WARNING)
public final class TruffleDeclaredType extends TruffleChecker
    implements BugChecker.VariableTreeMatcher, BugChecker.MethodTreeMatcher {
  private final Set<String> banned;

  /** For {@link java.util.ServiceLoader}; Error Prone itself injects the flags constructor. */
  public TruffleDeclaredType() {
    this(ErrorProneFlags.empty());
  }

  @Inject
  public TruffleDeclaredType(ErrorProneFlags flags) {
    super(flags, "declaredtypes");
    this.banned = policy.list("declaredtypes");
  }

  private String match(Type type) {
    if (type == null) return null;
    var name = type.tsym == null ? null : type.tsym.getQualifiedName().toString();
    if (name != null && banned.contains(name)) return type.tsym.getSimpleName().toString();
    if (type instanceof Type.ArrayType array) return match(array.elemtype);
    return null;
  }

  @Override
  public Description matchVariable(VariableTree tree, VisitorState state) {
    var symbol = ASTHelpers.getSymbol(tree);
    var name = symbol == null || tree.getType() == null ? null : match(symbol.type);
    if (name == null || skip(state)) return Description.NO_MATCH;
    return report(tree.getType(), "Declared as '" + name + "'; declare it as Object.");
  }

  @Override
  public Description matchMethod(MethodTree tree, VisitorState state) {
    var returned = tree.getReturnType();
    var name = returned == null ? null : match(ASTHelpers.getType(returned));
    if (name == null || skip(state)) return Description.NO_MATCH;
    return report(returned, "Returns '" + name + "'; return Object.");
  }
}
