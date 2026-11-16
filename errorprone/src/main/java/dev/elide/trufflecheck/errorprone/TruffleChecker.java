/*
 * Copyright (c) 2024-2026 Elide Technologies, Inc.
 * SPDX-License-Identifier: Apache-2.0 OR MIT
 */

package dev.elide.trufflecheck.errorprone;

import com.google.errorprone.ErrorProneFlags;
import com.google.errorprone.VisitorState;
import com.google.errorprone.bugpatterns.BugChecker;
import com.google.errorprone.matchers.Description;
import com.sun.source.tree.Tree;
import dev.elide.trufflecheck.Policy;
import java.util.regex.Pattern;

/**
 * Base for trufflecheck rules: resolves the policy from {@code -XepOpt:Trufflecheck:Policy=<path>},
 * honors the rule's {@code <rule>.exclude} path pattern, and skips Truffle DSL output.
 */
abstract class TruffleChecker extends BugChecker {
  static final String FLAG = "Trufflecheck:Policy";

  protected final Policy policy;
  protected final String rule;
  private final Pattern exclude;

  TruffleChecker(ErrorProneFlags flags, String rule) {
    this.policy = Policy.resolve(flags.get(FLAG).orElse(null));
    this.rule = rule;
    this.exclude = policy.exclude(rule);
  }

  /** Whether this rule should stay quiet at the current path. */
  protected boolean skip(VisitorState state) {
    if (exclude != null) {
      var source = state.getPath().getCompilationUnit().getSourceFile();
      if (source != null && exclude.matcher(source.toUri().getPath()).matches()) return true;
    }
    return Truffle.inGeneratedCode(state);
  }

  protected Description report(Tree tree) {
    return buildDescription(tree).setMessage(policy.message(rule)).build();
  }

  protected Description report(Tree tree, String detail) {
    return buildDescription(tree).setMessage(detail + " " + policy.message(rule)).build();
  }
}
