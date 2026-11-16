/*
 * Copyright (c) 2024-2026 Elide Technologies, Inc.
 * SPDX-License-Identifier: Apache-2.0 OR MIT
 */

package dev.elide.trufflecheck.errorprone;

import static com.google.errorprone.matchers.method.MethodMatchers.anyMethod;
import static com.google.errorprone.matchers.method.MethodMatchers.constructor;

import com.google.errorprone.matchers.Matcher;
import com.google.errorprone.matchers.Matchers;
import com.google.errorprone.predicates.TypePredicates;
import com.sun.source.tree.ExpressionTree;
import dev.elide.trufflecheck.Policy;
import java.util.ArrayList;
import java.util.List;

final class Calls {
  private Calls() {}

  /**
   * Matches the {@code Owner#method} entries under {@code key}, including overrides, and {@code
   * Owner#<init>} entries as constructions of the owner or a subclass.
   */
  static Matcher<ExpressionTree> matcher(Policy policy, String key) {
    List<Matcher<ExpressionTree>> matchers = new ArrayList<>();
    for (var call : policy.calls(key)) {
      matchers.add(
          call.constructor()
              ? constructor().forClass(TypePredicates.isDescendantOf(call.owner()))
              : anyMethod().onDescendantOf(call.owner()).named(call.method()));
    }
    return Matchers.anyOf(matchers);
  }
}
