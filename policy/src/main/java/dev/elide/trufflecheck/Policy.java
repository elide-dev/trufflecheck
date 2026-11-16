/*
 * Copyright (c) 2024-2026 Elide Technologies, Inc.
 * SPDX-License-Identifier: Apache-2.0 OR MIT
 */

package dev.elide.trufflecheck;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

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
 * {@code classinit.native-image} names files or directories (relative to the overlay) to scan for
 * {@code META-INF/native-image/**}{@code /native-image.properties}; their {@code Args} contribute
 * {@code --initialize-at-build-time=} and {@code --initialize-at-run-time=} entries.
 *
 * <p>Policies are cached per overlay path and reloaded when the overlay or a list it names changes,
 * so long-lived compiler daemons pick up edits.
 */
public final class Policy {
  /** System property naming a policy overlay. */
  public static final String PROPERTY = "trufflecheck.policy";

  /** Environment variable naming a policy overlay. */
  public static final String ENVIRONMENT = "TRUFFLECHECK_POLICY";

  private static final String DEFAULTS = "/META-INF/trufflecheck/policy.properties";
  private static final Map<String, Policy> CACHE = new ConcurrentHashMap<>();

  /** A {@code Type#method} entry; {@code method} is {@code <init>} for constructors. */
  public record Call(String owner, String method) {
    public boolean constructor() {
      return method.equals("<init>");
    }
  }

  private final Properties rules;
  private final List<String> buildTime;
  private final List<String> runTime;
  private final Map<Path, Long> inputs;

  private Policy(
      Properties rules, List<String> buildTime, List<String> runTime, Map<Path, Long> inputs) {
    this.rules = rules;
    this.buildTime = buildTime;
    this.runTime = runTime;
    this.inputs = inputs;
  }

  /** The policy for an explicit overlay path, falling back to the system property, then env. */
  public static Policy resolve(String overlay) {
    var path = blankToNull(overlay);
    if (path == null) path = blankToNull(System.getProperty(PROPERTY));
    if (path == null) path = blankToNull(System.getenv(ENVIRONMENT));
    var key = path == null ? "" : path;
    return CACHE.compute(key, (k, cached) -> cached != null && cached.fresh() ? cached : load(k));
  }

  /** The policy named by the system property or environment, or the bundled defaults. */
  public static Policy current() {
    return resolve(null);
  }

  private static String blankToNull(String value) {
    return value == null || value.isBlank() ? null : value.strip();
  }

  private boolean fresh() {
    for (var input : inputs.entrySet()) {
      if (modified(input.getKey()) != input.getValue()) return false;
    }
    return true;
  }

  private static long modified(Path path) {
    try {
      return Files.getLastModifiedTime(path).toMillis();
    } catch (IOException e) {
      return -1;
    }
  }

  private static Policy load(String overlay) {
    var rules = new Properties();
    try (var stream = Policy.class.getResourceAsStream(DEFAULTS)) {
      if (stream == null) throw new IllegalStateException("Missing bundled policy: " + DEFAULTS);
      read(rules, stream);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
    var inputs = new ConcurrentHashMap<Path, Long>();
    Path base = null;
    if (!overlay.isEmpty()) {
      var file = Path.of(overlay).toAbsolutePath();
      base = file.getParent();
      inputs.put(file, modified(file));
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
    var buildTime = new ArrayList<>(classInit(rules.getProperty("classinit.buildtime"), base, inputs));
    var runTime = new ArrayList<>(classInit(rules.getProperty("classinit.runtime"), base, inputs));
    for (var root : split(rules.getProperty("classinit.native-image", ""))) {
      var path = (base == null ? Path.of(root) : base.resolve(root)).toAbsolutePath();
      for (var file : nativeImageProperties(path)) {
        inputs.put(file, modified(file));
        readNativeImageArgs(file, buildTime, runTime);
      }
    }
    return new Policy(rules, List.copyOf(buildTime), List.copyOf(runTime), Map.copyOf(inputs));
  }

  private static List<Path> nativeImageProperties(Path root) {
    if (Files.isRegularFile(root)) return List.of(root);
    if (!Files.isDirectory(root)) return List.of();
    try (var walk = Files.walk(root)) {
      return walk.filter(p -> p.getFileName().toString().equals("native-image.properties"))
          .filter(p -> p.toString().replace('\\', '/').contains("META-INF/native-image/"))
          .sorted()
          .toList();
    } catch (IOException e) {
      throw new UncheckedIOException("Cannot scan " + root + " for native-image.properties", e);
    }
  }

  /** {@code Args = --initialize-at-build-time=a,b --initialize-at-run-time=c}, with line continuations. */
  private static void readNativeImageArgs(Path file, List<String> buildTime, List<String> runTime) {
    var properties = new Properties();
    try (var stream = Files.newInputStream(file)) {
      read(properties, stream);
    } catch (IOException e) {
      throw new UncheckedIOException("Cannot read " + file, e);
    }
    var args = properties.getProperty("Args", "");
    for (var arg : args.trim().split("\\s+")) {
      if (arg.startsWith("--initialize-at-build-time=")) {
        buildTime.addAll(split(arg.substring("--initialize-at-build-time=".length())));
      } else if (arg.startsWith("--initialize-at-run-time=")) {
        runTime.addAll(split(arg.substring("--initialize-at-run-time=".length())));
      }
    }
  }

  private static void read(Properties into, InputStream stream) throws IOException {
    into.load(new InputStreamReader(stream, StandardCharsets.UTF_8));
  }

  private static List<String> classInit(String path, Path base, Map<Path, Long> inputs) {
    if (path == null || path.isBlank()) return List.of();
    var file = (base == null ? Path.of(path.strip()) : base.resolve(path.strip())).toAbsolutePath();
    inputs.put(file, modified(file));
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
    var out = new LinkedHashSet<String>();
    for (var part : value.split(",")) {
      var item = part.strip();
      if (!item.isEmpty()) out.add(item);
    }
    // Unmodifiable but null-tolerant: Kotlin callers test nullable names with `in`, and Set.copyOf's
    // contains(null) throws.
    return java.util.Collections.unmodifiableSet(out);
  }

  /** Owners (types, packages or {@code Type#method} entries) the rule matches; required. */
  public Set<String> owners(String rule) {
    return split(required(rule));
  }

  /** A comma-separated list; empty when the key is unset. */
  public Set<String> list(String key) {
    return split(rules.getProperty(key, ""));
  }

  /** {@code Type#method} entries under {@code key}; a malformed entry fails loudly. */
  public List<Call> calls(String key) {
    var out = new ArrayList<Call>();
    for (var entry : split(required(key))) {
      var split = entry.indexOf('#');
      if (split <= 0 || split == entry.length() - 1 || entry.indexOf('#', split + 1) >= 0) {
        throw new IllegalStateException(
            "Malformed trufflecheck policy entry under " + key + ": '" + entry
                + "' (expected Type#method or Type#<init>)");
      }
      out.add(new Call(entry.substring(0, split), entry.substring(split + 1)));
    }
    return List.copyOf(out);
  }

  /** Packages the rule applies in; empty when unset, which leaves scoped rules inactive. */
  public Set<String> scope(String rule) {
    return list(rule + ".scope");
  }

  /** Source paths the rule skips ({@code <rule>.exclude}, a regex on the file path), or null. */
  public Pattern exclude(String rule) {
    var value = rules.getProperty(rule + ".exclude", "").strip();
    return value.isEmpty() ? null : Pattern.compile(value);
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
