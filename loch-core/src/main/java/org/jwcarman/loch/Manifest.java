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

import java.util.ArrayList;
import java.util.List;

/**
 * What a charter permits, in a form a person can read.
 *
 * <p>Worth printing at startup and worth pasting into a review. No algebra can tell you whether a
 * check is strong enough -- an endorsement that merely confirms a record exists looks exactly like
 * one that ties it to the person who asked -- so the list being short, named and in front of
 * somebody is the control.
 *
 * <p>Two sections are what a reviewer came for. The <b>label-weakening operations</b>: everything
 * else in the design makes labels more constrained, these are the only things that can make them
 * less, and there should be few enough to read in one sitting.
 *
 * <p>And the <b>questions</b>. A question never hands the value over, which makes it look like the
 * safe way to use one -- but each answer is a bit and the asker chooses the question, so enough of
 * them read the value a piece at a time. Nothing here counts them, deliberately: a budget small
 * enough to stop reconstruction is small enough to make the feature useless, and what actually
 * distinguishes a probe from a question is the shape of what the caller may choose, which only the
 * person who wrote it knows. What limits the exposure is the ceiling -- a value you may not ask
 * about gives you no questions at all -- and what makes it visible is the trail, which records
 * every question against the value it was asked about.
 */
public record Manifest(
    String bottom,
    List<Entry> destinations,
    List<Entry> derivations,
    List<Entry> questions,
    AccessContext renderedFor) {

  /**
   * One line of the report.
   *
   * @param weakens whether this operation can make a label less constrained
   */
  public record Entry(String name, String detail, boolean weakens) {}

  public Manifest {
    destinations = List.copyOf(destinations);
    derivations = List.copyOf(derivations);
    questions = List.copyOf(questions);
  }

  /**
   * Every operation that can weaken a label: the ones a reviewer is actually looking for.
   *
   * <p>One list, because there is one kind of operation. Derivations over several values were once
   * a separate type, and were quietly missing from this report for exactly as long as nobody
   * looked.
   */
  public List<Entry> weakening() {
    return derivations.stream().filter(Entry::weakens).toList();
  }

  @Override
  public String toString() {
    List<String> lines = new ArrayList<>();
    lines.add(
        "charter manifest, as "
            + (renderedFor.attributes().isEmpty()
                ? "nobody in particular"
                : renderedFor.attributes()));
    lines.add("");
    lines.add("  unconstrained label (bottom)");
    lines.add("    " + bottom);
    section(lines, "destinations", destinations, "  nothing may be dereferenced anywhere");
    if (!destinations.isEmpty()) {
      lines.add(
          renderedFor.attributes().isEmpty()
              ? "    (a ceiling that reads the access cannot be shown without one -- render this"
                  + " manifest for a representative access to see them)"
              : "    (what these doors accept for this access; another may be offered more or"
                  + " less)");
    }
    section(lines, "derivations", derivations, "  no value can be made from another");
    section(lines, "questions", questions, "  no question can be asked without taking the value");
    if (!questions.isEmpty()) {
      lines.add(
          "    (each answer is one bit and the asker chooses the question, so enough questions"
              + " read the value; a ceiling is what limits who may ask at all)");
    }
    lines.add("");
    List<Entry> weakening = weakening();
    lines.add("  " + weakening.size() + " operation(s) can WEAKEN a label:");
    if (weakening.isEmpty()) {
      lines.add("    (none -- labels under this charter only ever become more constrained)");
    } else {
      weakening.forEach(entry -> lines.add("    " + entry.name() + "  " + entry.detail()));
    }
    return String.join(System.lineSeparator(), lines);
  }

  private static void section(
      List<String> lines, String title, List<Entry> entries, String whenEmpty) {
    lines.add("");
    lines.add("  " + title + " (" + entries.size() + ")");
    if (entries.isEmpty()) {
      lines.add("  " + whenEmpty);
      return;
    }
    int width = entries.stream().mapToInt(entry -> entry.name().length()).max().orElse(0);
    for (Entry entry : entries) {
      lines.add(
          "    %-"
                  .concat(Integer.toString(width))
                  .concat("s  %s")
                  .formatted(entry.name(), entry.detail())
              + (entry.weakens() ? "   << WEAKENS LABELS" : ""));
    }
  }
}
