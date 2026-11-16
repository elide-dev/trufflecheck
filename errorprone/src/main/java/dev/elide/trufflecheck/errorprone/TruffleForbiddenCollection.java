/*
 * Copyright (c) 2024-2026 Elide Technologies, Inc.
 * SPDX-License-Identifier: Apache-2.0 OR MIT
 */

package dev.elide.trufflecheck.errorprone;

import com.google.errorprone.BugPattern;
import com.google.errorprone.ErrorProneFlags;
import javax.inject.Inject;

@BugPattern(
    name = "TruffleForbiddenCollection",
    altNames = "ElideForbiddenCollection",
    summary = "Avoid prohibited collection implementations",
    severity = BugPattern.SeverityLevel.ERROR)
public final class TruffleForbiddenCollection extends ConstructorRule {
  /** For {@link java.util.ServiceLoader}; Error Prone itself injects the flags constructor. */
  public TruffleForbiddenCollection() {
    this(ErrorProneFlags.empty());
  }

  @Inject
  public TruffleForbiddenCollection(ErrorProneFlags flags) {
    super(flags, "collections");
  }
}
