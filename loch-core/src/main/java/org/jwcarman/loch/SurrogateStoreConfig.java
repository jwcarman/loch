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
public class SurrogateStoreConfig {

  private List<Axis<?>> axes = List.of();
  private AccessContextProvider ambient = AccessContextProvider.none();
  private java.util.function.BiPredicate<Label, AccessContext> mayErase = (label, context) -> false;
  private final List<DestinationSpec> destinations = new ArrayList<>();
  private final List<DerivationSpec<?>> derivations = new ArrayList<>();
  final List<QuerySpec<?, ?>> queries = new ArrayList<>();
  private final java.util.Set<String> sources = new java.util.LinkedHashSet<>();
  private DefaultSurrogateStore bound;
  private final List<Binding> bindings = new ArrayList<>();
  private final java.util.Map<String, SurrogateType<?>> types = new LinkedHashMap<>();

  /**
   * The questions this store asks about every value it holds. Required.
   *
   * <p>This is the application's whole security vocabulary, and it is the schema: a label may only
   * speak to an axis declared here, a ceiling has to constrain every axis a label speaks to, and a
   * stored row naming an axis no longer declared is refused rather than quietly read without it.
   */
  public SurrogateStoreConfig axes(Axis<?>... axes) {
    Objects.requireNonNull(axes, "a store needs axes");
    if (axes.length == 0) {
      throw new IllegalArgumentException(
          "a store needs at least one axis: a label that says nothing about anything is below every"
              + " ceiling, which means readable by everyone");
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
    return this;
  }

  /** Somewhere values may go. Registered once; referenced by name forever after. */
  public SurrogateStoreConfig destination(DestinationSpec destination) {
    destinations.add(Objects.requireNonNull(destination, "a destination must not be null"));
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
    SurrogateType<?> existing = types.putIfAbsent(declared.name(), declared);
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

  /** Everything this store was told it may keep, for the manifest. */
  java.util.Collection<SurrogateType<?>> types() {
    return java.util.List.copyOf(types.values());
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
    registered(type);
    Binding binding = binding("source '" + name + "'");
    Objects.requireNonNull(type, "a source needs to know what it accepts");
    Objects.requireNonNull(labelling, "a source needs to say how it labels what arrives");
    if (!sources.add(name)) {
      throw new IllegalStateException("two sources are registered as '" + name + "'");
    }
    return new Conceal<>() {
      @Override
      public Surrogate<T> conceal(T value) {
        return binding.engine().concealVia(name, type, labelling, value);
      }

      @Override
      public String toString() {
        return "source '" + name + "'";
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
    java.util.Set<String> names = new java.util.LinkedHashSet<>();
    for (SurrogateType<?> type : reads) {
      names.add(registered(type).name());
    }
    destination(Destinations.varying(name, ceiling));
    // Declaration order, not hash order: this list ends up in an error message somebody reads.
    return new Door(
        name, java.util.Collections.unmodifiableSet(names), binding("destination '" + name + "'"));
  }

  /** The same, for a ceiling that does not depend on who is asking. */
  @SafeVarargs
  public final SurrogateDestination destination(
      String name, Ceiling ceiling, SurrogateType<?>... reads) {
    Objects.requireNonNull(ceiling, "a destination needs a ceiling");
    return destination(name, context -> ceiling, reads);
  }

  /** The implementation of a destination: a name, what it reads, and what it is attached to. */
  private record Door(String name, java.util.Set<String> reads, Binding binding)
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
      Binding bound = binding;
      return new Reveal<>() {
        @Override
        public SurrogateType<T> type() {
          return type;
        }

        @Override
        public Revealed<T> reveal(Surrogate<T> surrogate) {
          return bound.engine().revealVia(surrogate, type, door);
        }

        @Override
        public String toString() {
          return "'" + door + "' reading " + type.name();
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
    registered(input);
    registered(output);
    return new Minting<>(
        this,
        name,
        List.<SurrogateType<?>>of(input),
        output,
        (values, context) ->
            Optional.ofNullable(function.apply(input.type().rawClass().cast(values.getFirst()))),
        false,
        (spec, binding) ->
            new Derivation<>() {
              @Override
              public Derived<O> derive(Surrogate<I> parent) {
                return binding.engine().deriveVia(spec, List.of(parent));
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
    registered(input);
    registered(output);
    return new Minting<>(
        this,
        name,
        List.<SurrogateType<?>>of(input),
        output,
        (values, context) ->
            function.apply(input.type().rawClass().cast(values.getFirst()), context),
        false,
        (spec, binding) ->
            new Derivation<>() {
              @Override
              public Derived<O> derive(Surrogate<I> parent) {
                return binding.engine().deriveVia(spec, List.of(parent));
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
        (spec, binding) ->
            new Fold<>() {
              @Override
              public Derived<O> fold(List<Surrogate<I>> parents) {
                return binding.engine().deriveVia(spec, List.copyOf(parents));
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

    private final SurrogateStoreConfig config;
    private final String name;
    private final List<SurrogateType<?>> inputTypes;
    private final SurrogateType<O> outputType;
    private final java.util.function.BiFunction<List<Object>, AccessContext, Optional<O>> function;
    private final boolean fold;
    private final java.util.function.BiFunction<DerivationSpec<O>, Binding, C> capability;
    private java.util.function.Function<AccessContext, Ceiling> ceiling;
    private java.util.function.UnaryOperator<Label> relabel;
    private java.util.function.Predicate<AccessContext> availableTo = context -> true;

    private Minting(
        SurrogateStoreConfig config,
        String name,
        List<SurrogateType<?>> inputTypes,
        SurrogateType<O> outputType,
        java.util.function.BiFunction<List<Object>, AccessContext, Optional<O>> function,
        boolean fold,
        java.util.function.BiFunction<DerivationSpec<O>, Binding, C> capability) {
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
      config.derivations.add(spec);
      return capability.apply(spec, config.binding("'" + name + "'"));
    }
  }

  /**
   * Handed to every capability minted here, once the store they belong to exists.
   *
   * <p>A capability is minted while the configuration lambda is still running, which is before
   * there is anything for it to act on. So it holds this config and reaches the store through it,
   * and until the store has been built there is nothing to reach. Refusing loudly matters more than
   * it looks: the failure mode this replaces is a capability that silently does nothing, which
   * every happy-path test would pass.
   */
  void bind(DefaultSurrogateStore store) {
    if (this.bound != null) {
      throw new IllegalStateException(
          "this configuration has already built a store; build a second one from a fresh config, or"
              + " its capabilities would write into the first");
    }
    this.bound = store;
    bindings.forEach(binding -> binding.attach(store));
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
    return new Querying<>(this, name, registered(input), asking);
  }

  /** What a query still needs said about it before it becomes a capability. */
  public static final class Querying<I, Q> {

    private final SurrogateStoreConfig config;
    private final String name;
    private final SurrogateType<I> inputType;
    private final Query.Asking<I, Q> asking;
    private java.util.function.Function<AccessContext, Ceiling> ceiling;
    private java.util.function.Predicate<AccessContext> availableTo = context -> true;

    private Querying(
        SurrogateStoreConfig config,
        String name,
        SurrogateType<I> inputType,
        Query.Asking<I, Q> asking) {
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
      config.queries.add(spec);
      Binding binding = config.binding("query '" + name + "'");
      return new Query<>() {
        @Override
        public Answer ask(Surrogate<I> about, Q against) {
          return binding.engine().askVia(spec, about, against);
        }
      };
    }
  }

  List<QuerySpec<?, ?>> queries() {
    return List.copyOf(queries);
  }

  /**
   * The store a capability reaches through, attached when that store is built.
   *
   * <p>Per capability, not per config, and that distinction is the whole safety property. If a
   * capability reached the store through the config, one minted <i>after</i> the store was built
   * would find it already there and work perfectly -- measured doing exactly that: a door minted
   * after startup planted a value at another tenant's label, and a derivation minted after startup
   * read a cardholder token. Binding each capability at construction means a late one is attached
   * to nothing, and says so.
   */
  static final class Binding {

    private final String what;
    private DefaultSurrogateStore store;

    private Binding(String what) {
      this.what = what;
    }

    private void attach(DefaultSurrogateStore store) {
      this.store = store;
    }

    DefaultSurrogateStore engine() {
      if (store == null) {
        throw new IllegalStateException(
            what
                + " is attached to no store. Capabilities are minted while a store is being"
                + " configured and are attached when it is built; this one was minted afterwards,"
                + " so there is nothing for it to act on.");
      }
      return store;
    }
  }

  private Binding binding(String what) {
    Binding binding = new Binding(what);
    bindings.add(binding);
    return binding;
  }

  List<DerivationSpec<?>> derivations() {
    return List.copyOf(derivations);
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
  public SurrogateStoreConfig currentAccess(AccessContextProvider ambient) {
    this.ambient = Objects.requireNonNull(ambient, "an access source must not be null");
    return this;
  }

  AccessContextProvider ambient() {
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
   * keeps a store that cannot.
   *
   * <p><b>Descendants go regardless.</b> The check is against the root, and everything derived from
   * it is removed whether or not it is labelled more constrained -- which is what erasure means. A
   * value derived from two customers dies with either of them.
   */
  public SurrogateStoreConfig mayErase(
      java.util.function.BiPredicate<Label, AccessContext> mayErase) {
    this.mayErase = Objects.requireNonNull(mayErase, "an erasure policy must not be null");
    return this;
  }

  java.util.function.BiPredicate<Label, AccessContext> mayErase() {
    return mayErase;
  }

  /**
   * The axes this store was declared with.
   *
   * <p>Public because a durable store in another package has to write labels down, and named
   * distinctly from {@link #axes(Axis...)} because a no-argument call would otherwise resolve to
   * the varargs setter rather than to this.
   */
  public List<Axis<?>> declaredAxes() {
    if (axes.isEmpty()) {
      throw new IllegalStateException(
          "a store needs axes: call axes(...) with the questions it asks about every value");
    }
    return axes;
  }

  List<DestinationSpec> destinations() {
    return List.copyOf(destinations);
  }

  java.util.Set<String> sources() {
    return java.util.Set.copyOf(sources);
  }
}
