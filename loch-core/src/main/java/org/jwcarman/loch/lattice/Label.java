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
package org.jwcarman.loch.lattice;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * What is known about one value, one axis at a time.
 *
 * <p>Built, combined and rendered. <b>Never read back.</b> There is no way to ask a label what it
 * says on an axis, and that absence is deliberate rather than an oversight: of the eight
 * declassification rules in this repository when the design was written, seven only ever wrote a
 * new value and the eighth read two components purely to copy them forward. Nothing needed a
 * getter.
 *
 * <p>Not having one is worth more than the convenience it would cost. A typed getter would have to
 * answer what a matching axis returns when it holds a mixture -- not the application's type,
 * because a mixture is not one, and not an empty optional either, because that would mean both
 * "mixed" and "nobody said" and those two must never be confused. Not having the getter means not
 * having the question, and it keeps labels opaque to application code, which is where the rest of
 * this library already stands.
 *
 * <p>Immutable. Every operation returns a new one.
 */
public final class Label {

  private final Map<Axis<?>, Object> said;

  private Label(Map<Axis<?>, Object> said) {
    this.said = said;
  }

  /** A label that says nothing at all. Below every ceiling, which is why axes can be required. */
  public static Label nothing() {
    return new Label(Map.of());
  }

  /** What one axis says about this value. */
  public static <T> Label of(Axis<T> axis, T value) {
    return nothing().with(axis, value);
  }

  /**
   * The same label, with this axis saying something else.
   *
   * <p>Used both to build one up and to declassify: {@code joined.with(INTEGRITY, ENDORSED)} is how
   * a derivation vouches for what it checked.
   */
  public <T> Label with(Axis<T> axis, T value) {
    Objects.requireNonNull(axis, "a label needs an axis to say something about");
    Map<Axis<?>, Object> next = new LinkedHashMap<>(said);
    next.put(axis, axis.lift(value));
    return new Label(next);
  }

  /**
   * The more constrained of the two, axis by axis.
   *
   * <p>An axis missing from one side is not missing from the answer: what a label does not say sits
   * at that axis's bottom, and bottom is the identity, so the other side's value carries. That is
   * why two labels describing different axes combine into one describing both.
   *
   * <p>On a matching axis this is where a mixture is born, and it is the only place one can be.
   */
  public Label join(Label other) {
    Map<Axis<?>, Object> next = new LinkedHashMap<>(said);
    other.said.forEach((axis, value) -> next.merge(axis, value, axis::join));
    return new Label(next);
  }

  /**
   * Whether this label is at or below another on every axis.
   *
   * <p>Not the same question a {@link Ceiling} answers. A ceiling is what a reader is entitled to;
   * this compares two labels, and the one place it is needed is declassification -- checking that
   * what a derivation relabelled its result to is genuinely below the combination of its parents.
   *
   * <p>Derived from {@link #join} rather than written separately, for the same reason an axis
   * derives its own: two things that must agree cannot disagree if only one of them exists.
   */
  public boolean atOrBelow(Label other) {
    return join(other).equals(other);
  }

  /**
   * Whether this label says exactly this on one axis.
   *
   * <p>A predicate, not a getter: it takes the value you are asking about and answers yes or no. It
   * hands nothing back, so it raises none of the questions a getter would -- a mixture is not equal
   * to any value anyone can write, and an unsaid axis is not equal to one either, so both answer no
   * without having to be represented.
   *
   * <p>For assertions, manifests and reports. Not for decisions: what a reader may see is a {@link
   * Ceiling}, and asking a label one axis at a time and acting on the answers is how an application
   * would rebuild the gate badly, outside the audit.
   */
  public <T> boolean says(Axis<T> axis, T value) {
    return at(axis).equals(axis.lift(value));
  }

  /** Whether this axis was marked required and this label left it unsaid. */
  public boolean unsaid(Axis<?> axis) {
    return axis.unsaid(at(axis));
  }

  /**
   * What this label says, keyed by axis name, for storage.
   *
   * <p>Names rather than positions, which is what stops a schema change from invalidating rows
   * already written. A record's components are positional: adding a fourth one makes every blob in
   * the table undecodable.
   */
  public Map<String, String> encode() {
    Map<String, String> encoded = new LinkedHashMap<>();
    said.forEach((axis, value) -> encoded.put(axis.name(), axis.encode(value)));
    return encoded;
  }

  /**
   * A label read back out of storage, against the axes a store now declares.
   *
   * <p><b>An axis in the row that the store no longer declares is refused.</b> Ignoring it would be
   * the dangerous direction: the row was written under a constraint, and quietly dropping that
   * constraint makes the value <i>more</i> readable than it was labelled. A store that has stopped
   * declaring an axis has to reckon with the rows carrying it, and failing loudly on read is how it
   * finds out.
   *
   * <p>The other direction needs no special handling here, but it is not automatically safe. An
   * axis declared now but missing from an older row reads as unsaid, which is the bottom of its
   * order and therefore below every ceiling -- so such a row is readable by <i>more</i> readers,
   * not fewer. What stops that is the engine refusing an incomplete label wherever it reads one
   * back, not anything this method does; marking an axis {@code required()} is what makes rows
   * written before it unreadable rather than universally readable.
   */
  public static Label decode(Map<String, String> encoded, Axes declared) {
    Map<Axis<?>, Object> said = new LinkedHashMap<>();
    encoded.forEach(
        (name, value) -> {
          Axis<?> axis = declared.named(name).orElse(null);
          if (axis == null) {
            throw new IllegalArgumentException(
                "a stored label says something about '"
                    + name
                    + "', which this store no longer declares; dropping it would make the value"
                    + " readable by more than it was labelled for");
          }
          said.put(axis, axis.decode(value));
        });
    return new Label(said);
  }

  /** What this label says on one axis, in whatever form the axis keeps. */
  Object at(Axis<?> axis) {
    return said.getOrDefault(axis, axis.bottom());
  }

  /** Every axis this label has something to say about. */
  Iterable<Axis<?>> axes() {
    return said.keySet();
  }

  /** For a manifest line or an audit row. Never for a decision. */
  @Override
  public String toString() {
    if (said.isEmpty()) {
      return "{}";
    }
    return said.entrySet().stream()
        .map(entry -> entry.getKey().name() + "=" + render(entry))
        .collect(Collectors.joining(", ", "{", "}"));
  }

  private static String render(Map.Entry<Axis<?>, Object> entry) {
    return entry.getKey().render(entry.getValue());
  }

  @Override
  public boolean equals(Object other) {
    return other instanceof Label label && said.equals(label.said);
  }

  @Override
  public int hashCode() {
    return said.hashCode();
  }
}
