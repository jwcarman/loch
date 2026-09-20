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

import java.util.Optional;

/**
 * A label that must agree or else becomes unusable: a tenant, a data-residency region, a security
 * domain.
 *
 * <p>Some labels do not form a ladder. Joining tenant A with tenant B does not give a "more
 * restricted tenant" — it gives something that belongs to two customers at once and may go nowhere.
 * The literature calls this an exact-match dimension; the draft spec called it "reject the
 * derivation".
 *
 * <p><b>Refusing is not how it works, though.</b> A join that can fail would make the whole algebra
 * partial, and a derivation that throws tells you at the wrong moment — while combining, rather
 * than when someone tries to use the result. So the conflict is a *value*: {@link Conflict} is the
 * top of this lattice, no ceiling admits it, and the combined value exists with honest lineage to
 * both parents while being incapable of reaching any destination.
 *
 * <p>That is the design's sharpest claim made real. Cross-tenant leakage is not forbidden by a rule
 * someone remembered to write; the value that would carry it is simply unusable.
 *
 * <p><b>A trap worth knowing about before you hit it.</b> There is no ceiling meaning "any single
 * tenant, but not a mixture". That set is not of the form {@code {x : x ⊑ c}} for any {@code c} --
 * the only {@code c} above every {@code One} is {@link Conflict} itself, and a ceiling of
 * {@code Conflict} admits conflicts too, which is the opposite of what anyone wants. So a
 * destination must not hold an exact dimension constant. It takes the value from the access: this
 * request is on behalf of <i>acme</i>, so acme's data passes, another tenant's does not, and a
 * mixture does not either. With nothing named the ceiling is {@link None} and only unattributed
 * values pass, which is the fail-closed answer.
 *
 * @param <T> the underlying label, which must have value semantics
 */
public sealed interface Exact<T> {

  /** Nothing has been said. The identity for joining, and the bottom of this lattice. */
  record None<T>() implements Exact<T> {}

  /** Exactly one value, and everything joined with it so far agreed. */
  record One<T>(T value) implements Exact<T> {
    public One {
      if (value == null) {
        throw new IllegalArgumentException("an exact label needs a value; use None instead");
      }
    }
  }

  /** Two values disagreed. The top: permitted by nothing, forever. */
  record Conflict<T>() implements Exact<T> {}

  /** Nothing said. */
  static <T> Exact<T> none() {
    return new None<>();
  }

  /** This value, and only this value. */
  static <T> Exact<T> of(T value) {
    return new One<>(value);
  }

  /** Irreconcilable. */
  static <T> Exact<T> conflict() {
    return new Conflict<>();
  }

  /** The value, when there is exactly one; empty when nothing was said or two things disagreed. */
  default Optional<T> resolved() {
    return this instanceof One<T> one ? Optional.of(one.value()) : Optional.empty();
  }

  /** Whether two values disagreed, which is a thing worth reporting in a refusal. */
  default boolean conflicted() {
    return this instanceof Conflict<T>;
  }
}
