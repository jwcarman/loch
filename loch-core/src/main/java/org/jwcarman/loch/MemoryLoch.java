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
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import org.jwcarman.loch.lattice.Lattice;

/**
 * A loch that keeps everything in memory.
 *
 * <p>For tests, for single-process tools, and for working out whether the shape is right before a
 * database is involved. It does not encrypt, it does not survive a restart, and it does not audit
 * -- so it is not the thing to put in front of real cardholder data.
 *
 * <p>What it does do is enforce the gate exactly as a durable implementation must, which is what
 * makes it useful for proving a policy before deploying it.
 */
public final class MemoryLoch<A> implements Loch<A> {

  private record Entry<A>(Object value, Class<?> type, A attribution, Lineage lineage) {}

  private final Lattice<A> lattice;
  private final Map<DestinationId, Destination<A>> destinations;
  private final Map<String, Derivation<A, ?, ?>> derivations;
  private final Map<HeldId, Entry<A>> entries = new ConcurrentHashMap<>();

  private MemoryLoch(LochConfig<A> config) {
    this.lattice = config.lattice();
    Map<DestinationId, Destination<A>> byId = new LinkedHashMap<>();
    for (Destination<A> destination : config.destinations()) {
      if (byId.put(destination.id(), destination) != null) {
        throw new IllegalStateException(
            "two destinations are registered as '" + destination.id() + "'");
      }
    }
    this.destinations = Map.copyOf(new HashMap<>(byId));
    Map<String, Derivation<A, ?, ?>> byName = new LinkedHashMap<>();
    for (Derivation<A, ?, ?> derivation : config.derivations()) {
      if (byName.put(derivation.id().value(), derivation) != null) {
        throw new IllegalStateException(
            "two derivations are registered as '" + derivation.id() + "'");
      }
    }
    this.derivations = Map.copyOf(new HashMap<>(byName));
  }

  /** Builds one. The customizer is where the lattice and the destinations are declared. */
  public static <A> MemoryLoch<A> create(Consumer<LochConfig<A>> customizer) {
    LochConfig<A> config = new LochConfig<>();
    customizer.accept(config);
    return new MemoryLoch<>(config);
  }

  @Override
  public <T> Held<T> hold(T value, A attribution) {
    if (value == null) {
      throw new IllegalArgumentException("a loch holds values, not nulls");
    }
    if (attribution == null) {
      throw new IllegalArgumentException(
          "a held value needs an attribution; use the lattice's bottom to say 'nothing in"
              + " particular'");
    }
    @SuppressWarnings("unchecked")
    Class<T> type = (Class<T>) value.getClass();
    HeldId id = HeldId.fresh();
    entries.put(id, new Entry<>(value, type, attribution, Lineage.held()));
    return new Held<>(id, type);
  }

  @Override
  public A attribution(Held<?> held) {
    Entry<A> entry = entries.get(held.id());
    if (entry == null) {
      throw new IllegalArgumentException("this loch is not holding " + held.id());
    }
    return entry.attribution();
  }

  @Override
  public boolean holds(Held<?> held) {
    return entries.containsKey(held.id());
  }

  @Override
  public Lineage lineage(Held<?> held) {
    Entry<A> entry = entries.get(held.id());
    if (entry == null) {
      throw new IllegalArgumentException("this loch is not holding " + held.id());
    }
    return entry.lineage();
  }

  @Override
  public List<String> manifest() {
    List<String> lines = new ArrayList<>();
    derivations.values().stream()
        .filter(Derivation::privileged)
        .forEach(
            derivation ->
                lines.add(
                    "%s: %s -> %s, may weaken labels"
                        .formatted(
                            derivation.id(),
                            derivation.inputType().getSimpleName(),
                            derivation.outputType().getSimpleName())));
    return List.copyOf(lines);
  }

