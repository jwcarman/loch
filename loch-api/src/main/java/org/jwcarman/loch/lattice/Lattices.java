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
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.ToIntFunction;

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
   * A ladder, listed least-constrained first.
   *
   * <pre>{@code
   * Lattices.ladder(PUBLIC, INTERNAL, CONFIDENTIAL, SECRET)
   * }</pre>
   *
   * <p><b>There is deliberately no version of this that reads {@code Enum::ordinal}.</b>
   * Declaration order is a terrible place to keep a security-relevant contract: someone sorts a
   * list of constants alphabetically in an unrelated tidy-up, every test still passes, and the gate
   * now permits the exact opposite of what it should. Nothing about the enum declaration says "the
   * order of these lines is load-bearing", so nothing stops it. Saying the order here, at the
   * wiring site, is the whole point -- it is the line a reviewer reads and a diff shows.
   *
   * <p>Every constant of the enum must appear exactly once, which is checked: an omitted constant
   * would otherwise have no rung and no honest answer at the gate.
   *
   * @param leastConstrainedFirst the rungs, in order; the first is the bottom
   */
  @SafeVarargs
  public static <E extends Enum<E>> Lattice<E> ladder(E... leastConstrainedFirst) {
    if (leastConstrainedFirst == null || leastConstrainedFirst.length == 0) {
      throw new IllegalArgumentException("a ladder needs at least one rung");
    }
    Map<E, Integer> rungs = new HashMap<>();
    for (int i = 0; i < leastConstrainedFirst.length; i++) {
      E constant = Objects.requireNonNull(leastConstrainedFirst[i], "a rung must not be null");
      if (rungs.put(constant, i) != null) {
        throw new IllegalArgumentException(constant + " appears twice in the ladder");
      }
    }
    E[] declared = leastConstrainedFirst[0].getDeclaringClass().getEnumConstants();
    for (E constant : declared) {
      if (!rungs.containsKey(constant)) {
        throw new IllegalArgumentException(
            constant
                + " is missing from the ladder; every constant needs a rung, or the gate has no"
                + " honest answer for it");
      }
    }
    return ranked(rungs::get, leastConstrainedFirst[0]);
  }

  /**
   * A ladder ordered by a rank the label already carries.
   *
   * <p>For domains that come with their own numbering -- FIPS 199 impact levels, a clearance grade,
   * anything where the rung is data rather than a position in a list. Higher is more constrained.
   * Gaps and negatives are fine; ties are not, because two constants sharing a rung would make
   * {@code join} depend on argument order and quietly break commutativity.
   *
   * @param type the enum whose constants are the rungs
   * @param rank higher means more constrained
   */
  public static <E extends Enum<E>> Lattice<E> ranked(Class<E> type, ToIntFunction<E> rank) {
    E[] constants = type.getEnumConstants();
    if (constants == null || constants.length == 0) {
      throw new IllegalArgumentException(type.getName() + " has no constants to order");
    }
    Map<Integer, E> seen = new HashMap<>();
    E bottom = null;
    for (E constant : constants) {
      int rung = rank.applyAsInt(constant);
      E clash = seen.put(rung, constant);
      if (clash != null) {
        throw new IllegalArgumentException(
            "%s and %s are both ranked %d; ranks must be distinct, or joining them would depend on"
                    .formatted(clash, constant, rung)
                + " which came first");
      }
      if (bottom == null || rung < rank.applyAsInt(bottom)) {
        bottom = constant;
      }
    }
    return ranked(rank::applyAsInt, bottom);
  }

  private static <E> Lattice<E> ranked(java.util.function.Function<E, Integer> rank, E bottom) {
    return new Lattice<>() {
      @Override
      public E join(E left, E right) {
        return rank.apply(left) >= rank.apply(right) ? left : right;
      }

      @Override
      public E bottom() {
        return bottom;
      }
    };
  }

  /**
   * One axis of a label: how to read it, and the order it lives in.
   *
   * @param read pulls this axis out of the whole label
   * @param lattice the order over that axis alone
   */
  public record Axis<A, T>(java.util.function.Function<A, T> read, Lattice<T> lattice) {

    T join(A left, A right) {
      return lattice.join(read.apply(left), read.apply(right));
    }
  }

  /** Names an axis of a label and the order it lives in. */
  public static <A, T> Axis<A, T> axis(java.util.function.Function<A, T> read, Lattice<T> lattice) {
    return new Axis<>(read, lattice);
  }

  /** Builds a label out of two axes. */
  @FunctionalInterface
  public interface Of2<T1, T2, A> {
    A build(T1 first, T2 second);
  }

  /** Builds a label out of three axes. */
  @FunctionalInterface
  public interface Of3<T1, T2, T3, A> {
    A build(T1 first, T2 second, T3 third);
  }

  /** Builds a label out of four axes. */
  @FunctionalInterface
  public interface Of4<T1, T2, T3, T4, A> {
    A build(T1 first, T2 second, T3 third, T4 fourth);
  }

  /** Builds a label out of five axes. */
  @FunctionalInterface
  public interface Of5<T1, T2, T3, T4, T5, A> {
    A build(T1 first, T2 second, T3 third, T4 fourth, T5 fifth);
  }

  /**
   * A label made of independent axes, joined one axis at a time.
   *
   * <pre>{@code
   * record Labels(Exact<String> tenant, Integrity integrity, Sensitivity sensitivity) {}
   *
   * Lattice<Labels> LATTICE =
   *     Lattices.product(
   *         Labels::new,
   *         Lattices.axis(Labels::tenant, Lattices.exact()),
   *         Lattices.axis(Labels::integrity, Integrity.LATTICE),
   *         Lattices.axis(Labels::sensitivity, Sensitivity.LATTICE));
   * }</pre>
   *
   * <p>The product of lattices is a lattice and its join is componentwise, so this satisfies the
   * laws whenever each axis does -- which means an application that composes its label this way
   * never writes a join at all.
   *
   * <p>That matters more than the saved lines. A hand-written product join is mechanical and
   * security-relevant at the same time: forgetting to join one axis leaves that axis taking the
   * left-hand value, which is not a merge, and the TCK only notices if the samples happen to differ
   * on exactly that axis. Here the compiler will not let an axis go missing, because the
   * constructor has as many arguments as there are axes.
   */
  public static <A, T1, T2> Lattice<A> product(
      Of2<T1, T2, A> build, Axis<A, T1> a1, Axis<A, T2> a2) {
    return new Lattice<>() {
      @Override
      public A join(A left, A right) {
        return build.build(a1.join(left, right), a2.join(left, right));
      }

      @Override
      public A bottom() {
        return build.build(a1.lattice().bottom(), a2.lattice().bottom());
      }
    };
  }

  /** A label made of three independent axes. */
  public static <A, T1, T2, T3> Lattice<A> product(
      Of3<T1, T2, T3, A> build, Axis<A, T1> a1, Axis<A, T2> a2, Axis<A, T3> a3) {
    return new Lattice<>() {
      @Override
      public A join(A left, A right) {
        return build.build(a1.join(left, right), a2.join(left, right), a3.join(left, right));
      }

      @Override
      public A bottom() {
        return build.build(a1.lattice().bottom(), a2.lattice().bottom(), a3.lattice().bottom());
      }
    };
  }

  /** A label made of four independent axes. */
  public static <A, T1, T2, T3, T4> Lattice<A> product(
      Of4<T1, T2, T3, T4, A> build,
      Axis<A, T1> a1,
      Axis<A, T2> a2,
      Axis<A, T3> a3,
      Axis<A, T4> a4) {
    return new Lattice<>() {
      @Override
      public A join(A left, A right) {
        return build.build(
            a1.join(left, right), a2.join(left, right), a3.join(left, right), a4.join(left, right));
      }

      @Override
      public A bottom() {
        return build.build(
            a1.lattice().bottom(),
            a2.lattice().bottom(),
            a3.lattice().bottom(),
            a4.lattice().bottom());
      }
    };
  }

  /** A label made of five independent axes. */
  public static <A, T1, T2, T3, T4, T5> Lattice<A> product(
      Of5<T1, T2, T3, T4, T5, A> build,
      Axis<A, T1> a1,
      Axis<A, T2> a2,
      Axis<A, T3> a3,
      Axis<A, T4> a4,
      Axis<A, T5> a5) {
    return new Lattice<>() {
      @Override
      public A join(A left, A right) {
        return build.build(
            a1.join(left, right),
            a2.join(left, right),
            a3.join(left, right),
            a4.join(left, right),
            a5.join(left, right));
      }

      @Override
      public A bottom() {
        return build.build(
            a1.lattice().bottom(),
            a2.lattice().bottom(),
            a3.lattice().bottom(),
            a4.lattice().bottom(),
            a5.lattice().bottom());
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
        if (left.conflicted() || right.conflicted()) {
          return Exact.conflict();
        }
        if (left.empty()) {
          return right;
        }
        if (right.empty()) {
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
