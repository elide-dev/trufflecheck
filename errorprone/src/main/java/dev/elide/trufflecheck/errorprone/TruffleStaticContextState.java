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
import java.util.Set;
import javax.inject.Inject;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.Modifier;

/**
 * Static fields holding per-context or per-engine state ({@code contextstate} types: Env,
 * TruffleContext, the language, threads, thread-locals, call targets, adoptable nodes). Statics are
 * shared by every context and engine in the process, sealed into the image heap under build-time
 * initialization, and break context pre-initialization. Use {@code ContextReference}, {@code
 * LanguageReference}, {@code ContextLocal} or {@code ContextThreadLocal}.
 */
@BugPattern(
    name = "TruffleStaticContextState",
    summary = "Per-context state must not live in static fields",
    severity = BugPattern.SeverityLevel.ERROR)
public final class TruffleStaticContextState extends TruffleChecker
    implements BugChecker.VariableTreeMatcher {
  private static final String UNADOPTABLE = "com.oracle.truffle.api.nodes.UnadoptableNode";

  private final Set<String> types;
  private final Set<String> allow;

  /** For {@link java.util.ServiceLoader}; Error Prone itself injects the flags constructor. */
  public TruffleStaticContextState() {
    this(ErrorProneFlags.empty());
  }

  @Inject
  public TruffleStaticContextState(ErrorProneFlags flags) {
    super(flags, "contextstate");
    this.types = policy.owners("contextstate");
    this.allow = policy.list("contextstate.allow");
  }

  @Override
  public Description matchVariable(VariableTree tree, VisitorState state) {
    var symbol = ASTHelpers.getSymbol(tree);
    if (symbol == null
        || symbol.getKind() != ElementKind.FIELD
        || !symbol.getModifiers().contains(Modifier.STATIC)) {
      return Description.NO_MATCH;
    }
    var type = symbol.type;
    for (var allowed : allow) {
      if (Truffle.isSubtype(type, allowed, state)) return Description.NO_MATCH;
    }
    if (Truffle.isSubtype(type, Truffle.NODE, state) && Truffle.isSubtype(type, UNADOPTABLE, state)) {
      return Description.NO_MATCH;
    }
    for (var banned : types) {
      if (Truffle.isSubtype(type, banned, state)) {
        return skip(state)
            ? Description.NO_MATCH
            : report(tree, "'" + symbol.getSimpleName() + "' holds a " + type.tsym.getSimpleName() + ".");
      }
    }
    return Description.NO_MATCH;
  }
}
