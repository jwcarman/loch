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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;
import org.jwcarman.loch.lattice.Axes;
import org.jwcarman.loch.lattice.Axis;
import org.jwcarman.loch.lattice.Ceiling;
import org.jwcarman.loch.lattice.Label;

/**
 * How a store is built: the axes its labels are said on, and the destinations values may reach.
 *
 * <p>Both are wiring-time decisions on purpose. An axis supplied later could reorder what is
 * permitted underneath values already stored, and a destination supplied at a call site would let
 * any code invent its own permission.
 */
public final class DefaultCharter implements Charter {

  /**
   * Everything declared so far, replaced rather than mutated.
   *
   * <p>Immutable so that declaring can be a single atomic transition and therefore cannot race
   * sealing. A mutable map here would reopen that race: a declaration could check that the charter
   * was open, be overtaken by a seal, and then add authority to a charter already in force.
   *
   * <p>Copying the whole thing per declaration is quadratic in the number of portals. At dozens of
   * portals that is nothing, and it is the price of the invariant -- do not "optimise" it back into
   * a mutable map.
   */
  /**
   * Everything declared, frozen at the moment of sealing.
   *
   * <p>Built once rather than per declaration. Writing a charter is single-threaded -- it happens
   * while an application is being wired, before anything it constitutes can act -- so the
   * collections below are ordinary and mutable until they are copied in here.
   *
   * <p>What crosses threads is this, and it crosses exactly once: the atomic write that seals a
   * charter publishes it to every request thread that will ever use a portal. That is why it is
   * immutable and why the copies preserve order -- these are read back into the manifest and into
   * the refusal naming which types a door reads, and a message that differs between runs is a
   * message nobody trusts.
   */
  record Configuration(
      java.util.Map<String, SurrogateType<?>> types,
      java.util.Set<String> sources,
      List<DestinationSpec> destinations,
      List<DerivationSpec<?>> derivations,
      List<QuerySpec<?, ?>> queries,
      AccessContextProvider currentAccess,
      java.util.function.BiPredicate<Label, AccessContext> mayErase) {

    Configuration {
      types = java.util.Collections.unmodifiableMap(new LinkedHashMap<>(types));
      sources = java.util.Collections.unmodifiableSet(new java.util.LinkedHashSet<>(sources));
      destinations = List.copyOf(destinations);
      derivations = List.copyOf(derivations);
      queries = List.copyOf(queries);
    }
  }

  /**
   * Where a charter is in its one irreversible transition.
   *
   * <p>Every portal a charter constitutes shares this one reference, so sealing does not walk them
   * and install anything -- it changes the state of the domain they all belong to, and they are all
   * in force from that instant.
   */
  sealed interface State {
    /** Authority may be constituted, and none of it may be exercised. */
    record Configuring() implements State {}

    /** Authority may be exercised, and none of it may be constituted. */
    record Active(Configuration configuration, Engine engine) implements State {}
  }

  /** What a declaration produced: the charter it leaves behind, and the portal it hands back. */
  private final Axes axes;

  // Written only while this charter is being configured, which is single-threaded by contract: an
  // application wires itself on one thread, and nothing it constitutes can act until it is sealed.
  // Sealing copies all of it into an immutable snapshot and publishes that with one atomic write,
  // which is the only moment any of it crosses to the threads that will use a portal.
  private final java.util.Map<String, SurrogateType<?>> types = new LinkedHashMap<>();
  private final java.util.Set<String> sources = new java.util.LinkedHashSet<>();
  private final List<DestinationSpec> destinations = new ArrayList<>();
  private final List<DerivationSpec<?>> derivations = new ArrayList<>();
  private final List<QuerySpec<?, ?>> queries = new ArrayList<>();
  private AccessContextProvider currentAccess = AccessContextProvider.none();
  private java.util.function.BiPredicate<Label, AccessContext> mayErase = (label, context) -> false;
  private final java.util.concurrent.atomic.AtomicReference<State> lifecycle =
      new java.util.concurrent.atomic.AtomicReference<>(new State.Configuring());

  /**
   * The questions this charter asks about every value it holds.
   *
   * <p>Constructor arguments because they are not configuration -- they are what this charter is.
   * They decide what a label is able to say at all and what a stored row is decoded against, so a
   * charter cannot meaningfully exist before them.
   */
  public DefaultCharter(Axes axes) {
    this.axes = Objects.requireNonNull(axes, "a charter needs axes");
  }

