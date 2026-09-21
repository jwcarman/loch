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
import org.jwcarman.codec.spi.TypeRef;
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
  private java.util.function.BiPredicate<A, AccessContext> mayErase = (label, context) -> false;
  private java.util.function.BiPredicate<A, AccessContext> mayHold = (label, context) -> true;
  private final List<Destination<A>> destinations = new ArrayList<>();
  private final List<Derivation<A, ?, ?>> derivations = new ArrayList<>();
  private final List<Question<A, ?, ?>> questions = new ArrayList<>();
  private final java.util.Set<InletId> inlets = new java.util.LinkedHashSet<>();
  private DefaultLoch<A> bound;

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

  // ------------------------------------------------------------------ minting capabilities

  /**
   * Mints the authority to put values into this loch at one label. Configuration time only.
   *
   * <p>Returns the {@link Inlet} rather than this config, so the fluent chain stops here and the
   * caller has to capture what it was given. That is the point: there is no way to ask for an inlet
   * afterwards, so a capability nobody kept is a capability nobody has.
   *
   * <p>The function fixes the parts of the label that are properties of the door -- what this is,
   * how far it is trusted, what kind of data arrives here -- and may read the rest, typically a
   * tenant, from ambient context.
   */
  public <T> Inlet<T> inlet(
      InletId id, TypeRef<T> type, java.util.function.Function<AccessContext, A> labelling) {
    Objects.requireNonNull(id, "an inlet needs a name");
    Objects.requireNonNull(type, "an inlet needs to know what it accepts");
    Objects.requireNonNull(labelling, "an inlet needs to say how it labels what arrives");
    if (!inlets.add(id)) {
      throw new IllegalStateException("two inlets are registered as '" + id + "'");
    }
    return new Inlet<>() {
      @Override
      public InletId id() {
        return id;
      }

      @Override
      public Handle<T> hold(T value) {
        return engine().holdVia(id, type, labelling, value);
      }

      @Override
      public String toString() {
        return "inlet '" + id + "'";
      }
    };
  }

  /** The same, for a type with no generic parameters of its own. */
  public <T> Inlet<T> inlet(
      InletId id, Class<T> type, java.util.function.Function<AccessContext, A> labelling) {
    return inlet(id, TypeRef.of(type), labelling);
  }

  /** An inlet whose label does not depend on who is acting. */
  public <T> Inlet<T> inlet(InletId id, Class<T> type, A label) {
    Objects.requireNonNull(label, "an inlet needs a label");
    return inlet(id, TypeRef.of(type), context -> label);
  }

  /**
   * Mints the authority to read plaintext out of this loch at one ceiling. Configuration time only.
   *
   * <p>Also registers the destination, so the manifest still enumerates it and audit lines still
   * name it. The id remains what this door is called; it stops being a way to reach it.
   */
  public Outlet outlet(DestinationId id, java.util.function.Function<AccessContext, A> ceiling) {
    destination(Destinations.varying(id, ceiling));
    return new Outlet() {
      @Override
      public DestinationId id() {
        return id;
      }

      @Override
      public <T> Dereferenced<T> read(Handle<T> held) {
        return read(held, AccessContext.empty());
      }

      @Override
      public <T> Dereferenced<T> read(Handle<T> held, AccessContext context) {
        return engine().dereference(held, id, context);
      }

      @Override
      public String toString() {
        return "outlet '" + id + "'";
      }
    };
  }

  /** An outlet whose ceiling does not depend on who is asking. */
  public Outlet outlet(DestinationId id, A ceiling) {
    Objects.requireNonNull(ceiling, "an outlet needs a ceiling");
    return outlet(id, context -> ceiling);
  }

  /**
   * Handed to every capability minted here, once the loch they belong to exists.
   *
   * <p>A capability is minted while the configuration lambda is still running, which is before
   * there is anything for it to act on. So it holds this config and reaches the loch through it,
   * and until the loch has been built there is nothing to reach. Refusing loudly matters more than
   * it looks: the failure mode this replaces is a capability that silently does nothing, which
   * every happy-path test would pass.
   */
  void bind(DefaultLoch<A> loch) {
    if (this.bound != null) {
      throw new IllegalStateException(
          "this configuration has already built a loch; build a second one from a fresh config, or"
              + " its capabilities would write into the first");
    }
    this.bound = loch;
  }

  private DefaultLoch<A> engine() {
    if (bound == null) {
      throw new IllegalStateException(
          "this capability was minted but the loch it belongs to was never finished being built");
    }
    return bound;
  }

  /** A question that can be asked of a held value without the value leaving. */
  public LochConfig<A> question(Question<A, ?, ?> question) {
    questions.add(Objects.requireNonNull(question, "a question must not be null"));
    return this;
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

  /**
   * Who may erase what.
   *
   * <p>Takes the label of the value being erased as well as who is asking, because who alone is not
   * enough: a policy that only asks the caller's role lets one tenant's compliance officer destroy
   * another tenant's records. Whatever the rule, it has to see what is about to be destroyed.
   *
   * <pre>{@code
   * .mayErase((label, ctx) ->
   *     ctx.has("role", "compliance") && LATTICE.permits(label, everythingIMayRead(ctx)))
   * }</pre>
   *
   * <p>Refuses everyone until this says otherwise, because erasure is the one operation a label
   * does not govern on its own. Every other gate asks whether a value may be <i>disclosed</i>
   * somewhere; a label has nothing to say about whether it may be <i>destroyed</i>, and "possession
   * is not authority" is a rule about reading. An application that never erases says nothing and
   * keeps a loch that cannot.
   *
   * <p><b>Descendants go regardless.</b> The check is against the root, and everything derived from
   * it is removed whether or not it is labelled more constrained -- which is what erasure means. A
   * value derived from two customers dies with either of them.
   */
  public LochConfig<A> mayErase(java.util.function.BiPredicate<A, AccessContext> mayErase) {
    this.mayErase = Objects.requireNonNull(mayErase, "an erasure policy must not be null");
    return this;
  }

  java.util.function.BiPredicate<A, AccessContext> mayErase() {
    return mayErase;
  }

  /**
   * What labels this access may create data at.
   *
   * <p>Every other gate decides whether a value may be <i>read</i>. This one decides whether it may
   * be <i>written</i>, and the two are genuinely different questions: Bell-LaPadula famously
   * permits a low subject to write a high object it cannot read, which is where the phrase "blind
   * write up" comes from, and Biba forbids it precisely because creating data more trusted than you
   * are is how a forgery becomes a fact.
   *
   * <p>Concretely: without this, code acting for one tenant can hold a value labelled as another
   * tenant's endorsed record, and that tenant will later read it as its own authoritative data.
   * Nothing downstream can tell the difference, because by then it is correctly labelled.
   *
   * <pre>{@code
   * .mayHold((label, ctx) -> label.tenant().resolved().filter(t -> ctx.has("tenant", t)).isPresent())
   * }</pre>
   *
   * <p><b>Permissive by default</b>, unlike the other policies here, and the exception is worth
   * justifying rather than hiding. {@code hold} is the entry point every application uses, often
   * before it has any notion of who is acting -- a mailbox listener, a batch import, a migration --
   * and refusing by default would make the first thing anyone writes fail. An application handling
   * more than one tenant's data should set it.
   */
  public LochConfig<A> mayHold(java.util.function.BiPredicate<A, AccessContext> mayHold) {
    this.mayHold = Objects.requireNonNull(mayHold, "a hold policy must not be null");
    return this;
  }

  java.util.function.BiPredicate<A, AccessContext> mayHold() {
    return mayHold;
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

  java.util.Set<InletId> inlets() {
    return java.util.Set.copyOf(inlets);
  }
}
