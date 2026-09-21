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

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * The questions an application asks about every value it holds.
 *
 * <p>The schema, and a value in its own right. A {@link Label} can only speak to an axis named
 * here, a {@link Ceiling} has to constrain every axis a label speaks to, and a stored row naming an
 * axis this no longer contains is refused rather than quietly read without it.
 *
 * <p>A type rather than a loose {@code List<Axis<?>>} for three reasons. The uniqueness rule
 * belongs to the set and not to whatever happens to hold it. Storage needs the same object to
 * decode a label against, so passing it around by name keeps one source of truth. And an
 * application can hand its vocabulary to infrastructure before anything is built from it, which is
 * what lets the thing that constructs a charter be the thing that brings it into force.
 *
 * <p>Ordered, because the order is what a manifest and an error message print.
 */
public final class Axes implements Iterable<Axis<?>> {

  private final List<Axis<?>> axes;
  private final Map<String, Axis<?>> byName;

  private Axes(List<Axis<?>> axes, Map<String, Axis<?>> byName) {
    this.axes = axes;
    this.byName = byName;
  }

  /**
   * The axes an application asks about, in the order it wants them read.
   *
   * @throws IllegalArgumentException if two axes want one name, which would make a stored label
   *     read as something it is not
   */
  public static Axes of(Axis<?>... axes) {
    Objects.requireNonNull(axes, "axes must not be null");
    if (axes.length == 0) {
      throw new IllegalArgumentException(
          "at least one axis is needed: a label that says nothing about anything is below every"
              + " ceiling, which means readable by everyone");
    }
    Map<String, Axis<?>> byName = new LinkedHashMap<>();
    for (Axis<?> axis : axes) {
      Objects.requireNonNull(axis, "an axis must not be null");
      Axis<?> clash = byName.putIfAbsent(axis.name(), axis);
      if (clash != null) {
        throw new IllegalArgumentException(
            "two axes both want the name '"
                + axis.name()
                + "', and a stored label is keyed by name, so one would read as the other");
      }
    }
    return new Axes(List.of(axes), Map.copyOf(byName));
  }

  /** The axis of this name, if it is one of these. */
  public Optional<Axis<?>> named(String name) {
    return Optional.ofNullable(byName.get(name));
  }

  /** How many questions are asked. */
  public int size() {
    return axes.size();
  }

  @Override
  public Iterator<Axis<?>> iterator() {
    return axes.iterator();
  }

  @Override
  public String toString() {
    return axes.stream().map(Axis::name).collect(Collectors.joining(", ", "[", "]"));
  }

  @Override
  public boolean equals(Object other) {
    return other instanceof Axes that && axes.equals(that.axes);
  }

  @Override
  public int hashCode() {
    return axes.hashCode();
  }
}
