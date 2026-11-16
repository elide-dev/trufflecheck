/*
 * Copyright (c) 2024-2026 Elide Technologies, Inc.
 * SPDX-License-Identifier: Apache-2.0
 */

package dev.elide.trufflecheck.errorprone;

import com.google.errorprone.BugPattern;
import com.google.errorprone.ErrorProneFlags;
import javax.inject.Inject;

@BugPattern(
    name = "TruffleStackfulException",
    altNames = "ElideStackfulException",
    summary = "Avoid stack-capturing exception constructors",
    severity = BugPattern.SeverityLevel.ERROR)
public final class TruffleStackfulException extends ConstructorRule {
  /** For {@link java.util.ServiceLoader}; Error Prone itself injects the flags constructor. */
  public TruffleStackfulException() {
    this(ErrorProneFlags.empty());
  }

  @Inject
  public TruffleStackfulException(ErrorProneFlags flags) {
    super(flags, "stackful");
  }
}
