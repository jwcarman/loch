/*
 * Copyright © 2026 James Carman
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.jwcarman.loch;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.jwcarman.loch.lattice.Ceiling;
import org.jwcarman.loch.lattice.Label;

/**
 * Rendering a {@link Manifest} from what a charter has been told, and nothing else.
 *
 * <p>Package-private and entirely pure: it reads a frozen {@link Configuration} and produces a
 * report. It decides nothing and holds nothing, which is why it does not live on the charter --
 * reporting is not one of a charter's powers, it is a reading of one.
 */
final class Manifests {

  private static final String NO_WRITER = "no-writer";
  private static final String NO_READER = "no-reader";

  private Manifests() {}

  /** What this configuration permits, rendered for one access. */
  static Manifest of(Configuration configuration, AccessContext as) {
    return new Manifest(
        String.valueOf(Label.nothing()),
        sourceEntries(configuration),
        destinationEntries(configuration, as),
        derivationEntries(configuration),
        queryEntries(configuration),
        findings(configuration),
        as);
  }

  private static List<Manifest.Entry> sourceEntries(Configuration configuration) {
    List<Manifest.Entry> ways = new ArrayList<>();
    for (var source : configuration.sources().entrySet()) {
      ways.add(
          new Manifest.Entry(
              source.getKey(),
              "accepts a " + source.getValue().name(),
              false,
              List.of(),
              source.getValue().name()));
    }
    return ways;
  }

  private static List<Manifest.Entry> destinationEntries(
      Configuration configuration, AccessContext as) {
    List<Manifest.Entry> doors = new ArrayList<>();
    for (DestinationSpec destination : configuration.destinations()) {
      doors.add(
          new Manifest.Entry(
              destination.name(),
              "accepts up to " + accepts(destination, as),
              false,
              List.copyOf(
                  configuration.destinationReads().getOrDefault(destination.name(), Set.of())),
              null));
    }
    return doors;
  }

  private static List<Manifest.Entry> derivationEntries(Configuration configuration) {
    List<Manifest.Entry> entries = new ArrayList<>();
    for (DerivationSpec<?> derivation : configuration.derivations()) {
      entries.add(
          new Manifest.Entry(
              derivation.name(),
              "%s -> %s".formatted(reads(derivation), derivation.outputType().name()),
              derivation.privileged(),
              derivation.inputTypes().stream().map(SurrogateType::name).distinct().toList(),
              derivation.outputType().name()));
    }
    return entries;
  }

  private static List<Manifest.Entry> queryEntries(Configuration configuration) {
    List<Manifest.Entry> questions = new ArrayList<>();
    for (QuerySpec<?, ?> query : configuration.queries()) {
      questions.add(
          new Manifest.Entry(
              query.name(),
              "asks about " + query.inputType().name(),
              false,
              List.of(query.inputType().name()),
              null));
    }
    return questions;
  }

  /**
   * What can be proved about the declarations without running anything.
   *
   * <p>A question about types, and therefore answerable: which types anything can produce, which
   * types anything reads, and whether a path exists between them. A door nobody can reach is dead
   * authority; a door that reads a type nothing makes is usually a rename that went half-applied,
   * and it fails as a refusal at request time rather than at startup, which is the worst moment to
   * find out.
   *
   * <p>Deliberately not a question about labels. A source's label and a destination's ceiling are
   * both functions of the access, so "can an unendorsed value reach the vendor model" has no
   * general answer -- only one per caller. That is what rendering a manifest for an access is for.
   */
  private static List<Manifest.Finding> findings(Configuration configuration) {
    List<Manifest.Finding> findings = new ArrayList<>();
    Set<String> produced = producedTypes(configuration);
    Set<String> read = readTypes(configuration);
    declarationPresenceFindings(configuration, findings);
    unreachableSourceFindings(configuration, findings);
    unproducedDestinationReadFindings(configuration, produced, findings);
    derivationFindings(configuration, produced, read, findings);
    unproducedQueryFindings(configuration, produced, findings);
    return findings;
  }

  /** Every type something in this charter can produce: a source's, or a derivation's output. */
  private static Set<String> producedTypes(Configuration configuration) {
    Set<String> produced = new LinkedHashSet<>();
    configuration.sources().values().forEach(type -> produced.add(type.name()));
    for (DerivationSpec<?> derivation : configuration.derivations()) {
      produced.add(derivation.outputType().name());
    }
    return produced;
  }

  /** Every type something in this charter reads: a door's, a derivation's, or a question's. */
  private static Set<String> readTypes(Configuration configuration) {
    Set<String> read = new LinkedHashSet<>();
    configuration.destinationReads().values().forEach(read::addAll);
    for (DerivationSpec<?> derivation : configuration.derivations()) {
      derivation.inputTypes().forEach(type -> read.add(type.name()));
    }
    for (QuerySpec<?, ?> query : configuration.queries()) {
      read.add(query.inputType().name());
    }
    return read;
  }

