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

import java.util.List;
import java.util.Optional;

/**
 * Where a value came from.
 *
 * <p>Recorded for every derived value, which turns two awkward questions into queries: what
 * contributed to this, and what would have to go if a customer asked to be erased. Deletion is a
 * reachability problem rather than a cascade anyone has to design.
 *
 * @param parents the values this was derived from, in the order they were given
 * @param derivation what made it, absent for a value that was held directly
 */
public record Lineage(List<String> parents, Optional<String> derivation) {

  private static final Lineage HELD = new Lineage(List.of(), Optional.empty());

  public Lineage {
    parents = List.copyOf(parents);
  }

  /** A value nobody derived: it was handed to the store by trusted code at a boundary. */
  public static Lineage held() {
    return HELD;
  }

  public static Lineage derivedFrom(List<String> parents, String derivation) {
    return new Lineage(parents, Optional.of(derivation));
  }

  /** Whether this value was asserted at a boundary rather than computed from something. */
  public boolean asserted() {
    return derivation.isEmpty();
  }
}
