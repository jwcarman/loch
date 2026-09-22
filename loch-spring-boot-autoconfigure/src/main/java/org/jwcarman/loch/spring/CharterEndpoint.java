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
package org.jwcarman.loch.spring;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.jwcarman.loch.Charter;
import org.jwcarman.loch.Manifest;
import org.jwcarman.loch.lattice.Axis;
import org.springframework.boot.actuate.endpoint.annotation.Endpoint;
import org.springframework.boot.actuate.endpoint.annotation.ReadOperation;
import org.springframework.boot.actuate.endpoint.annotation.Selector;

/**
 * What this application's charter permits, explorable while it is running.
 *
 * <p>The manifest is already meant to be printed at startup and pasted into a review. This is the
 * same report reachable at runtime, which is where somebody asks the question that matters: not
 * "what did we intend to deploy" but "what is deployed".
 *
 * <pre>
 * /actuator/charter                      everything, plus what can be drilled into
 * /actuator/charter/types                every surrogate type this charter mentions
 * /actuator/charter/types/{name}         everything declared about values of that type
 * /actuator/charter/sources/{name}       one door in
 * /actuator/charter/destinations/{name}  one door out
 * /actuator/charter/derivations/{name}   one way of making a value from another
 * /actuator/charter/questions/{name}     one question
 * /actuator/charter/findings             what is provably unreachable
 * </pre>
 *
 * <p><b>Declarations only, never a value.</b> Every name here was chosen by whoever wrote the
 * charter, and nothing is derived from anything held: no label, no lineage, no identifier of
 * anything concealed. That is the same line the {@link Charter} interface draws, and it is what
 * makes this safe to expose at all.
 *
 * <p><b>No evaluated ceilings.</b> A ceiling reads the access, and the access here belongs to
 * whoever is looking at an operations endpoint rather than to the caller a door was declared for.
 * Rendering them would produce a page of "could not decide", or worse, one tenant's limits shown to
 * somebody else. What is reported instead is what does not vary.
 *
 * <p>Still worth protecting. A list of every door and every declassification is a map of the
 * application's security posture, useful to somebody attacking it even though it discloses no data.
 * Treat it as you would {@code /actuator/beans}.
 */
@Endpoint(id = "charter")
public class CharterEndpoint {

  private static final String TYPES = "types";
  private static final String SOURCES = "sources";
  private static final String DESTINATIONS = "destinations";
  private static final String DERIVATIONS = "derivations";
  private static final String QUESTIONS = "questions";
  private static final String FINDINGS = "findings";

  private final Charter charter;

  public CharterEndpoint(Charter charter) {
    this.charter = charter;
  }

  /** The whole charter, and what can be asked about next. */
  @ReadOperation
  public Map<String, Object> charter() {
    Manifest manifest = charter.manifest();
    Map<String, Object> report = new LinkedHashMap<>();
    report.put("sealed", charter.sealed());
    report.put("axes", axes());
    report.put(TYPES, types(manifest));
    report.put(SOURCES, entries(manifest.sources()));
    report.put(DESTINATIONS, doors(manifest.destinations()));
    report.put(DERIVATIONS, entries(manifest.derivations()));
    report.put(QUESTIONS, entries(manifest.questions()));
    report.put("weakening", entries(manifest.weakening()));
    // What an auditor came for: a door nobody can reach, or one reading a type nothing makes.
    // Provable from the declarations, so a finding is a fact rather than a suspicion.
    report.put(FINDINGS, findings(manifest));
    return report;
  }

  /** One section of it. */
  @ReadOperation
  public Map<String, Object> section(@Selector String section) {
    Manifest manifest = charter.manifest();
    Map<String, Object> report = new LinkedHashMap<>();
    report.put("section", section);
    switch (section) {
      case TYPES -> report.put(TYPES, types(manifest));
      case SOURCES -> report.put(SOURCES, entries(manifest.sources()));
      case DESTINATIONS -> report.put(DESTINATIONS, doors(manifest.destinations()));
      case DERIVATIONS -> report.put(DERIVATIONS, entries(manifest.derivations()));
      case QUESTIONS -> report.put(QUESTIONS, entries(manifest.questions()));
      case FINDINGS -> report.put(FINDINGS, findings(manifest));
      default -> {
        return null; // 404: this endpoint has no such section.
      }
    }
    return report;
  }