  /** The same, for an application naming its axes inline rather than handing over a schema. */
  @SafeVarargs
  public DefaultCharter(Axis<?>... axes) {
    this(Axes.of(axes));
  }

  /** The schema this charter was constituted with. */
  public Axes axes() {
    return axes;
  }

  /**
   * Brings every portal this charter constituted into force at once, against this storage.
   *
   * <p>Irreversible. After it returns, nothing further may be declared and everything already
   * declared works. There is no way back: no unseal, no rebind, no replacing the storage.
   */
  public void seal(Storage storage) {
    Objects.requireNonNull(storage, "a charter is sealed to a storage");
    State current = lifecycle.get();
    if (!(current instanceof State.Configuring)) {
      throw new IllegalStateException("this charter is already sealed");
    }
    Configuration configuration =
        new Configuration(
            types, sources, destinations, derivations, queries, currentAccess, mayErase);
    Engine engine = new Engine(axes, configuration, storage);
    // One write, and every portal this charter constituted is in force. It is also the only moment
    // any of this crosses a thread, which is why the snapshot above is taken first.
    if (!lifecycle.compareAndSet(current, new State.Active(configuration, engine))) {
      throw new IllegalStateException("this charter was sealed while it was being sealed");
    }
  }

  /** Whether this charter has been brought into force. */
  public boolean sealed() {
    return lifecycle.get() instanceof State.Active;
  }

  /**
   * Refuses anything further once this charter is in force.
   *
   * <p>A security invariant rather than an ergonomic one: an authority graph that can still grow is
   * not one anybody can reason about. Writing a charter is single-threaded by contract, so this is
   * a state check rather than a transition -- there is nothing to race with, because nothing else
   * is declaring and the only thread that could seal is this one.
   */
  private void stillWriting() {
    if (!(lifecycle.get() instanceof State.Configuring)) {
      throw new IllegalStateException(
          "nothing further can be declared: this charter has been sealed, and an authority graph"
              + " that can still grow is not one anybody can reason about");
    }
  }

  /** The engine a portal reaches through, or a refusal saying why it cannot. */
  static Engine engineOf(
      java.util.concurrent.atomic.AtomicReference<State> lifecycle, String what) {
    return switch (lifecycle.get()) {
      case State.Active active -> active.engine();
      case State.Configuring _ ->
          throw new IllegalStateException(
              what
                  + " cannot be exercised before its charter is sealed. Authority is constituted"
                  + " while a charter is being written and comes into force when it is sealed;"
                  + " this one was asked to act before that happened.");
    };
  }

  java.util.concurrent.atomic.AtomicReference<State> lifecycle() {
    return lifecycle;
  }

  /**
   * What has been declared, from wherever it currently lives.
   *
   * <p>While writing, that is the fields themselves, read on the thread that is writing them. Once
   * sealed it is the snapshot, read through the atomic that published it -- which is what makes
   * reporting safe from a request thread.
   */
  private Configuration configuration() {
    return switch (lifecycle.get()) {
      case State.Active active -> active.configuration();
      case State.Configuring _ ->
          new Configuration(
              types, sources, destinations, derivations, queries, currentAccess, mayErase);
    };
  }

  /** Somewhere values may go. Registered once; referenced by name forever after. */
  @Override
  public DefaultCharter destination(DestinationSpec destination) {
    Objects.requireNonNull(destination, "a destination must not be null");
    stillWriting();
    destinations.add(destination);
    return this;
  }

  // ------------------------------------------------------------------ the types it will keep

  /**
   * Records a type and refuses a name that already means something else.
   *
   * <p>Called by every declaration, so the check does not depend on how the type was declared. A
   * name has to identify one type: two of them sharing a name means a reader is handed the wrong
   * one, and finding that out at startup beats finding it out from a decode failure in production.
   */
  <T> SurrogateType<T> registered(SurrogateType<T> declared) {
    SurrogateType<?> existing = types.get(declared.name());
    if (existing != null && !existing.type().getType().equals(declared.type().getType())) {
      throw new IllegalStateException(
          ("two types both want the name '%s': %s and %s. A stored name has to identify one type,"
                  + " or a reader gets handed the wrong one. Name one of them explicitly.")
              .formatted(
                  declared.name(),
                  existing.type().getType().getTypeName(),
                  declared.type().getType().getTypeName()));
    }
    return declared;
  }

