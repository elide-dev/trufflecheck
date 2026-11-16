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
import com.sun.source.tree.BinaryTree;
import com.sun.source.tree.CompoundAssignmentTree;
import com.sun.source.tree.MethodInvocationTree;
import com.sun.source.tree.Tree;
import com.sun.tools.javac.code.Symbol;
import dev.elide.trufflecheck.Policy;
import java.util.HashSet;
import java.util.Set;
import javax.inject.Inject;

/**
 * Host calls partial evaluation must not inline, in code it reaches: by default Native Image's
 * runtime-compilation blocklist (TruffleFeature), which fails the image build when such methods are
 * reachable from runtime-compiled code, plus other PE-hostile APIs (formatting, regex, streams,
 * reflection, printing) and non-constant string concatenation.
 *
 * <p>Calls match by the declaring class of the resolved method, as the blocklist does: {@code
 * boundary.types} (every method; {@code pkg.*} for a package), {@code boundary.calls} ({@code
 * Type#method}), less {@code boundary.allow}.
 */
@BugPattern(
    name = "TruffleMissingBoundary",
    summary = "PE-hostile host calls need @TruffleBoundary",
    severity = BugPattern.SeverityLevel.ERROR)
public final class TruffleMissingBoundary extends TruffleChecker
    implements BugChecker.MethodInvocationTreeMatcher,
        BugChecker.BinaryTreeMatcher,
        BugChecker.CompoundAssignmentTreeMatcher {
  private static final String STRING = "java.lang.String";

  private final Set<String> types;
  private final Set<String> packages = new HashSet<>();
  private final Set<String> calls = new HashSet<>();
  private final Set<String> allow = new HashSet<>();

  /** For {@link java.util.ServiceLoader}; Error Prone itself injects the flags constructor. */
  public TruffleMissingBoundary() {
    this(ErrorProneFlags.empty());
  }

  @Inject
  public TruffleMissingBoundary(ErrorProneFlags flags) {
    super(flags, "boundary");
    var all = new HashSet<String>();
    for (var entry : policy.list("boundary.types")) {
      if (entry.endsWith(".*")) packages.add(entry.substring(0, entry.length() - 2));
      else all.add(entry);
    }
    this.types = Set.copyOf(all);
    for (var call : policy.calls("boundary.calls")) calls.add(call.owner() + "#" + call.method());
    for (var call : policy.calls("boundary.allow")) allow.add(call.owner() + "#" + call.method());
  }

  private String hostile(Symbol.MethodSymbol method) {
    if (!(method.owner instanceof Symbol.ClassSymbol owner)) return null;
    var type = owner.getQualifiedName().toString();
    var key = type + "#" + (method.isConstructor() ? "<init>" : method.getSimpleName());
    if (allow.contains(key)) return null;
    if (calls.contains(key)
        || types.contains(type)
        || Policy.inPackages(owner.packge().getQualifiedName().toString(), packages)) {
      return owner.getSimpleName() + "." + (method.isConstructor() ? "<init>" : method.getSimpleName());
    }
    return null;
  }

  @Override
  public Description matchMethodInvocation(MethodInvocationTree tree, VisitorState state) {
    var symbol = ASTHelpers.getSymbol(tree);
    var name = symbol == null ? null : hostile(symbol);
    if (name == null || skip(state) || !Truffle.inPartialEvaluation(state)) return Description.NO_MATCH;
    return report(tree, "'" + name + "' must not be inlined into compiled code.");
  }

  @Override
  public Description matchBinary(BinaryTree tree, VisitorState state) {
    return concatenation(tree, state);
  }

  @Override
  public Description matchCompoundAssignment(CompoundAssignmentTree tree, VisitorState state) {
    return concatenation(tree, state);
  }

  /** {@code a + b} on strings, not folded by javac: StringConcatHelper is blocklisted. */
  private Description concatenation(Tree tree, VisitorState state) {
    if (tree.getKind() != Tree.Kind.PLUS && tree.getKind() != Tree.Kind.PLUS_ASSIGNMENT) {
      return Description.NO_MATCH;
    }
    var type = ASTHelpers.getType(tree);
    if (type == null || !type.tsym.getQualifiedName().contentEquals(STRING)) return Description.NO_MATCH;
    if (tree instanceof BinaryTree binary && ASTHelpers.constValue(binary) != null) return Description.NO_MATCH;
    // Report the outermost concatenation only.
    if (state.getPath().getParentPath().getLeaf() instanceof BinaryTree parent
        && parent.getKind() == Tree.Kind.PLUS
        && ASTHelpers.getType(parent) != null
        && ASTHelpers.getType(parent).tsym.getQualifiedName().contentEquals(STRING)) {
      return Description.NO_MATCH;
    }
    if (skip(state) || !Truffle.inPartialEvaluation(state)) return Description.NO_MATCH;
    return report(tree, "String concatenation must not be inlined into compiled code.");
  }
}
