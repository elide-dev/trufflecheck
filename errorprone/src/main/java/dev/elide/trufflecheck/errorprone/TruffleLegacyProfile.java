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
import com.sun.source.tree.VariableTree;
import com.sun.tools.javac.code.Symbol;
import javax.inject.Inject;
import javax.lang.model.element.ElementKind;

/**
 * Hand-written legacy profile fields ({@code BranchProfile}, {@code ConditionProfile}, …) in DSL
 * nodes (classes with {@code @Specialization} methods). Prefer {@code @Cached Inlined*Profile} with
 * {@code @Bind Node}: inlined profiles live in the node's state bits instead of separate objects,
 * which cuts footprint and image size (truffle/docs/DSLNodeObjectInlining.md). The DSL only warns
 * for {@code @Cached} parameters, not fields.
 */
@BugPattern(
    name = "TruffleLegacyProfile",
    summary = "DSL nodes should use inlined profiles",
    severity = BugPattern.SeverityLevel.WARNING)
public final class TruffleLegacyProfile extends TruffleChecker implements BugChecker.VariableTreeMatcher {
  private static final String PROFILE = "com.oracle.truffle.api.profiles.Profile";
  private static final String INLINED = "com.oracle.truffle.api.profiles.InlinedProfile";

  /** For {@link java.util.ServiceLoader}; Error Prone itself injects the flags constructor. */
  public TruffleLegacyProfile() {
    this(ErrorProneFlags.empty());
  }

  @Inject
  public TruffleLegacyProfile(ErrorProneFlags flags) {
    super(flags, "legacyprofile");
  }

  static boolean isDslNode(Symbol.ClassSymbol type, VisitorState state) {
    for (var member : type.getEnclosedElements()) {
      if (member.getKind() == ElementKind.METHOD
          && (ASTHelpers.hasAnnotation(member, Truffle.SPECIALIZATION, state)
              || ASTHelpers.hasAnnotation(member, Truffle.FALLBACK, state))) {
        return true;
      }
    }
    return false;
  }

  @Override
  public Description matchVariable(VariableTree tree, VisitorState state) {
    var symbol = ASTHelpers.getSymbol(tree);
    if (symbol == null || symbol.getKind() != ElementKind.FIELD) return Description.NO_MATCH;
    if (!Truffle.isSubtype(symbol.type, PROFILE, state) || Truffle.isSubtype(symbol.type, INLINED, state)) {
      return Description.NO_MATCH;
    }
    var owner = symbol.enclClass();
    if (owner == null || !isDslNode(owner, state) || skip(state)) return Description.NO_MATCH;
    return report(
        tree,
        "'" + symbol.getSimpleName() + "' is a legacy " + symbol.type.tsym.getSimpleName() + "; use @Cached Inlined"
            + symbol.type.tsym.getSimpleName() + " with @Bind Node.");
  }
}