  /** Everything this charter was told it may keep, for the manifest. */
  java.util.Collection<SurrogateType<?>> types() {
    return java.util.List.copyOf(configuration().types().values());
  }

  /** Validates each type and folds it into the configuration the caller is about to leave. */
  void recording(SurrogateType<?>... declared) {
    for (SurrogateType<?> type : declared) {
      registered(type);
      types.put(type.name(), type);
    }
  }

  // ------------------------------------------------------------------ declaring capabilities

  /** A source whose label depends on neither what arrives nor who is acting. */
  public <T> Conceal<T> source(String name, SurrogateType<T> type, Label label) {
    Objects.requireNonNull(label, "a source needs a label");
    return source(name, type, (value, context) -> label);
  }

  /** The same, for a label that does not depend on what is arriving. */
  public <T> Conceal<T> source(
      String name,
      SurrogateType<T> type,
      java.util.function.Function<AccessContext, Label> labelling) {
    return source(name, type, (value, context) -> labelling.apply(context));
  }

  public <T> Conceal<T> source(
      String name,
      SurrogateType<T> type,
      java.util.function.BiFunction<T, AccessContext, Label> labelling) {
    Objects.requireNonNull(name, "a source needs a name");
    Objects.requireNonNull(type, "a source needs to know what it accepts");
    Objects.requireNonNull(labelling, "a source needs to say how it labels what arrives");
    stillWriting();
    if (!sources.add(name)) {
      throw new IllegalStateException("two sources are registered as '" + name + "'");
    }
    recording(type);
    var lifecycle = lifecycle();
    String what = "source '" + name + "'";
    return new Conceal<T>() {
      @Override
      public Surrogate<T> conceal(T value) {
        return engineOf(lifecycle, what).concealVia(name, type, labelling, value);
      }

      @Override
      public String toString() {
        return what;
      }
    };
  }

  /**
   * The same, for a label that does not depend on what is arriving.
   *
   * <p>The common case: a door knows what it is, so mail from customers is untrusted whatever it
   * says. Reach for the other form when the label is a property of the value -- a classification
   * marking inside a document, a sender the ingest verified, a scan that found card numbers.
   */
  /** A source whose label depends on neither who is acting nor what is arriving. */
  /**
   * Declares somewhere values may go: what may reach it, and what it reads.
   *
   * <p>Both restrictions are settled here and neither can be widened afterwards. The ceiling says
   * which labels may arrive; {@code reads} says which types come back out. Because both are fixed
   * before any reader exists, a reader is a typed view rather than a grant -- which is what makes
   * one safe to constitute on demand, and the destination itself safe to hand to the service that
   * talks to that subsystem.
   *
   * @param reads every type this destination will hand over, and no others
   */
  @SafeVarargs
  public final SurrogateDestination destination(
      String name,
      java.util.function.Function<AccessContext, Ceiling> ceiling,
      SurrogateType<?>... reads) {
    Objects.requireNonNull(name, "a destination needs a name");
    Objects.requireNonNull(ceiling, "a destination needs a ceiling");
    if (reads.length == 0) {
      throw new IllegalStateException(
          "'"
              + name
              + "' has to say which types it reads. A destination that reads anything reads"
              + " everything its ceiling admits, including whatever gets stored at that label"
              + " next year.");
    }
    // Declaration order, not hash order: this list ends up in an error message somebody reads.
    java.util.Set<String> names = new java.util.LinkedHashSet<>();
    for (SurrogateType<?> type : reads) {
      names.add(type.name());
    }
    stillWriting();
    recording(reads);
    destinations.add(Destinations.varying(name, ceiling));
    return new Door(name, java.util.Collections.unmodifiableSet(names), lifecycle());
  }

  /** The same, for a ceiling that does not depend on who is asking. */
  @SafeVarargs
  public final SurrogateDestination destination(
      String name, Ceiling ceiling, SurrogateType<?>... reads) {
    Objects.requireNonNull(ceiling, "a destination needs a ceiling");
    return destination(name, context -> ceiling, reads);
  }