  /** Whether this charter has any door in and any door out at all. */
  private static void declarationPresenceFindings(
      Configuration configuration, List<Manifest.Finding> findings) {
    if (configuration.sources().isEmpty()) {
      findings.add(
          new Manifest.Finding(
              "no-sources", "(charter)", "nothing can be concealed: no source is declared"));
    }
    if (configuration.destinations().isEmpty()) {
      findings.add(
          new Manifest.Finding(
              "no-destinations",
              "(charter)",
              "nothing can be revealed anywhere: no destination is declared"));
    }
  }

  /**
   * A value concealed here can reach a door only if some chain of derivations gets its type to one
   * a door reads. Walked rather than assumed: a derivation in the middle is easy to miss.
   */
  private static void unreachableSourceFindings(
      Configuration configuration, List<Manifest.Finding> findings) {
    for (var source : configuration.sources().entrySet()) {
      if (!reaches(source.getValue().name(), configuration)) {
        findings.add(
            new Manifest.Finding(
                NO_READER,
                source.getKey(),
                "writes '%s', which no destination reads and no derivation turns into one"
                    .formatted(source.getValue().name())));
      }
    }
  }

  /** A door that reads a type nothing in this charter can ever produce. */
  private static void unproducedDestinationReadFindings(
      Configuration configuration, Set<String> produced, List<Manifest.Finding> findings) {
    for (var door : configuration.destinationReads().entrySet()) {
      for (String type : door.getValue()) {
        if (!produced.contains(type)) {
          findings.add(
              new Manifest.Finding(
                  NO_WRITER,
                  door.getKey(),
                  "reads '%s', which nothing in this charter can produce".formatted(type)));
        }
      }
    }
  }

  /** A derivation that reads what nothing produces, or makes what nothing reads. */
  private static void derivationFindings(
      Configuration configuration,
      Set<String> produced,
      Set<String> read,
      List<Manifest.Finding> findings) {
    for (DerivationSpec<?> derivation : configuration.derivations()) {
      for (SurrogateType<?> input : derivation.inputTypes()) {
        if (!produced.contains(input.name())) {
          findings.add(
              new Manifest.Finding(
                  NO_WRITER,
                  derivation.name(),
                  "reads a %s, which nothing in this charter can produce".formatted(input.name())));
        }
      }
      if (!read.contains(derivation.outputType().name())) {
        findings.add(
            new Manifest.Finding(
                NO_READER,
                derivation.name(),
                "makes '%s', which nothing reads".formatted(derivation.outputType().name())));
      }
    }
  }

  /** A question that asks about a type nothing in this charter can ever produce. */
  private static void unproducedQueryFindings(
      Configuration configuration, Set<String> produced, List<Manifest.Finding> findings) {
    for (QuerySpec<?, ?> query : configuration.queries()) {
      if (!produced.contains(query.inputType().name())) {
        findings.add(
            new Manifest.Finding(
                NO_WRITER,
                query.name(),
                "asks about a %s, which nothing in this charter can produce"
                    .formatted(query.inputType().name())));
      }
    }
  }

  /** Whether any destination reads this type, or a type reachable from it by deriving. */
  private static boolean reaches(String type, Configuration configuration) {
    Set<String> seen = new LinkedHashSet<>();
    Deque<String> pending = new ArrayDeque<>(List.of(type));
    Set<String> doorsRead = new LinkedHashSet<>();
    configuration.destinationReads().values().forEach(doorsRead::addAll);
    while (!pending.isEmpty()) {
      String next = pending.removeFirst();
      if (!seen.add(next)) {
        continue;
      }
      if (doorsRead.contains(next)) {
        return true;
      }
      for (DerivationSpec<?> derivation : configuration.derivations()) {
        for (SurrogateType<?> input : derivation.inputTypes()) {
          if (input.name().equals(next)) {
            pending.add(derivation.outputType().name());
          }
        }
      }
    }
    return false;
  }

  /**
   * What this door accepts for one access.
   *
   * <p>A ceiling that reads the tenant out of the access cannot be rendered without one, and in a
   * multi-tenant application that is every ceiling there is -- so a manifest rendered for nobody
   * printed "could not be evaluated" against every door, which is the report being useless in
   * exactly the case it exists for. It is rendered for an access because there is no such thing as
   * what a door accepts in general.
   *
   * <p>For the manifest only, and evaluated against an empty access when the caller does not supply
   * one, because a manifest is a statement about the system rather than about one request. A
   * ceiling that reads a tenant will refuse to answer that, and saying so is more honest than
   * printing what it would allow nobody.
   */
  private static String accepts(DestinationSpec destination, AccessContext as) {
    try {
      Ceiling ceiling = destination.ceiling(as);
      return ceiling == null ? "(said nothing for this access)" : ceiling.toString();
    } catch (RuntimeException _) {
      return "(could not decide for this access)";
    }
  }

  /** How a derivation's parents read: positionally, or as many of one type. */
  private static String reads(DerivationSpec<?> spec) {
    String types =
        spec.inputTypes().stream().map(SurrogateType::name).collect(Collectors.joining(", "));
    return spec.fold() ? "many " + types : types;
  }
}
