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
public final class Charter {

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
  record Configuration(
      java.util.Map<String, SurrogateType<?>> types,
      java.util.Set<String> sources,
      List<DestinationSpec> destinations,
      List<DerivationSpec<?>> derivations,
      List<QuerySpec<?, ?>> queries,
      AccessContextProvider currentAccess,
      java.util.function.BiPredicate<Label, AccessContext> mayErase) {

    static Configuration empty() {
      return new Configuration(
          java.util.Map.of(),
          java.util.Set.of(),
          List.of(),
          List.of(),
          List.of(),
          AccessContextProvider.none(),
          (label, context) -> false);
    }

    Configuration withType(SurrogateType<?> type) {
      java.util.Map<String, SurrogateType<?>> next = new LinkedHashMap<>(types);
      next.put(type.name(), type);
      return new Configuration(
          next, sources, destinations, derivations, queries, currentAccess, mayErase);
    }

    Configuration withSource(String name) {
      java.util.Set<String> next = new java.util.LinkedHashSet<>(sources);
      next.add(name);
      return new Configuration(
          types, next, destinations, derivations, queries, currentAccess, mayErase);
    }

    Configuration with(DestinationSpec destination) {
      List<DestinationSpec> next = new ArrayList<>(destinations);
      next.add(destination);
      return new Configuration(types, sources, next, derivations, queries, currentAccess, mayErase);
    }

    Configuration with(DerivationSpec<?> derivation) {
      List<DerivationSpec<?>> next = new ArrayList<>(derivations);
      next.add(derivation);
      return new Configuration(
          types, sources, destinations, next, queries, currentAccess, mayErase);
    }

    Configuration with(QuerySpec<?, ?> query) {
      List<QuerySpec<?, ?>> next = new ArrayList<>(queries);
      next.add(query);
      return new Configuration(
          types, sources, destinations, derivations, next, currentAccess, mayErase);
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
    Configuration configuration();

    /** Authority may be constituted, and none of it may be exercised. */
    record Configuring(Configuration configuration) implements State {}

    /** Authority may be exercised, and none of it may be constituted. */
    record Active(Configuration configuration, Engine engine) implements State {}
  }

  /** What a declaration produced: the charter it leaves behind, and the portal it hands back. */
  private record Declared<P>(Configuration configuration, P portal) {}

  private final List<Axis<?>> axes;
  private final java.util.concurrent.atomic.AtomicReference<State> lifecycle =
      new java.util.concurrent.atomic.AtomicReference<>(
          new State.Configuring(Configuration.empty()));

  /**
   * The questions this charter asks about every value it holds.
   *
   * <p>Constructor arguments because they are not configuration -- they are what this charter is.
   * They decide what a label is able to say at all and what a stored row is decoded against, so a
   * charter cannot meaningfully exist before them.
   */
  @SafeVarargs
  public Charter(Axis<?>... axes) {
    Objects.requireNonNull(axes, "a charter needs axes");
    if (axes.length == 0) {
      throw new IllegalArgumentException(
          "a charter needs at least one axis: a label that says nothing about anything is below"
              + " every ceiling, which means readable by everyone");
    }
    java.util.Set<String> named = new java.util.LinkedHashSet<>();
    for (Axis<?> axis : axes) {
      Objects.requireNonNull(axis, "an axis must not be null");
      if (!named.add(axis.name())) {
        throw new IllegalArgumentException(
            "two axes both want the name '"
                + axis.name()
                + "', and a stored label is keyed by name, so one would read as the other");
      }
    }
    this.axes = List.of(axes);
  }

  /** The axes this charter was constituted with. */
  public List<Axis<?>> axes() {
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
    if (!(current instanceof State.Configuring(Configuration configuration))) {
      throw new IllegalStateException("this charter is already sealed");
    }
    Engine engine = new Engine(axes, configuration, storage);
    if (!lifecycle.compareAndSet(current, new State.Active(configuration, engine))) {
      throw new IllegalStateException("this charter was changed while it was being sealed");
    }
  }

  /** Whether this charter has been brought into force. */
  public boolean sealed() {
    return lifecycle.get() instanceof State.Active;
  }

  /**
   * Constitutes one new authority, as a single atomic transition.
   *
   * <p>Fails rather than retries on a concurrent change. Two threads configuring one charter is a
   * programming error at bootstrap, not contention worth absorbing.
   */
  private <P> P declare(java.util.function.Function<Configuration, Declared<P>> declaration) {
    State current = lifecycle.get();
    if (!(current instanceof State.Configuring(Configuration configuration))) {
      throw new IllegalStateException(
          "nothing further can be declared: this charter has been sealed, and an authority graph"
              + " that can still grow is not one anybody can reason about");
    }
    Declared<P> result = declaration.apply(configuration);
    if (!lifecycle.compareAndSet(current, new State.Configuring(result.configuration()))) {
      throw new IllegalStateException(
          "this charter was changed by another thread while something was being declared");
    }
    return result.portal();
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

  private Configuration configuration() {
    return lifecycle.get().configuration();
  }

  /** Somewhere values may go. Registered once; referenced by name forever after. */
  public Charter destination(DestinationSpec destination) {
    Objects.requireNonNull(destination, "a destination must not be null");
    declare(configuration -> new Declared<>(configuration.with(destination), destination));
    return this;
  }

  // ------------------------------------------------------------------ the types it will keep

  /**
   * Records a type and refuses a name that already means something else.
   *
   * <p>Called by every mint, so the check does not depend on how the type was declared. A name has
   * to identify one type: two of them sharing a name means a reader is handed the wrong one, and
   * finding that out at startup beats finding it out from a decode failure in production.
   */
  <T> SurrogateType<T> registered(SurrogateType<T> declared) {
    SurrogateType<?> existing = configuration().types().get(declared.name());
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
  private Configuration recording(Configuration configuration, SurrogateType<?>... declared) {
    Configuration next = configuration;
    for (SurrogateType<?> type : declared) {
      registered(type);
      next = next.withType(type);
    }
    return next;
  }

  // ------------------------------------------------------------------ minting capabilities

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
    var lifecycle = lifecycle();
    String what = "source '" + name + "'";
    return declare(
        configuration -> {
          if (configuration.sources().contains(name)) {
            throw new IllegalStateException("two sources are registered as '" + name + "'");
          }
          return new Declared<>(
              recording(configuration, type).withSource(name),
              new Conceal<T>() {
                @Override
                public Surrogate<T> conceal(T value) {
                  return engineOf(lifecycle, what).concealVia(name, type, labelling, value);
                }

                @Override
                public String toString() {
                  return what;
                }
              });
        });
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
   * one safe to mint on demand, and the destination itself safe to hand to the service that talks
   * to that subsystem.
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
    var lifecycle = lifecycle();
    return declare(
        configuration ->
            new Declared<>(
                recording(configuration, reads).with(Destinations.varying(name, ceiling)),
                new Door(name, java.util.Collections.unmodifiableSet(names), lifecycle)));
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
  public <I, O> Minting<O, Derivation<I, O>> derivation(
      String name, SurrogateType<I> input, SurrogateType<O> output, Function<I, O> function) {
    return new Minting<>(
        this,
        name,
        List.<SurrogateType<?>>of(input),
        output,
        (values, context) ->
            Optional.ofNullable(function.apply(input.type().rawClass().cast(values.getFirst()))),
        false,
        (spec, lifecycle) ->
            new Derivation<>() {
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
  public <I, O> Minting<O, Derivation<I, O>> checking(
      String name,
      SurrogateType<I> input,
      SurrogateType<O> output,
      java.util.function.BiFunction<I, AccessContext, Optional<O>> function) {
    return new Minting<>(
        this,
        name,
        List.<SurrogateType<?>>of(input),
        output,
        (values, context) ->
            function.apply(input.type().rawClass().cast(values.getFirst()), context),
        false,
        (spec, lifecycle) ->
            new Derivation<>() {
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
  public <I, O> Minting<O, Fold<I, O>> fold(
      String name, SurrogateType<I> input, SurrogateType<O> output, Function<List<I>, O> function) {
    return new Minting<>(
        this,
        name,
        List.<SurrogateType<?>>of(input),
        output,
        (values, context) ->
            Optional.ofNullable(
                function.apply(values.stream().map(v -> input.type().rawClass().cast(v)).toList())),
        true,
        (spec, lifecycle) ->
            new Fold<>() {
              @Override
              public Derived<O> fold(List<Surrogate<I>> parents) {
                return engineOf(lifecycle, "\'" + spec.name() + "\'")
                    .deriveVia(spec, List.copyOf(parents));
              }
            });
  }

  /**
   * What a derivation still needs said about it before it becomes a capability.
   *
   * <p>One type for every arity, because everything left to say -- what it may read, whether it
   * lowers, where it is offered -- is about labels and contexts, not about how many parents there
   * are. {@code C} is whatever this eventually mints.
   */
  public static final class Minting<O, C> {

    private final Charter config;
    private final String name;
    private final List<SurrogateType<?>> inputTypes;
    private final SurrogateType<O> outputType;
    private final java.util.function.BiFunction<List<Object>, AccessContext, Optional<O>> function;
    private final boolean fold;
    private final java.util.function.BiFunction<
            DerivationSpec<O>, java.util.concurrent.atomic.AtomicReference<State>, C>
        capability;
    private java.util.function.Function<AccessContext, Ceiling> ceiling;
    private java.util.function.UnaryOperator<Label> relabel;
    private java.util.function.Predicate<AccessContext> availableTo = context -> true;

    private Minting(
        Charter config,
        String name,
        List<SurrogateType<?>> inputTypes,
        SurrogateType<O> outputType,
        java.util.function.BiFunction<List<Object>, AccessContext, Optional<O>> function,
        boolean fold,
        java.util.function.BiFunction<
                DerivationSpec<O>, java.util.concurrent.atomic.AtomicReference<State>, C>
            capability) {
      this.config = config;
      this.name = name;
      this.inputTypes = inputTypes;
      this.outputType = outputType;
      this.function = function;
      this.fold = fold;
      this.capability = capability;
    }

    /** The most constrained parent this will accept. */
    public Minting<O, C> accepting(Ceiling ceiling) {
      Objects.requireNonNull(ceiling, "a ceiling must not be null");
      return accepting(context -> ceiling);
    }

    /** A ceiling that depends on who is asking, which a tenant always does. */
    public Minting<O, C> accepting(java.util.function.Function<AccessContext, Ceiling> ceiling) {
      this.ceiling = Objects.requireNonNull(ceiling, "a ceiling must not be null");
      return this;
    }

    /** Declares that the result is less constrained than its parents, and by how much. */
    public Minting<O, C> lowering(java.util.function.UnaryOperator<Label> relabel) {
      this.relabel = Objects.requireNonNull(relabel, "a lowering must not be null");
      return this;
    }

    /** Whether this is offered at all, given who is asking. */
    public Minting<O, C> availableTo(java.util.function.Predicate<AccessContext> availableTo) {
      this.availableTo = Objects.requireNonNull(availableTo, "an availability must not be null");
      return this;
    }

    /** Registers it and hands back the capability. Nothing can obtain one any other way. */
    public C mint() {
      if (ceiling == null) {
        throw new IllegalStateException(
            "'"
                + name
                + "' reads plaintext, so it needs a ceiling: call accepting(...) with what it may"
                + " look at, saying any() on the axes it is deliberately broad about");
      }
      DerivationSpec<O> spec =
          new DerivationSpec<>(
              name, inputTypes, outputType, function, ceiling, relabel, availableTo, fold);
      var lifecycle = config.lifecycle();
      SurrogateType<?>[] declared = new SurrogateType<?>[inputTypes.size() + 1];
      inputTypes.toArray(declared);
      declared[inputTypes.size()] = outputType;
      return config.declare(
          configuration ->
              new Declared<>(
                  config.recording(configuration, declared).with(spec),
                  capability.apply(spec, lifecycle)));
    }
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
  public Charter currentAccess(AccessContextProvider currentAccess) {
    Objects.requireNonNull(currentAccess, "an access source must not be null");
    declare(
        configuration ->
            new Declared<>(
                new Configuration(
                    configuration.types(),
                    configuration.sources(),
                    configuration.destinations(),
                    configuration.derivations(),
                    configuration.queries(),
                    currentAccess,
                    configuration.mayErase()),
                this));
    return this;
  }

  AccessContextProvider currentAccess() {
    return configuration().currentAccess();
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
  public Charter mayErase(java.util.function.BiPredicate<Label, AccessContext> mayErase) {
    Objects.requireNonNull(mayErase, "an erasure policy must not be null");
    declare(
        configuration ->
            new Declared<>(
                new Configuration(
                    configuration.types(),
                    configuration.sources(),
                    configuration.destinations(),
                    configuration.derivations(),
                    configuration.queries(),
                    configuration.currentAccess(),
                    mayErase),
                this));
    return this;
  }

  java.util.function.BiPredicate<Label, AccessContext> mayErase() {
    return configuration().mayErase();
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
  public <I, Q> Querying<I, Q> query(
      String name, SurrogateType<I> input, Class<Q> against, Query.Asking<I, Q> asking) {
    Objects.requireNonNull(name, "a query needs a name");
    Objects.requireNonNull(against, "a query needs to say what it is asked against");
    Objects.requireNonNull(asking, "a query needs something to ask");
    return new Querying<>(this, name, input, asking);
  }

  /** What a query still needs said about it before it becomes a capability. */
  public static final class Querying<I, Q> {

    private final Charter config;
    private final String name;
    private final SurrogateType<I> inputType;
    private final Query.Asking<I, Q> asking;
    private java.util.function.Function<AccessContext, Ceiling> ceiling;
    private java.util.function.Predicate<AccessContext> availableTo = context -> true;

    private Querying(
        Charter config, String name, SurrogateType<I> inputType, Query.Asking<I, Q> asking) {
      this.config = config;
      this.name = name;
      this.inputType = inputType;
      this.asking = asking;
    }

    /** The most constrained value this may be asked about. */
    public Querying<I, Q> accepting(java.util.function.Function<AccessContext, Ceiling> ceiling) {
      this.ceiling = Objects.requireNonNull(ceiling, "a ceiling must not be null");
      return this;
    }

    /** Accepts the same thing regardless of who is asking. */
    public Querying<I, Q> accepting(Ceiling ceiling) {
      Objects.requireNonNull(ceiling, "a ceiling must not be null");
      return accepting(context -> ceiling);
    }

    /** Whether this is offered at all, given who is asking. */
    public Querying<I, Q> availableTo(java.util.function.Predicate<AccessContext> availableTo) {
      this.availableTo = Objects.requireNonNull(availableTo, "an availability must not be null");
      return this;
    }

    /** Registers it and hands back the capability. Nothing can obtain one any other way. */
    public Query<I, Q> mint() {
      if (ceiling == null) {
        throw new IllegalStateException(
            "'"
                + name
                + "' reads plaintext to answer, so it needs a ceiling: call accepting(...) with"
                + " what it may look at, saying any() on the axes it is deliberately broad about");
      }
      QuerySpec<I, Q> spec = new QuerySpec<>(name, inputType, asking, ceiling, availableTo);
      var lifecycle = config.lifecycle();
      String what = "query '" + name + "'";
      return config.declare(
          configuration ->
              new Declared<>(
                  config.recording(configuration, inputType).with(spec),
                  new Query<I, Q>() {
                    @Override
                    public Answer ask(Surrogate<I> about, Q against) {
                      return engineOf(lifecycle, what).askVia(spec, about, against);
                    }

                    @Override
                    public String toString() {
                      return what;
                    }
                  }));
    }
  }

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