  /** The implementation of a destination: a name, what it reads, and what it is attached to. */
  private record Door(
      String name,
      java.util.Set<String> reads,
      java.util.concurrent.atomic.AtomicReference<State> lifecycle)
      implements SurrogateDestination {

    @Override
    public <T> Reveal<T> reading(SurrogateType<T> type) {
      Objects.requireNonNull(type, "a reader needs to say what comes out of it");
      if (!reads.contains(type.name())) {
        throw new IllegalStateException(
            ("'%s' does not read %s. It was declared to read %s, and a reader cannot add to that"
                    + " list.")
                .formatted(name, type.name(), reads));
      }
      String door = name;
      var lifecycle = lifecycle();
      String what = "'" + door + "' reading " + type.name();
      return new Reveal<>() {
        @Override
        public SurrogateType<T> type() {
          return type;
        }

        @Override
        public Revealed<T> reveal(Surrogate<T> surrogate) {
          return engineOf(lifecycle, what).revealVia(surrogate, type, door);
        }

        @Override
        public String toString() {
          return what;
        }
      };
    }
  }

  /**
   * Mints the authority to make one value from one other. Configuration time only.
   *
   * <p>Five arities, one per shape, because the JVM has no variadic generics and every library that
   * has faced this made the same choice: {@code kotlinx.coroutines} gives {@code Flow.combine}
   * overloads for two through five flows, as do RxJava and Reactor for {@code zip}. Beyond five, or
   * where the parents share a type, use {@link #fold}.
   */
  /** The same, for types already declared. */
  @Override
  public <I, O> Derivation<I, O> derivation(
      String name,
      SurrogateType<I> input,
      SurrogateType<O> output,
      Function<I, O> function,
      java.util.function.Consumer<DerivationConfig> customizer) {
    return constitute(
        name,
        List.<SurrogateType<?>>of(input),
        output,
        (values, context) ->
            Optional.ofNullable(function.apply(input.type().rawClass().cast(values.getFirst()))),
        false,
        customizer,
        (spec, lifecycle) ->
            new Derivation<I, O>() {
              @Override
              public Derived<O> derive(Surrogate<I> parent) {
                return engineOf(lifecycle, "\'" + spec.name() + "\'")
                    .deriveVia(spec, List.of(parent));
              }
            });
  }

  /**
   * The same, for a derivation that may decline: a lookup that finds nothing, a check that fails.
   */
  /** The same, for types already declared. */
  @Override
  public <I, O> Derivation<I, O> checking(
      String name,
      SurrogateType<I> input,
      SurrogateType<O> output,
      java.util.function.BiFunction<I, AccessContext, Optional<O>> function,
      java.util.function.Consumer<DerivationConfig> customizer) {
    return constitute(
        name,
        List.<SurrogateType<?>>of(input),
        output,
        (values, context) ->
            function.apply(input.type().rawClass().cast(values.getFirst()), context),
        false,
        customizer,
        (spec, lifecycle) ->
            new Derivation<I, O>() {
              @Override
              public Derived<O> derive(Surrogate<I> parent) {
                return engineOf(lifecycle, "\'" + spec.name() + "\'")
                    .deriveVia(spec, List.of(parent));
              }
            });
  }

  /**
   * Mints the authority to fold any number of values of one type into a new one.
   *
   * <p>The result carries the join of every parent's label, so folding two tenants' data yields
   * something labelled for both, which no destination admits.
   */
  /** The same, for types already declared. */
  @Override
  public <I, O> Fold<I, O> fold(
      String name,
      SurrogateType<I> input,
      SurrogateType<O> output,
      Function<List<I>, O> function,
      java.util.function.Consumer<DerivationConfig> customizer) {
    return constitute(
        name,
        List.<SurrogateType<?>>of(input),
        output,
        (values, context) ->
            Optional.ofNullable(
                function.apply(values.stream().map(v -> input.type().rawClass().cast(v)).toList())),
        true,
        customizer,
        (spec, lifecycle) ->
            new Fold<I, O>() {
              @Override
              public Derived<O> fold(List<Surrogate<I>> parents) {
                return engineOf(lifecycle, "\'" + spec.name() + "\'")
                    .deriveVia(spec, List.copyOf(parents));
              }
            });
  }

