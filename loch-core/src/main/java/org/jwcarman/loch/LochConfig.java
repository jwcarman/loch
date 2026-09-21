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
public class LochConfig<A> {

  private Lattice<A> lattice;
  private boolean explainRefusals;
  private Auditor auditor;
  private java.util.function.Supplier<AccessContext> ambient = AccessContext::empty;
  private java.util.Set<String> callerMayContribute = java.util.Set.of();
  private final List<Destination<A>> destinations = new ArrayList<>();
  private final List<Derivation<A, ?, ?>> derivations = new ArrayList<>();
  private final List<Question<A, ?, ?>> questions = new ArrayList<>();
  private final List<Fold<A, ?, ?>> folds = new ArrayList<>();

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
  public LochConfig<A> question(Question<A, ?, ?> question) {
    questions.add(Objects.requireNonNull(question, "a question must not be null"));
    return this;
  }

  /** A way of making one value out of several. */
  public LochConfig<A> fold(Fold<A, ?, ?> fold) {
    folds.add(Objects.requireNonNull(fold, "a fold must not be null"));
    return this;
  }

  List<Fold<A, ?, ?>> folds() {
    return List.copyOf(folds);
  }

  List<Question<A, ?, ?>> questions() {
    return List.copyOf(questions);
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

  /**
   * Where the record of every access goes. Required, or say {@link #withoutAudit()}.
   *
   * <p>There is no default. A governance control that quietly keeps no record still produces the
   * report, which is worse than not having it, so which of the two you want is a decision rather
   * than an omission.
   */
  public LochConfig<A> auditor(Auditor auditor) {
    this.auditor = Objects.requireNonNull(auditor, "an auditor must not be null");
    return this;
  }

  /** Keeps no record, on purpose and in writing. */
  public LochConfig<A> withoutAudit() {
    return auditor(Auditors.discarding());
  }

  /**
   * Where a loch finds out who is asking, when a caller has not said.
   *
   * <p>Identity is known at the edge -- a request, a message, a session -- and needed at the gate,
   * which may be many layers down. Threading an {@code AccessContext} parameter through all of them
   * would make the safety feature the most annoying thing in the codebase, and annoying safety
   * features get routed around.
   *
   * <p>So the application says once where the answer lives. A {@code ThreadLocal}, a {@code
   * ScopedValue}, Spring's {@code SecurityContextHolder} -- Loch does not care, and has no opinion
   * about how a request scope works.
   *
   * <pre>{@code
   * .askingWhoIsAsking(() -> AccessContext.of(Map.of(
   *     "tenant", CurrentTenant.get(),
   *     "principal", SecurityContextHolder.getContext().getAuthentication().getName())))
   * }</pre>
   *
   * <p>An application with no notion of identity says nothing and every context is empty.
   */
  public LochConfig<A> askingWhoIsAsking(java.util.function.Supplier<AccessContext> ambient) {
    this.ambient = Objects.requireNonNull(ambient, "an ambient context source must not be null");
    return this;
  }

  /**
   * The context keys a call site may contribute, on top of what the edge established.
   *
   * <p>Empty by default, deliberately. Anything a caller says about who it is would otherwise be
   * taken at its word, and code holding a loch could name itself whichever tenant or role it
   * pleased. Identity comes from {@link #askingWhoIsAsking}; a caller contributes only what the
   * edge could not know, such as the purpose of an operation.
   *
   * <p>Never list an identity key here.
   */
  public LochConfig<A> callerMayContribute(String... keys) {
    this.callerMayContribute = java.util.Set.of(keys);
    return this;
  }

  java.util.Set<String> callerMayContribute() {
    return callerMayContribute;
  }

  java.util.function.Supplier<AccessContext> ambient() {
    return ambient;
  }

  Auditor auditor() {
    if (auditor == null) {
      throw new IllegalStateException(
          "a loch needs an auditor: call auditor(...) with somewhere to record accesses, or"
              + " withoutAudit() if you really mean to keep no record");
    }
    return auditor;
  }

  Lattice<A> lattice() {
    if (lattice == null) {
      throw new IllegalStateException(
          "a loch needs a lattice: call lattice(...) with the order over your label type");
    }
    return lattice;
  }

  List<Destination<A>> destinations() {
    return List.copyOf(destinations);
  }
}
