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
  private final List<Destination<A>> destinations = new ArrayList<>();

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
