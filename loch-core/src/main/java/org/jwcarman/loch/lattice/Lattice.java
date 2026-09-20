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

/**
 * An ordering over labels, expressed as the one operation everything else is derived from.
 *
 * <p><b>Up means more constrained.</b> Every lattice in Loch is oriented that way, without
 * exception: {@link #bottom()} is the label that constrains nothing, and joining two labels yields
 * one at least as constrained as both. Confidentiality rises toward secret, integrity rises toward
 * untrusted, a set of contributing sources rises as it grows. Orienting them alike is what lets one
 * operation serve all of them.
 *
 * <p>That orientation is not a convention for tidiness. It is what makes the safety rule a
 * consequence rather than a check: a derived value's label is the join of its parents', join is
 * monotone, and therefore <b>ordinary derivation cannot weaken a label</b>. Not "must not" —
 * cannot. The only way down is an operation that says so, in public, with evidence.
 *
 * <p><b>Implement {@code join} and {@code bottom}; the order comes free.</b> In a join-semilattice
 * {@code a ⊑ b} holds exactly when {@code a ⊔ b = b}, so {@link #permits} is derived rather than
 * written. Two things that must agree cannot disagree if only one of them exists.
 *
 * <p>Implementations must satisfy the laws in {@code LatticeTck}, which property-tests them. The
 * laws are not decoration: {@link #permits} is defined through {@code equals}, so a type with
 * broken value semantics does not fail loudly — it quietly permits everything, or quietly permits
 * nothing.
 *
 * @param <T> the label type, which must be immutable and have value semantics
 */
@FunctionalInterface
public interface Lattice<T> {

  /**
   * The least upper bound: the weakest label at least as constrained as both.
   *
   * <p>Associative, commutative and idempotent, with {@link #bottom()} as its identity.
   */
  T join(T left, T right);

  /**
   * The label that constrains nothing, and the identity for {@link #join}.
   *
   * <p>Defaults to refusing, because for most label types there is no sensible neutral value to
   * guess and a wrong one silently permits. A lattice built by {@link Lattices} always supplies it.
   */
  default T bottom() {
    throw new UnsupportedOperationException(
        "this lattice has no bottom; build it with Lattices, or override bottom()");
  }

  /**
   * Whether a value labelled {@code value} may reach a destination whose ceiling is {@code
   * ceiling}.
   *
   * <p>Reads as "value is at most ceiling". A destination's ceiling is the most constrained thing
   * it will accept, so a vendor endpoint sits low and a payment processor sits high.
   *
   * <p><b>This is why several parents need no special case.</b> In a semilattice {@code a ⊔ b ⊑ c}
   * holds exactly when {@code a ⊑ c} and {@code b ⊑ c} — the defining property of a least upper
   * bound — so checking the joined label and checking each parent are the same question. An
   * implementation may do whichever is convenient and no one can construct a case where they
   * disagree.
   */
  default boolean permits(T value, T ceiling) {
    return join(value, ceiling).equals(ceiling);
  }
}