  /**
   * Declares one derivation-shaped authority and hands back the portal.
   *
   * <p>Every arity comes through here, which is why the ceiling check lives in one place. A
   * derivation reads plaintext, so it has to say what it may look at; there is no default, because
   * a default here would be a policy nobody wrote.
   */
  private <O, C> C constitute(
      String name,
      List<SurrogateType<?>> inputTypes,
      SurrogateType<O> outputType,
      java.util.function.BiFunction<List<Object>, AccessContext, Optional<O>> function,
      boolean fold,
      java.util.function.Consumer<DerivationConfig> customizer,
      java.util.function.BiFunction<
              DerivationSpec<O>, java.util.concurrent.atomic.AtomicReference<State>, C>
          capability) {
    Objects.requireNonNull(name, "a derivation needs a name");
    Objects.requireNonNull(customizer, "a derivation needs to say what it may read");
    DerivationConfig settings = new DerivationConfig();
    customizer.accept(settings);
    if (settings.ceiling() == null) {
      throw new IllegalStateException(
          "'"
              + name
              + "' reads plaintext, so it needs a ceiling: call accepting(...) with what it may"
              + " look at, saying any() on the axes it is deliberately broad about");
    }
    DerivationSpec<O> spec =
        new DerivationSpec<>(
            name,
            inputTypes,
            outputType,
            function,
            settings.ceiling(),
            settings.relabel(),
            settings.availableTo(),
            fold);
    stillWriting();
    SurrogateType<?>[] declared = new SurrogateType<?>[inputTypes.size() + 1];
    inputTypes.toArray(declared);
    declared[inputTypes.size()] = outputType;
    recording(declared);
    derivations.add(spec);
    return capability.apply(spec, lifecycle());
  }

  List<DerivationSpec<?>> derivations() {
    return configuration().derivations();
  }

  /**
   * Where the access happening right now comes from.
   *
   * <p>Identity is known at the edge -- a request, a message, a session -- and needed at the gate,
   * which may be many layers down. Threading an {@code AccessContext} parameter through all of them
   * would make the safety feature the most annoying thing in the codebase, and annoying safety
   * features get routed around.
   *
   * <p>So the application says once where the answer lives. A {@code ThreadLocal}, a {@code
   * ScopedValue}, Spring's {@code SecurityContextHolder} -- a store does not care, and has no
   * opinion about how a request scope works.
   *
   * <p>Whatever this returns is taken as fact. It is the one input a caller cannot argue with,
   * which is why it must come from somewhere a caller does not control.
   *
   * <pre>{@code
   * .currentAccess(() -> AccessContext.of(Map.of(
   *     "tenant", CurrentTenant.get(),
   *     "principal", SecurityContextHolder.getContext().getAuthentication().getName())))
   * }</pre>
   *
   * <p>An application with no notion of identity says nothing and every context is empty.
   */
  @Override
  public DefaultCharter currentAccess(AccessContextProvider currentAccess) {
    Objects.requireNonNull(currentAccess, "an access source must not be null");
    stillWriting();
    this.currentAccess = currentAccess;
    return this;
  }

