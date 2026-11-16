/*
 * Copyright (c) 2024-2026 Elide Technologies, Inc.
 * SPDX-License-Identifier: Apache-2.0 OR MIT
 */

package dev.elide.trufflecheck.errorprone;

import static com.google.errorprone.matchers.method.MethodMatchers.anyMethod;

import com.google.errorprone.matchers.Matcher;
import com.google.errorprone.matchers.Matchers;
import com.sun.source.tree.ExpressionTree;
import dev.elide.trufflecheck.Policy;
import java.util.ArrayList;
import java.util.List;

final class Calls {
  private Calls() {}

  /** Matches the {@code Owner#method} entries listed under {@code rule}, including overrides. */
  static Matcher<ExpressionTree> matcher(Policy policy, String rule) {
    List<Matcher<ExpressionTree>> matchers = new ArrayList<>();
    for (var entry : policy.owners(rule)) {
      var split = entry.indexOf('#');
      matchers.add(
          anyMethod().onDescendantOf(entry.substring(0, split)).named(entry.substring(split + 1)));
    }
    return Matchers.anyOf(matchers);
  }
}
