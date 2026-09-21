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
 * The most a reader is entitled to see, said one axis at a time.
 *
 * <p>Declared wherever plaintext is: on a destination, a derivation, a fold, a question. A value
 * gets through only if every axis it is labelled on satisfies this ceiling.
 *
 * <p><b>Silence is not permission.</b> A label mentioning an axis this ceiling says nothing about
 * is refused, not waved through. That is the difference between a reader who declared breadth and a
 * reader who forgot an axis, and the two must not look the same -- the second one is how an axis
 * added later would quietly stop being enforced on every ceiling written before it existed.
 *
 * <p>Immutable. Every operation returns a new one.
 */
public final class Ceiling {

  /** A lifted value to stay at or below, or the mark of an axis deliberately left broad. */
  private record Bound(Object atMost, boolean any) {}

  private final Map<Axis<?>, Bound> bounds;

  private Ceiling(Map<Axis<?>, Bound> bounds) {
    this.bounds = bounds;
  }

  /**
   * A ceiling entitled to nothing, because it has yet to say what it is entitled to.
   *
   * <p>It admits a label that says nothing at all, which is the only thing below every ceiling, and
   * refuses everything else -- so it is the fail-closed starting point rather than a closed door.
   */
  public static Ceiling nothing() {
    return new Ceiling(Map.of());
  }

  /** What a reader is entitled to on one axis. */
  public static <T> Ceiling of(Axis<T> axis, Constraint<T> constraint) {
    return nothing().with(axis, constraint);
  }

  /** The same ceiling, saying something else about this axis. */
  public <T> Ceiling with(Axis<T> axis, Constraint<T> constraint) {
    Objects.requireNonNull(axis, "a ceiling needs an axis to constrain");
    Objects.requireNonNull(constraint, "'" + axis.name() + "' needs a constraint");
    Map<Axis<?>, Bound> next = new LinkedHashMap<>(bounds);
    next.put(axis, bound(axis, constraint));
    return new Ceiling(next);
  }

  private static <T> Bound bound(Axis<T> axis, Constraint<T> constraint) {
    return switch (constraint) {
      case Constraint.AtMost<T> atMost -> new Bound(axis.lift(atMost.value()), false);
      case Constraint.Any<T>() -> new Bound(null, true);
    };
  }

  /**
   * Whether this reader may see a value labelled so.
   *
   * <p>Every axis the label speaks to has to pass. An axis this ceiling never constrained fails,
   * which is what keeps a forgotten axis from reading like a deliberate one.
   */
  public boolean permits(Label label) {
    for (Axis<?> axis : label.axes()) {
      Bound bound = bounds.get(axis);
      if (bound == null) {
        return false;
      }
      Object value = label.at(axis);
      boolean allowed = bound.any() ? axis.admitsAny(value) : axis.permits(value, bound.atMost());
      if (!allowed) {
        return false;
      }
    }
    return true;
  }

  /** Whether this ceiling has said anything at all about an axis. */
  public boolean constrains(Axis<?> axis) {
    return bounds.containsKey(axis);
  }

  /** For a refusal message and for the manifest. */
  @Override
  public String toString() {
    if (bounds.isEmpty()) {
      return "{}";
    }
    return bounds.entrySet().stream()
        .map(entry -> entry.getKey().name() + "=" + render(entry))
        .collect(Collectors.joining(", ", "{", "}"));
  }

  private static String render(Map.Entry<Axis<?>, Bound> entry) {
    Bound bound = entry.getValue();
    return bound.any() ? "(any)" : entry.getKey().render(bound.atMost());
  }

  @Override
  public boolean equals(Object other) {
    return other instanceof Ceiling ceiling && bounds.equals(ceiling.bounds);
  }

  @Override
  public int hashCode() {
    return bounds.hashCode();
  }
}