  @Override
  @SuppressWarnings("unchecked")
  public <I, O> Derived<O> derive(Held<I> parent, DerivationId<I, O> id, AccessContext context) {
    Derivation<A, I, O> derivation = (Derivation<A, I, O>) derivations.get(id.value());
    if (derivation == null) {
      return new Derived.Refused<>(
          Derived.Reason.NO_SUCH_DERIVATION, "no derivation is registered as '" + id + "'");
    }
    if (!derivation.availableTo(context)) {
      return new Derived.Refused<>(
          Derived.Reason.NOT_AVAILABLE_HERE, "'" + id + "' is not offered here");
    }
    Entry<A> entry = entries.get(parent.id());
    if (entry == null) {
      return new Derived.Refused<>(
          Derived.Reason.NO_SUCH_VALUE, "this loch is not holding " + parent.id());
    }
    if (!derivation.inputType().isAssignableFrom(entry.type())) {
      return new Derived.Refused<>(
          Derived.Reason.WRONG_TYPE,
          "'%s' reads a %s, but %s is a %s"
              .formatted(
                  id,
                  derivation.inputType().getSimpleName(),
                  parent.id(),
                  entry.type().getSimpleName()));
    }
    // A derivation is handed plaintext, so it is a destination and passes the same gate.
    Optional<A> ceiling = derivation.ceiling();
    if (ceiling.isPresent() && !lattice.permits(entry.attribution(), ceiling.get())) {
      return new Derived.Refused<>(
          Derived.Reason.ABOVE_CEILING,
          "%s is labelled %s; '%s' accepts %s"
              .formatted(parent.id(), entry.attribution(), id, ceiling.get()));
    }

    Optional<O> produced = derivation.apply(derivation.inputType().cast(entry.value()), context);
    if (produced.isEmpty()) {
      return new Derived.Refused<>(Derived.Reason.DECLINED, "'" + id + "' declined");
    }

    // One parent for now, but the fold is what makes several parents need no special case.
    A joined = entry.attribution();
    A label = joined;
    Optional<java.util.function.UnaryOperator<A>> relabel = derivation.relabel();
    if (relabel.isPresent()) {
      label = relabel.get().apply(joined);
      if (!lattice.permits(label, joined)) {
        return new Derived.Refused<>(
            Derived.Reason.NOT_A_LOWERING,
            "'%s' relabelled %s as %s, which is not below it; ordinary derivation already raises"
                .formatted(id, joined, label));
      }
    }

    List<HeldId> parents = List.of(parent.id());
    HeldId newId =
        derivation.deterministic()
            ? ContentAddress.of(parents, id.value(), derivation.version())
            : HeldId.fresh();
    entries.put(
        newId,
        new Entry<>(
            produced.get(),
            derivation.outputType(),
            label,
            Lineage.derivedFrom(parents, id.value())));
    return new Derived.Made<>(new Held<>(newId, derivation.outputType()));
  }

  @Override
  public <T> Dereferenced<T> dereference(Held<T> held, DestinationId to, AccessContext context) {
    Destination<A> destination = destinations.get(to);
    if (destination == null) {
      return new Dereferenced.Denied<>(
          Dereferenced.Reason.NO_SUCH_DESTINATION, "no destination is registered as '" + to + "'");
    }
    Entry<A> entry = entries.get(held.id());
    if (entry == null) {
      return new Dereferenced.Denied<>(
          Dereferenced.Reason.NO_SUCH_VALUE, "this loch is not holding " + held.id());
    }
    if (!held.type().isAssignableFrom(entry.type())) {
      return new Dereferenced.Denied<>(
          Dereferenced.Reason.WRONG_TYPE,
          held.id()
              + " is a "
              + entry.type().getSimpleName()
              + ", not a "
              + held.type().getSimpleName());
    }
    A ceiling = destination.ceiling(context);
    if (!lattice.permits(entry.attribution(), ceiling)) {
      return new Dereferenced.Denied<>(
          Dereferenced.Reason.ABOVE_CEILING,
          held.id() + " is labelled " + entry.attribution() + "; '" + to + "' accepts " + ceiling);
    }
    return new Dereferenced.Allowed<>(held.type().cast(entry.value()));
  }
}
