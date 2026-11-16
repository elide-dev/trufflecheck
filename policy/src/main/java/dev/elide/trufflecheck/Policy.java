/*
 * Copyright (c) 2024-2026 Elide Technologies, Inc.
 * SPDX-License-Identifier: Apache-2.0
 */

package dev.elide.trufflecheck;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Rule policy: which owners each rule matches, where it applies, and what it says.
 *
 * <p>Defaults ship in {@code META-INF/trufflecheck/policy.properties}. A project overlays them with
 * its own properties file, named (in order of precedence) by the checker's own option ({@code
 * -XepOpt:Trufflecheck:Policy=<path>} for Error Prone, {@code policy: <path>} for detekt), by the
 * {@code trufflecheck.policy} system property, or by the {@code TRUFFLECHECK_POLICY} environment
 * variable. Overlay keys replace defaults wholesale; {@code <key>+} appends to a default list.
 *
 * <p>Native Image class-init lists are named by {@code classinit.buildtime} and {@code
 * classinit.runtime}: paths relative to the overlay file, one class or package prefix per line.
 */
public final class Policy {
  /** System property naming a policy overlay. */
  public static final String PROPERTY = "trufflecheck.policy";

  /** Environment variable naming a policy overlay. */
  public static final String ENVIRONMENT = "TRUFFLECHECK_POLICY";

  private static final String DEFAULTS = "/META-INF/trufflecheck/policy.properties";
  private static final Map<String, Policy> CACHE = new ConcurrentHashMap<>();

  private final Properties rules;
  private final List<String> buildTime;
  private final List<String> runTime;

  private Policy(Properties rules, List<String> buildTime, List<String> runTime) {
    this.rules = rules;
    this.buildTime = buildTime;
    this.runTime = runTime;
  }

  /** The policy for an explicit overlay path, falling back to the system property, then env. */
  public static Policy resolve(String overlay) {
    var path = blankToNull(overlay);
    if (path == null) path = blankToNull(System.getProperty(PROPERTY));
    if (path == null) path = blankToNull(System.getenv(ENVIRONMENT));
    return CACHE.computeIfAbsent(path == null ? "" : path, Policy::load);
  }

  /** The policy named by the system property or environment, or the bundled defaults. */
  public static Policy current() {
    return resolve(null);
  }

  private static String blankToNull(String value) {
    return value == null || value.isBlank() ? null : value.strip();
  }

  private static Policy load(String overlay) {
    var rules = new Properties();
    try (var stream = Policy.class.getResourceAsStream(DEFAULTS)) {
      if (stream == null) throw new IllegalStateException("Missing bundled policy: " + DEFAULTS);
      read(rules, stream);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
    Path base = null;
    if (!overlay.isEmpty()) {
      var file = Path.of(overlay).toAbsolutePath();
      base = file.getParent();
      var extra = new Properties();
      try (var stream = Files.newInputStream(file)) {
        read(extra, stream);
      } catch (IOException e) {
        throw new UncheckedIOException("Cannot read trufflecheck policy " + file, e);
      }
      for (var key : extra.stringPropertyNames()) {
        var value = extra.getProperty(key);
        if (key.endsWith("+")) {
          var target = key.substring(0, key.length() - 1);
          var existing = rules.getProperty(target, "");
          rules.setProperty(target, existing.isBlank() ? value : existing + "," + value);
        } else {
          rules.setProperty(key, value);
        }
      }
    }
    return new Policy(
        rules,
        classInit(rules.getProperty("classinit.buildtime"), base),
        classInit(rules.getProperty("classinit.runtime"), base));
  }

  private static void read(Properties into, InputStream stream) throws IOException {
    into.load(new java.io.InputStreamReader(stream, StandardCharsets.UTF_8));
  }

  private static List<String> classInit(String path, Path base) {
    if (path == null || path.isBlank()) return List.of();
    var file = base == null ? Path.of(path) : base.resolve(path);
    try (var lines = Files.lines(file, StandardCharsets.UTF_8)) {
      return lines.map(String::strip).filter(l -> !l.isEmpty() && !l.startsWith("#")).toList();
    } catch (IOException e) {
      throw new UncheckedIOException("Cannot read class-init list " + file, e);
    }
  }

  private String required(String key) {
    var value = rules.getProperty(key);
    if (value == null || value.isBlank()) {
      throw new IllegalStateException("Missing or blank trufflecheck policy property: " + key);
    }
    return value;
  }

  private static Set<String> split(String value) {
    var out = new java.util.LinkedHashSet<String>();
    for (var part : value.split(",")) {
      var item = part.strip();
      if (!item.isEmpty()) out.add(item);
    }
    return Set.copyOf(out);
  }

  /** Owners ({@code Type} or {@code Type#method}) the rule matches; required. */
  public Set<String> owners(String rule) {
    return split(required(rule));
  }

  /** Packages the rule applies in; empty when unset, which leaves scoped rules inactive. */
  public Set<String> scope(String rule) {
    return split(rules.getProperty(rule + ".scope", ""));
  }

  /** The rule's diagnostic message; required. */
  public String message(String rule) {
    return required(rule + ".message");
  }

  /** Whether {@code name} sits in one of {@code packages} or a subpackage. */
  public static boolean inPackages(String name, Set<String> packages) {
    for (var pkg : packages) {
      if (name.equals(pkg) || name.startsWith(pkg + ".")) return true;
    }
    return false;
  }

  /** Native Image class-init policy: the most specific build-time or run-time entry wins. */
  public boolean initializedAtBuildTime(String binaryName) {
    return match(buildTime, binaryName) > match(runTime, binaryName);
  }

  private static int match(List<String> entries, String binaryName) {
    var best = -1;
    for (var entry : entries) {
      if (binaryName.equals(entry) || binaryName.startsWith(entry + ".")) {
        best = Math.max(best, entry.length());
      }
    }
    return best;
  }
}