  AccessContextProvider currentAccess() {
    return currentAccess;
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
   * keeps a store that cannot.
   *
   * <p><b>Descendants go regardless.</b> The check is against the root, and everything derived from
   * it is removed whether or not it is labelled more constrained -- which is what erasure means. A
   * value derived from two customers dies with either of them.
   */
  @Override
  public DefaultCharter mayErase(java.util.function.BiPredicate<Label, AccessContext> mayErase) {
    Objects.requireNonNull(mayErase, "an erasure policy must not be null");
    stillWriting();
    this.mayErase = mayErase;
    return this;
  }

  java.util.function.BiPredicate<Label, AccessContext> mayErase() {
    return mayErase;
  }

  List<DestinationSpec> destinations() {
    return configuration().destinations();
  }

  java.util.Set<String> sources() {
    return configuration().sources();
  }

  /**
   * Mints the authority to ask one question of a value without the value leaving.
   *
   * <p>Boolean, and registered here rather than named at a call site, for the reasons set out on
   * {@link Query}.
   */
  /** The same, for a type already declared. */
  @Override
  public <I, Q> Query<I, Q> query(
      String name,
      SurrogateType<I> input,
      Class<Q> against,
      Query.Asking<I, Q> asking,
      java.util.function.Consumer<QueryConfig> customizer) {
    Objects.requireNonNull(name, "a question needs a name");
    Objects.requireNonNull(customizer, "a question needs to say what it may read");
    QueryConfig settings = new QueryConfig();
    customizer.accept(settings);
    if (settings.ceiling() == null) {
      throw new IllegalStateException(
          "'"
              + name
              + "' reads plaintext to answer, so it needs a ceiling: call accepting(...) with what"
              + " it may look at, saying any() on the axes it is deliberately broad about");
    }
    QuerySpec<I, Q> spec =
        new QuerySpec<>(name, input, asking, settings.ceiling(), settings.availableTo());
    stillWriting();
    recording(input);
    queries.add(spec);
    var lifecycle = lifecycle();
    String what = "query '" + name + "'";
    return new Query<I, Q>() {
      @Override
      public Answer ask(Surrogate<I> about, Q against) {
        return engineOf(lifecycle, what).askVia(spec, about, against);
      }

      @Override
      public String toString() {
        return what;
      }
    };
  }

  /** What a query still needs said about it before it becomes a capability. */
  List<QuerySpec<?, ?>> queries() {
    return configuration().queries();
  }

  // ------------------------------------------------------------------ what a charter reports

  /**
   * What this charter permits, rendered.
   *
   * <p>Administrative, and deliberately not something a portal offers. Reading it tells you what
   * the system can do; it is not a way to do any of it.
   */
  public Manifest manifest() {
    Configuration configuration = configuration();
    List<Manifest.Entry> doors = new ArrayList<>();
    for (DestinationSpec destination : configuration.destinations()) {
      doors.add(
          new Manifest.Entry(destination.name(), "accepts up to " + accepts(destination), false));
    }
    List<Manifest.Entry> derivations = new ArrayList<>();
    for (DerivationSpec<?> derivation : configuration.derivations()) {
      derivations.add(
          new Manifest.Entry(
              derivation.name(),
              "%s -> %s".formatted(reads(derivation), derivation.outputType().name()),
              derivation.privileged()));
    }
    List<Manifest.Entry> questions = new ArrayList<>();
    for (QuerySpec<?, ?> query : configuration.queries()) {
      questions.add(
          new Manifest.Entry(query.name(), "asks about " + query.inputType().name(), false));
    }
    return new Manifest(String.valueOf(Label.nothing()), doors, derivations, questions);
  }

  /**
   * What a door accepts, for the manifest only.
   *
   * <p>Evaluated against an empty access, because a manifest is a statement about the system rather
   * than about one request. A ceiling that reads a tenant will refuse to answer that, and saying so
   * is more honest than printing what it would allow nobody.
   */
  private static String accepts(DestinationSpec destination) {
    try {
      Ceiling ceiling = destination.ceiling(AccessContext.empty());
      return ceiling == null ? "(its ceiling could not be evaluated)" : ceiling.toString();
    } catch (RuntimeException e) {
      return "(its ceiling could not be evaluated)";
    }
  }

  /** How a derivation's parents read: positionally, or as many of one type. */
  private static String reads(DerivationSpec<?> spec) {
    String types =
        spec.inputTypes().stream()
            .map(SurrogateType::name)
            .collect(java.util.stream.Collectors.joining(", "));
    return spec.fold() ? "many " + types : types;
  }

  /** How a value is labelled. For a report or an operator, never for a decision. */
  public Label label(Surrogate<?> surrogate) {
    return label(surrogate.id());
  }

  /** The same, for an identifier that arrived without its type. */
  public Label label(String id) {
    return engineOf(lifecycle, "this charter").label(id);
  }

  /** Where a value came from. */
  public Lineage lineage(Surrogate<?> surrogate) {
    return lineage(surrogate.id());
  }

  /** The same, for an identifier that arrived without its type. */
  public Lineage lineage(String id) {
    return engineOf(lifecycle, "this charter").lineage(id);
  }

  /** Whether this charter is holding a value at all. */
  public boolean holds(Surrogate<?> surrogate) {
    return holds(surrogate.id());
  }

  /** The same, for an identifier that arrived without its type. */
  public boolean holds(String id) {
    return engineOf(lifecycle, "this charter").holds(id);
  }

  /**
   * Forgets a value and everything derived from it.
   *
   * <p>Still guarded by {@code mayErase} against the acting context rather than by holding a
   * portal, which makes it the last authority here that is checked rather than held.
   */
  public int erase(Surrogate<?> root) {
    return engineOf(lifecycle, "this charter").erase(root);
  }
}
