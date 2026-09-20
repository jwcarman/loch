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
 * restricted tenant" -- it gives something that belongs to two customers at once and may go
 * nowhere.
 *
 * <p><b>Refusing is not how it works.</b> A join that can fail would make the whole algebra
 * partial, and a derivation that throws tells you at the wrong moment -- while combining, rather
 * than when someone tries to use the result. So the conflict is a <i>value</i>: it is the top of
 * this lattice, no ceiling admits it, and the combined value exists with honest lineage to both
 * parents while being incapable of reaching any destination.
 *
 * <p>That is the design's sharpest claim made real. Cross-tenant leakage is not forbidden by a rule
 * someone remembered to write; the value that would carry it is simply unusable.
 *
 * <p><b>A trap worth knowing about before you hit it.</b> There is no ceiling meaning "any single
 * tenant, but not a mixture". That set is not of the form {@code {x : x ⊑ c}} for any {@code c} --
 * the only {@code c} above every value is the conflict itself, and a ceiling of conflict admits
 * conflicts too, which is the opposite of what anyone wants. So a destination must not hold an
 * exact dimension constant. It takes the value from the access: this request is on behalf of
 * <i>acme</i>, so acme's data passes, another tenant's does not, and a mixture does not either.
 * With nothing named the ceiling is {@link #none()} and only unattributed values pass, which is the
 * fail-closed answer.
 *
 * <p><b>A record rather than a sealed hierarchy, on purpose.</b> Labels are stored, which means
 * they go through whatever codec an application uses. A sealed interface needs polymorphic type
 * information that every codec has to be told about separately, and this module depends on nothing
 * and so cannot annotate itself for any of them. Two plain components round-trip everywhere without
 * configuration, and that is worth more here than exhaustive pattern matching.
 *
 * @param value the one agreed value, or null when nothing has been said or two things disagreed
 * @param conflicted whether two different values were joined
 */
public record Exact<T>(T value, boolean conflicted) {

  public Exact {
    if (conflicted && value != null) {
      throw new IllegalArgumentException("a conflict has no value; two of them disagreed");
    }
  }

  /** Nothing said. The identity for joining, and the bottom of this lattice. */
  public static <T> Exact<T> none() {
    return new Exact<>(null, false);
  }

  /** This value, and only this value. */
  public static <T> Exact<T> of(T value) {
    if (value == null) {
      throw new IllegalArgumentException("an exact label needs a value; use none() instead");
    }
    return new Exact<>(value, false);
  }

  /** Irreconcilable, forever. The top: permitted by nothing. */
  public static <T> Exact<T> conflict() {
    return new Exact<>(null, true);
  }

  /** The value, when there is exactly one; empty when nothing was said or two things disagreed. */
  public Optional<T> resolved() {
    return Optional.ofNullable(value);
  }

  /** Whether nothing has been said yet. */
  public boolean empty() {
    return value == null && !conflicted;
  }
}
