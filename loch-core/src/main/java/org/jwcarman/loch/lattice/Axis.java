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

import java.util.Objects;

/**
 * One independent question about a value: whose is it, do we believe it, how sensitive is it.
 *
 * <p>An axis is not a name bolted onto an order. It is a <i>kind</i>, and the kind knows its own
 * rules -- how two values combine, and what breadth means on it. A ladder and a tenant behave
 * differently in ways no general-purpose order can express, which is why {@link #matching} exists
 * rather than an application assembling one out of parts.
 *
 * <p><b>Typed in what an application writes, not in what the engine stores.</b> {@code
 * Axis<String>} for a tenant means an application says {@code "acme"}. What the engine keeps is
 * wider than that -- it also has to represent "nobody said" and "two tenants were mixed", and
 * neither of those is a {@code String}. So {@code T} appears only on the way in, at {@link
 * #lift(Object)}, and everything past that point is opaque.
 *
 * <p>That asymmetry is what keeps this whole area free of unchecked casts. A typed container
 * normally needs one to hand {@code T} back out; nothing here ever hands {@code T} back out,
 * because nothing reads a label component. Labels are built, combined and rendered.
 *
 * @param <T> what an application writes on this axis
 */
public final class Axis<T> {

  private final String name;
  private final Order order;
  private final boolean required;

  private Axis(String name, Order order, boolean required) {
    this.name = name;
    this.order = order;
    this.required = required;
  }

  /**
   * Ranked constants, least constrained first.
   *
   * <p>Combining two gives the more constrained one, and breadth means anything: a ladder has no
   * poisoned value, so a reader entitled to "any rung" really may see every rung.
   *
   * <p>Order is declared by listing rather than by comparing, because a hand-written comparison can
   * be inconsistent -- non-transitive, or disagreeing with {@code equals} -- and an inconsistent
   * order does not fail loudly. It quietly permits the wrong things.
   */
  @SafeVarargs
  public static <E extends Enum<E>> Axis<E> ladder(String name, E... leastConstrainedFirst) {
    Objects.requireNonNull(name, "an axis needs a name");
    if (leastConstrainedFirst == null || leastConstrainedFirst.length == 0) {
      throw new IllegalArgumentException("'" + name + "' needs at least one rung");
    }
    return new Axis<>(name, Ladder.of(name, leastConstrainedFirst), false);
  }

  /**
   * A value that has to match exactly: one tenant, one region, one customer.
   *
   * <p>Two different values are not one more constrained than the other. They are incomparable, and
   * that incomparability is the point -- neither may read the other's data. So combining them
   * cannot pick a winner; it produces a <b>mixture</b>, a value no ceiling admits and no
   * application can write down. A mixed value still exists, still records honest lineage to both
   * parents, and can reach nobody.
   *
   * <p>Breadth here means <i>any one value</i>, never a mixture. A reporting job entitled to read
   * every tenant reads acme's row, then globex's row, and the combination it makes from them is
   * unusable. Blocking the individual reads would block the wrong thing.
   */
  public static Axis<String> matching(String name) {
    Objects.requireNonNull(name, "an axis needs a name");
    return new Axis<>(name, new Matching(), false);
  }

  /**
   * Says that leaving this axis unsaid is a gap rather than a label.
   *
   * <p>Only meaningful where the bottom of the order means "nobody said". Do not mark a ladder
   * required: {@code ladder("integrity", ENDORSED, UNENDORSED)} has {@code ENDORSED} at the bottom,
   * which is a real and common value, and requiring it would forbid endorsed data entirely.
   *
   * <p>Nothing can check this for you. The judgement is yours and it is per axis.
   */
  public Axis<T> required() {
    return new Axis<>(name, order, true);
  }

  /** How this axis is written down: in the manifest, in an audit line, in storage. */
  public String name() {
    return name;
  }

  /** Turns what an application wrote into what the engine keeps. */
  Object lift(T value) {
    return order.lift(Objects.requireNonNull(value, "'" + name + "' was given no value"));
  }

  /** What this axis holds when nobody has said anything. */
  Object bottom() {
    return order.bottom();
  }

  /** The more constrained of the two, which for a matching axis may be neither of them. */
  Object join(Object left, Object right) {
    return order.join(left, right);
  }

  /**
   * Whether a value sits at or below a ceiling on this axis.
   *
   * <p>Derived from {@link #join} rather than written separately: {@code a} is below {@code b}
   * exactly when combining them gives {@code b}. Two things that must agree cannot disagree if only
   * one of them exists.
   */
  boolean permits(Object value, Object ceiling) {
    return order.join(value, ceiling).equals(ceiling);
  }

  /** Whether an unconstrained reader may see this, which a mixture never is. */
  boolean admitsAny(Object value) {
    return order.admitsAny(value);
  }

  /** Whether this axis was marked required and left unsaid. */
  boolean unsaid(Object value) {
    return required && order.bottom().equals(value);
  }

  /** For the manifest and the audit line. */
  String render(Object value) {
    return order.render(value);
  }

  /** For storage. An axis carries its own encoding so that adding one does not invalidate rows. */
  String encode(Object value) {
    return order.encode(value);
  }

  Object decode(String encoded) {
    return order.decode(encoded);
  }

  @Override
  public String toString() {
    return name;
  }
}
