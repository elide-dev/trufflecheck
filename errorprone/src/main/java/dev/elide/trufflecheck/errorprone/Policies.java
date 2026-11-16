/*
 * Copyright (c) 2024-2026 Elide Technologies, Inc.
 * SPDX-License-Identifier: Apache-2.0 OR MIT
 */

package dev.elide.trufflecheck.errorprone;

import com.google.errorprone.ErrorProneFlags;
import dev.elide.trufflecheck.Policy;

/** Resolves the policy for a checker from {@code -XepOpt:Trufflecheck:Policy=<path>}. */
final class Policies {
  static final String FLAG = "Trufflecheck:Policy";

  private Policies() {}

  static Policy of(ErrorProneFlags flags) {
    return Policy.resolve(flags.get(FLAG).orElse(null));
  }
}
