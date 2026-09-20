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

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.jwcarman.loch.lattice.Lattice;

/**
 * How a loch is built: the lattice its labels live in, and the destinations values may reach.
 *
 * <p>Both are wiring-time decisions on purpose. A lattice supplied later could reorder what is
 * permitted underneath values already stored, and a destination supplied at a call site would let
 * any code invent its own permission.
 */
public final class LochConfig<A> {

  private Lattice<A> lattice;
  private boolean explainRefusals;
  private final List<Destination<A>> destinations = new ArrayList<>();
  private final List<Derivation<A, ?, ?>> derivations = new ArrayList<>();
  private final List<Check<A, ?, ?>> checks = new ArrayList<>();

  /** The order over this application's labels. Required. */
  public LochConfig<A> lattice(Lattice<A> lattice) {
    this.lattice = Objects.requireNonNull(lattice, "a loch needs a lattice");
    return this;
  }

  /** Somewhere values may go. Registered once; referenced by name forever after. */
  public LochConfig<A> destination(Destination<A> destination) {
    destinations.add(Objects.requireNonNull(destination, "a destination must not be null"));
    return this;
  }

  /** A destination accepting the same thing regardless of who asks. */
  public LochConfig<A> destination(DestinationId id, A ceiling) {
    return destination(Destinations.fixed(id, ceiling));
  }

  /** A way of making one value from another. Registered once; referenced by name forever after. */
  public LochConfig<A> derivation(Derivation<A, ?, ?> derivation) {
    derivations.add(Objects.requireNonNull(derivation, "a derivation must not be null"));
    return this;
  }

  /** A question that can be asked of a held value without the value leaving. */
  public LochConfig<A> check(Check<A, ?, ?> check) {
    checks.add(Objects.requireNonNull(check, "a check must not be null"));
    return this;
  }

  List<Check<A, ?, ?>> checks() {
    return List.copyOf(checks);
  }

  List<Derivation<A, ?, ?>> derivations() {
    return List.copyOf(derivations);
  }

  /**
   * Includes labels and ceilings in refusal messages.
   *
   * <p>Off by default, because a label can itself be sensitive -- a tenant's name in a refusal
   * shown to a different tenant is a leak, and refusal text has a way of reaching places the value
   * never would. On for development, where the alternative is guessing.
   */
  public LochConfig<A> explainRefusals() {
    this.explainRefusals = true;
    return this;
  }

  boolean explainsRefusals() {
    return explainRefusals;
  }

  Lattice<A> lattice() {
    if (lattice == null) {
      throw new IllegalStateException(
          "a loch needs a lattice: call lattice(...) with the order over your attribution type");
    }
    return lattice;
  }

  List<Destination<A>> destinations() {
    return List.copyOf(destinations);
  }
}