  /**
   * One named thing, or everything about one surrogate type.
   *
   * <p>{@code types/card} is the question somebody actually arrives with. Not "what does this
   * application permit" but "what can happen to a card": which doors it enters through, which doors
   * it leaves by, what can be made from it, what it can be made from, what may be asked about it,
   * and anything provably wrong with that.
   *
   * <p>A type nobody declared answers with empty lists rather than 404. That it is mentioned
   * nowhere is the answer, and a different fact from there being no such section.
   */
  @ReadOperation
  public Map<String, Object> named(@Selector String section, @Selector String name) {
    Manifest manifest = charter.manifest();
    if (TYPES.equals(section)) {
      Manifest about = manifest.about(name);
      Map<String, Object> report = new LinkedHashMap<>();
      report.put("type", name);
      report.put("concealedBy", entries(about.sources()));
      report.put("revealedAt", doors(about.destinations()));
      report.put(
          "madeBy",
          entries(about.derivations().stream().filter(e -> name.equals(e.writes())).toList()));
      report.put(
          "readBy",
          entries(about.derivations().stream().filter(e -> e.reads().contains(name)).toList()));
      report.put("askedAboutBy", entries(about.questions()));
      report.put(FINDINGS, findings(about));
      return report;
    }
    List<Manifest.Entry> in =
        switch (section) {
          case SOURCES -> manifest.sources();
          case DESTINATIONS -> manifest.destinations();
          case DERIVATIONS -> manifest.derivations();
          case QUESTIONS -> manifest.questions();
          default -> null;
        };
    if (in == null) {
      return null;
    }
    return in.stream()
        .filter(entry -> entry.name().equals(name))
        .findFirst()
        .map(CharterEndpoint::entry)
        .orElse(null);
  }

  /** Every surrogate type this charter mentions, and where it came up. */
  private static List<Map<String, Object>> types(Manifest manifest) {
    Set<String> names = new LinkedHashSet<>();
    List<List<Manifest.Entry>> all =
        List.of(
            manifest.sources(),
            manifest.destinations(),
            manifest.derivations(),
            manifest.questions());
    for (List<Manifest.Entry> entries : all) {
      for (Manifest.Entry entry : entries) {
        names.addAll(entry.reads());
        if (entry.writes() != null) {
          names.add(entry.writes());
        }
      }
    }
    List<Map<String, Object>> rendered = new ArrayList<>();
    for (String name : names) {
      Manifest about = manifest.about(name);
      Map<String, Object> type = new LinkedHashMap<>();
      type.put("name", name);
      type.put("concealedBy", about.sources().size());
      type.put("revealedAt", about.destinations().size());
      type.put(DERIVATIONS, about.derivations().size());
      type.put(QUESTIONS, about.questions().size());
      rendered.add(type);
    }
    return rendered;
  }

  /** The questions this application asks about every value, in the order it declared them. */
  private List<String> axes() {
    List<String> names = new ArrayList<>();
    for (Axis<?> axis : charter.axes()) {
      names.add(axis.name());
    }
    return names;
  }

  private static List<Map<String, Object>> findings(Manifest manifest) {
    return manifest.findings().stream().map(CharterEndpoint::finding).toList();
  }

  private static Map<String, Object> finding(Manifest.Finding finding) {
    Map<String, Object> rendered = new LinkedHashMap<>();
    rendered.put("kind", finding.kind());
    rendered.put("about", finding.about());
    rendered.put("detail", finding.detail());
    return rendered;
  }

  /**
   * A door, without the one thing this endpoint will not report.
   *
   * <p>A destination's {@code detail} is its ceiling rendered for an access, and there is no access
   * here worth rendering for -- so it would read "could not decide" on every row, which is noise
   * that looks like a fault. What a door reads does not vary, and that is reported.
   */
  private static List<Map<String, Object>> doors(List<Manifest.Entry> entries) {
    return entries.stream()
        .map(
            entry -> {
              Map<String, Object> rendered = new LinkedHashMap<>();
              rendered.put("name", entry.name());
              rendered.put("reads", entry.reads());
              rendered.put("ceiling", "depends on the caller; render a manifest for one to see it");
              return (Map<String, Object>) rendered;
            })
        .toList();
  }

  private static List<Map<String, Object>> entries(List<Manifest.Entry> entries) {
    return entries.stream().map(CharterEndpoint::entry).toList();
  }

  private static Map<String, Object> entry(Manifest.Entry entry) {
    Map<String, Object> rendered = new LinkedHashMap<>();
    rendered.put("name", entry.name());
    rendered.put("detail", entry.detail());
    rendered.put("reads", entry.reads());
    rendered.put("writes", entry.writes());
    rendered.put("weakens", entry.weakens());
    return rendered;
  }
}
