package com.oracle.truffle.api;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** Minimal stand-in for the Truffle API, enough for the rules to resolve types. */
public final class CompilerDirectives {
  private CompilerDirectives() {}

  @Retention(RetentionPolicy.RUNTIME)
  @Target(ElementType.FIELD)
  public @interface CompilationFinal {
    int dimensions() default -1;
  }

  @Retention(RetentionPolicy.CLASS)
  @Target({ElementType.METHOD, ElementType.CONSTRUCTOR})
  public @interface TruffleBoundary {}
}
