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

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * The lattices most labels turn out to be, so that defining one is a line rather than a puzzle.
 *
 * <p>Each satisfies the laws {@code LatticeTck} checks. An application writing its own should run
 * that TCK against it, because a broken join does not fail loudly — it silently permits or silently
 * denies.
 */
public final class Lattices {

  private Lattices() {}

  /**
   * A ladder, ordered by the order the constants are declared in.
   *
   * <p><b>Declare least-constrained first.</b> {@code PUBLIC, INTERNAL, CONFIDENTIAL, RESTRICTED}
   * and {@code ENDORSED, UNENDORSED} are both right, because in each the last constant is the one
   * that may go fewest places. Declaring an enum the other way round produces a lattice that
   * type-checks, passes every law, and permits exactly what it should refuse — which is the most
   * dangerous mistake available here and the reason this reads its order from the declaration
   * rather than from a name or an annotation someone might disagree with.
   *
   * <p>The bottom is the first constant.
   */
  public static <E extends Enum<E>> Lattice<E> ordinal(Class<E> type) {
    E[] constants = type.getEnumConstants();
    if (constants == null || constants.length == 0) {
      throw new IllegalArgumentException(type.getName() + " has no constants to order");
    }
    E bottom = constants[0];
    return new Lattice<>() {
      @Override
      public E join(E left, E right) {
        return left.ordinal() >= right.ordinal() ? left : right;
      }

      @Override
      public E bottom() {
        return bottom;
      }
    };
  }

  /**
   * Agree or become unusable: see {@link Exact}.
   *
   * <p>{@code None} joined with anything is that thing; two different values are a {@code
   * Conflict}, which no ceiling admits.
   */
  public static <T> Lattice<Exact<T>> exact() {
    return new Lattice<>() {
      @Override
      public Exact<T> join(Exact<T> left, Exact<T> right) {
        if (left instanceof Exact.Conflict<T> || right instanceof Exact.Conflict<T>) {
          return Exact.conflict();
        }
        if (left instanceof Exact.None<T>) {
          return right;
        }
        if (right instanceof Exact.None<T>) {
          return left;
        }
        return left.equals(right) ? left : Exact.conflict();
      }

      @Override
      public Exact<T> bottom() {
        return Exact.none();
      }
    };
  }

  /**
   * Accumulating labels, where more is more constrained: contributing sources, jurisdictions,
   * purposes a value was collected for.
   *
   * <p>Joining is union, so a value derived from two sources carries both and satisfies only a
   * destination that accepts both. The empty set is the bottom.
   *
   * <p>The returned sets are unmodifiable and iterate in insertion order, so a refusal can name the
   * contributing sources in the order they arrived rather than in whatever order a hash produced.
   */
  public static <T> Lattice<Set<T>> setUnion() {
    return new Lattice<>() {
      @Override
      public Set<T> join(Set<T> left, Set<T> right) {
        if (left.containsAll(right)) {
          return left;
        }
        if (right.containsAll(left)) {
          return right;
        }
        Set<T> union = new LinkedHashSet<>(left);
        union.addAll(right);
        return Collections.unmodifiableSet(union);
      }

      @Override
      public Set<T> bottom() {
        return Set.of();
      }
    };
  }
}
