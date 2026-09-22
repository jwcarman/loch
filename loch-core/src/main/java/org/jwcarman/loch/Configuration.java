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

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BiPredicate;
import org.jwcarman.loch.lattice.Label;

/**
 * Everything declared, frozen at the moment of sealing.
 *
 * <p>Built once rather than per declaration. Writing a charter is single-threaded -- it happens
 * while an application is being wired, before anything it constitutes can act -- so the collections
 * below are ordinary and mutable until they are copied in here.
 *
 * <p>What crosses threads is this, and it crosses exactly once: the atomic write that seals a
 * charter publishes it to every request thread that will ever use a portal. That is why it is
 * immutable and why the copies preserve order -- these are read back into the manifest and into the
 * refusal naming which types a door reads, and a message that differs between runs is a message
 * nobody trusts.
 *
 * <p>Top-level, and deliberately so. This record was nested in {@link DefaultCharter}, which made
 * the two mutually dependent: the engine took a {@code DefaultCharter.Configuration} and the
 * charter constructed the engine, so neither compiled without the other. Nothing here refers to the
 * charter, so the cycle was only ever a fact about where the declaration sat. Nesting it again
 * would restore the cycle.
 */
record Configuration(
    Map<String, SurrogateType<?>> types,
    Map<String, SurrogateType<?>> sources,
    Map<String, Set<String>> destinationReads,
    List<DestinationSpec> destinations,
    List<DerivationSpec<?>> derivations,
    List<QuerySpec<?, ?>> queries,
    AccessContextProvider currentAccess,
    BiPredicate<Label, AccessContext> mayErase) {

  Configuration {
    types = Collections.unmodifiableMap(new LinkedHashMap<>(types));
    sources = Collections.unmodifiableMap(new LinkedHashMap<>(sources));
    destinationReads = Collections.unmodifiableMap(new LinkedHashMap<>(destinationReads));
    destinations = List.copyOf(destinations);
    derivations = List.copyOf(derivations);
    queries = List.copyOf(queries);
  }
}
