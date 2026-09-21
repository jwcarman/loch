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
import org.jwcarman.codec.spi.TypeRef;
import org.jwcarman.loch.lattice.Lattice;

/**
 * How a store is built: the lattice its labels live in, and the destinations values may reach.
 *
 * <p>Both are wiring-time decisions on purpose. A lattice supplied later could reorder what is
 * permitted underneath values already stored, and a destination supplied at a call site would let
 * any code invent its own permission.
 */
public class SurrogateStoreConfig<A, D> {

  private Lattice<A> lattice;
  private Class<A> labelType;
  private boolean explainRefusals;
  private AccessContextProvider ambient = AccessContextProvider.none();
  private java.util.Set<String> callerMayContribute = java.util.Set.of();
  private java.util.function.BiPredicate<A, AccessContext> mayErase = (label, context) -> false;
  private final List<DestinationSpec<A>> destinations = new ArrayList<>();
  private final List<DerivationSpec<A, ?>> derivations = new ArrayList<>();
  final List<QuerySpec<A, ?, ?>> queries = new ArrayList<>();
  private final java.util.Set<String> sources = new java.util.LinkedHashSet<>();
  private DefaultSurrogateStore<A> bound;
  private final List<Binding<A>> bindings = new ArrayList<>();
  private final java.util.Map<String, SurrogateType<?>> types = new LinkedHashMap<>();

  /**
   * The record this application's labels are, which a durable store has to serialise.
   *
   * <p>Here rather than with the storage settings because it is a property of the store and not of
   * where it is kept: a label goes to disk encrypted like any other value, whatever the disk is.
   */
  public SurrogateStoreConfig<A, D> labelType(Class<A> labelType) {
    this.labelType = Objects.requireNonNull(labelType, "a label type must not be null");
    return this;
  }

  /** What the labels are, for a backing store that has to write them down. */
  public Class<A> labelType() {
    if (labelType == null) {
      throw new IllegalStateException(
          "this store needs to know its label type: call labelType(...) with the record your"
              + " labels are, because labels are written down like any other value");
    }
    return labelType;
  }

  /** The order over this application's labels. Required. */
  public SurrogateStoreConfig<A, D> lattice(Lattice<A> lattice) {
    this.lattice = Objects.requireNonNull(lattice, "a store needs a lattice");
    return this;
  }

  /** Somewhere values may go. Registered once; referenced by name forever after. */
  public SurrogateStoreConfig<A, D> destination(DestinationSpec<A> destination) {
    destinations.add(Objects.requireNonNull(destination, "a destination must not be null"));
    return this;
  }

  // ------------------------------------------------------------------ the types it will keep

  /**
   * A type this store will keep, naming itself.
   *
   * <p>Shorthand for {@link SurrogateType#of(Class)}: its {@link SurrogateName} if it has one,
   * otherwise its kebab-cased simple name.
   */
  public <T extends D> SurrogateType<T> type(Class<T> type) {
    return registered(SurrogateType.of(type));
  }

  /** A type this store will keep, named explicitly. */
  public <T extends D> SurrogateType<T> type(String name, Class<T> type) {
    return type(name, TypeRef.of(type));
  }

  /**
   * A type this store will keep, named explicitly, for a generic container.
   *
   * <p>Containers have to be named here. Their raw type is not yours to annotate and would collide
   * with every other container over it.
   */
  public <T extends D> SurrogateType<T> type(String name, TypeRef<T> type) {
    return registered(new SurrogateType<>(name, type));
  }

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
  public <T extends D> SurrogateSource<T> source(String name, SurrogateType<T> type, A label) {
    Objects.requireNonNull(label, "a source needs a label");
    return source(name, type, (value, context) -> label);
  }

  /** The same, for a label that does not depend on what is arriving. */
  public <T extends D> SurrogateSource<T> source(
      String name, SurrogateType<T> type, java.util.function.Function<AccessContext, A> labelling) {
    return source(name, type, (value, context) -> labelling.apply(context));
  }

  public <T extends D> SurrogateSource<T> source(
      String name,
      SurrogateType<T> type,
      java.util.function.BiFunction<T, AccessContext, A> labelling) {
    Objects.requireNonNull(name, "a source needs a name");
    registered(type);
    Binding<A> binding = binding("source '" + name + "'");
    Objects.requireNonNull(type, "a source needs to know what it accepts");
    Objects.requireNonNull(labelling, "a source needs to say how it labels what arrives");
    if (!sources.add(name)) {
      throw new IllegalStateException("two sources are registered as '" + name + "'");
    }
    return new SurrogateSource<>() {
      @Override
      public Surrogate<T> exchange(T value) {
        return binding.engine().exchangeVia(name, type, labelling, value);
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
   * Begins declaring somewhere values may go.
   *
   * <p>Say the ceiling here and the types with {@link Declaring#type}. Both restrictions are
   * settled before the destination exists and neither can be widened afterwards, which is what
   * makes a reader a typed view rather than a grant.
   */
  public Declaring<A, D> destination(
      String name, java.util.function.Function<AccessContext, A> ceiling) {
    Objects.requireNonNull(name, "a destination needs a name");
    Objects.requireNonNull(ceiling, "a destination needs a ceiling");
    return new Declaring<>(this, name, ceiling);
  }

  /** A destination being declared: its ceiling is set, its types are being listed. */
  public static final class Declaring<A, D> {

    private final SurrogateStoreConfig<A, D> config;
    private final String name;
    private final java.util.function.Function<AccessContext, A> ceiling;
    private final java.util.Set<String> reads = new java.util.LinkedHashSet<>();

    private Declaring(
        SurrogateStoreConfig<A, D> config,
        String name,
        java.util.function.Function<AccessContext, A> ceiling) {
      this.config = config;
      this.name = name;
      this.ceiling = ceiling;
    }

    /** One more type this destination is allowed to hand over. */
    /** The same, for a type already declared, including a generic container. */
    public Declaring<A, D> type(SurrogateType<?> type) {
      Objects.requireNonNull(type, "a destination's type must not be null");
      config.registered(type);
      reads.add(type.name());
      return this;
    }

    /** Registers the destination and hands it back. */
    public Destination<A, D> mint() {
      if (reads.isEmpty()) {
        throw new IllegalStateException(
            "'"
                + name
                + "' has to say which types it reads. A destination that reads anything reads"
                + " everything its ceiling admits, including whatever gets stored at that label"
                + " next year.");
      }
      config.destination(Destinations.varying(name, ceiling));
      // Declaration order, not hash order: this list ends up in an error message somebody has to
      // read, and "[last4, card]" changing to "[card, last4]" between runs helps nobody.
      return new Destination<>(
          name,
          java.util.Collections.unmodifiableSet(new java.util.LinkedHashSet<>(reads)),
          config.binding("destination '" + name + "'"));
    }
  }

  /**
   * Somewhere values may go, and the readers for it.
   *
   * <p>Holds no configuration and cannot declare anything new. Everything it enforces was settled
   * when it was constructed, so a reader it mints is a typed view and not a grant: mint one where
   * you need it, keep it or drop it.
   */
  public static final class Destination<A, D> {

    private final String name;
    private final java.util.Set<String> reads;
    private final Binding<A> binding;

    private Destination(String name, java.util.Set<String> reads, Binding<A> binding) {
      this.name = name;
      this.reads = reads;
      this.binding = binding;
    }

    /** A reader for one of the types this destination was declared to read. */
    public <T extends D> SurrogateSink<T> reading(SurrogateType<T> type) {
      Objects.requireNonNull(type, "a reader needs to say what comes out of it");
      String wanted = type.name();
      if (!reads.contains(wanted)) {
        throw new IllegalStateException(
            ("'%s' does not read %s. It was declared to read %s, and a reader cannot add to"
                    + " that list.")
                .formatted(name, wanted, reads));
      }
      String door = name;
      Binding<A> bound = binding;
      return new SurrogateSink<>() {
        @Override
        public SurrogateType<T> type() {
          return type;
        }

        @Override
        public Dereferenced<T> exchange(Surrogate<T> surrogate) {
          return exchange(surrogate, AccessContext.empty());
        }

        @Override
        public Dereferenced<T> exchange(Surrogate<T> surrogate, AccessContext context) {
          return bound.engine().dereference(surrogate, type, door, context);
        }

        @Override
        public String toString() {
          return "'" + door + "' reading " + type.name();
        }
      };
    }
  }

  /** The same, for a type already declared. */
  public <T extends D> SurrogateSink<T> sink(
      String name, SurrogateType<T> type, java.util.function.Function<AccessContext, A> ceiling) {
    return destination(name, ceiling).type(type).mint().reading(type);
  }

  /** The same, for a type with no generic parameters of its own. */
  /** An sink whose ceiling does not depend on who is asking. */
  // ------------------------------------------------------------------ derivations and folds

  /**
   * Mints the authority to make one value from one other. Configuration time only.
   *
   * <p>Five arities, one per shape, because the JVM has no variadic generics and every library that
   * has faced this made the same choice: {@code kotlinx.coroutines} gives {@code Flow.combine}
   * overloads for two through five flows, as do RxJava and Reactor for {@code zip}. Beyond five, or
   * where the parents share a type, use {@link #fold}.
   */
  /** The same, for types already declared. */
  public <I extends D, O extends D> Minting<A, O, Derivation<I, O>> derivation(
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
                return derive(parent, AccessContext.empty());
              }

              @Override
              public Derived<O> derive(Surrogate<I> parent, AccessContext context) {
                return binding.engine().deriveVia(spec, List.of(parent), context);
              }
            });
  }

  /**
   * The same, for a derivation that may decline: a lookup that finds nothing, a check that fails.
   */
  /** The same, for types already declared. */
  public <I extends D, O extends D> Minting<A, O, Derivation<I, O>> checking(
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
                return derive(parent, AccessContext.empty());
              }

              @Override
              public Derived<O> derive(Surrogate<I> parent, AccessContext context) {
                return binding.engine().deriveVia(spec, List.of(parent), context);
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
  public <I extends D, O extends D> Minting<A, O, Fold<I, O>> fold(
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
                return fold(parents, AccessContext.empty());
              }

              @Override
              public Derived<O> fold(List<Surrogate<I>> parents, AccessContext context) {
                return binding.engine().deriveVia(spec, List.copyOf(parents), context);
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
  public static final class Minting<A, O, C> {

    private final SurrogateStoreConfig<A, ?> config;
    private final String name;
    private final List<SurrogateType<?>> inputTypes;
    private final SurrogateType<O> outputType;
    private final java.util.function.BiFunction<List<Object>, AccessContext, Optional<O>> function;
    private final boolean fold;
    private final java.util.function.BiFunction<DerivationSpec<A, O>, Binding<A>, C> capability;
    private java.util.function.Function<AccessContext, A> ceiling;
    private boolean anything;
    private java.util.function.UnaryOperator<A> relabel;
    private java.util.function.Predicate<AccessContext> availableTo = context -> true;

    private Minting(
        SurrogateStoreConfig<A, ?> config,
        String name,
        List<SurrogateType<?>> inputTypes,
        SurrogateType<O> outputType,
        java.util.function.BiFunction<List<Object>, AccessContext, Optional<O>> function,
        boolean fold,
        java.util.function.BiFunction<DerivationSpec<A, O>, Binding<A>, C> capability) {
      this.config = config;
      this.name = name;
      this.inputTypes = inputTypes;
      this.outputType = outputType;
      this.function = function;
      this.fold = fold;
      this.capability = capability;
    }

    /** The most constrained parent this will accept. */
    public Minting<A, O, C> accepting(A ceiling) {
      Objects.requireNonNull(ceiling, "a ceiling must not be null");
      return accepting(context -> ceiling);
    }

    /** A ceiling that depends on who is asking, which a tenant always does. */
    public Minting<A, O, C> accepting(java.util.function.Function<AccessContext, A> ceiling) {
      this.ceiling = Objects.requireNonNull(ceiling, "a ceiling must not be null");
      return this;
    }

    /**
     * Reads anything, at any label.
     *
     * <p>Required if no ceiling is set, for the same reason a question's is: this receives
     * plaintext, so breadth has to be said out loud rather than fallen into.
     */
    public Minting<A, O, C> acceptingAnything() {
      this.anything = true;
      return this;
    }

    /** Declares that the result is less constrained than its parents, and by how much. */
    public Minting<A, O, C> lowering(java.util.function.UnaryOperator<A> relabel) {
      this.relabel = Objects.requireNonNull(relabel, "a lowering must not be null");
      return this;
    }

    /** Whether this is offered at all, given who is asking. */
    public Minting<A, O, C> availableTo(java.util.function.Predicate<AccessContext> availableTo) {
      this.availableTo = Objects.requireNonNull(availableTo, "an availability must not be null");
      return this;
    }

    /** Registers it and hands back the capability. Nothing can obtain one any other way. */
    public C mint() {
      if (ceiling == null && !anything) {
        throw new IllegalStateException(
            "'"
                + name
                + "' reads plaintext, so it needs a ceiling: call accepting(...) with what it may"
                + " look at, or acceptingAnything() if it really may look at everything");
      }
      DerivationSpec<A, O> spec =
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
  void bind(DefaultSurrogateStore<A> store) {
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
  public <I extends D, Q> Querying<A, I, Q> query(
      String name, SurrogateType<I> input, Class<Q> against, Query.Asking<I, Q> asking) {
    Objects.requireNonNull(name, "a query needs a name");
    Objects.requireNonNull(against, "a query needs to say what it is asked against");
    Objects.requireNonNull(asking, "a query needs something to ask");
    return new Querying<>(this, name, registered(input), asking);
  }

  /** What a query still needs said about it before it becomes a capability. */
  public static final class Querying<A, I, Q> {

    private final SurrogateStoreConfig<A, ?> config;
    private final String name;
    private final SurrogateType<I> inputType;
    private final Query.Asking<I, Q> asking;
    private java.util.function.Function<AccessContext, A> ceiling;
    private boolean anything;
    private java.util.function.Predicate<AccessContext> availableTo = context -> true;

    private Querying(
        SurrogateStoreConfig<A, ?> config,
        String name,
        SurrogateType<I> inputType,
        Query.Asking<I, Q> asking) {
      this.config = config;
      this.name = name;
      this.inputType = inputType;
      this.asking = asking;
    }

    /** The most constrained value this may be asked about. */
    public Querying<A, I, Q> accepting(java.util.function.Function<AccessContext, A> ceiling) {
      this.ceiling = Objects.requireNonNull(ceiling, "a ceiling must not be null");
      return this;
    }

    /** Accepts the same thing regardless of who is asking. */
    public Querying<A, I, Q> accepting(A ceiling) {
      Objects.requireNonNull(ceiling, "a ceiling must not be null");
      return accepting(context -> ceiling);
    }

    /**
     * Reads anything, at any label.
     *
     * <p>Required if no ceiling is set. This reads plaintext to answer, so breadth has to be said
     * out loud rather than fallen into.
     */
    public Querying<A, I, Q> acceptingAnything() {
      this.anything = true;
      return this;
    }

    /** Whether this is offered at all, given who is asking. */
    public Querying<A, I, Q> availableTo(java.util.function.Predicate<AccessContext> availableTo) {
      this.availableTo = Objects.requireNonNull(availableTo, "an availability must not be null");
      return this;
    }

    /** Registers it and hands back the capability. Nothing can obtain one any other way. */
    public Query<I, Q> mint() {
      if (ceiling == null && !anything) {
        throw new IllegalStateException(
            "'"
                + name
                + "' reads plaintext to answer, so it needs a ceiling: call accepting(...) with"
                + " what it may look at, or acceptingAnything() if it really may look at"
                + " everything");
      }
      QuerySpec<A, I, Q> spec = new QuerySpec<>(name, inputType, asking, ceiling, availableTo);
      config.queries.add(spec);
      Binding<A> binding = config.binding("query '" + name + "'");
      return new Query<>() {
        @Override
        public Answer ask(Surrogate<I> about, Q against) {
          return ask(about, against, AccessContext.empty());
        }

        @Override
        public Answer ask(Surrogate<I> about, Q against, AccessContext context) {
          return binding.engine().askVia(spec, about, against, context);
        }
      };
    }
  }

  List<QuerySpec<A, ?, ?>> queries() {
    return List.copyOf(queries);
  }

  /**
   * The store a capability reaches through, attached when that store is built.
   *
   * <p>Per capability, not per config, and that distinction is the whole safety property. If a
   * capability reached the store through the config, one minted <i>after</i> the store was built
   * would find it already there and work perfectly -- measured doing exactly that: an source minted
   * after startup planted a value at another tenant's label, and a derivation minted after startup
   * read a cardholder token. Binding each capability at construction means a late one is attached
   * to nothing, and says so.
   */
  static final class Binding<A> {

    private final String what;
    private DefaultSurrogateStore<A> store;

    private Binding(String what) {
      this.what = what;
    }

    private void attach(DefaultSurrogateStore<A> store) {
      this.store = store;
    }

    DefaultSurrogateStore<A> engine() {
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

  private Binding<A> binding(String what) {
    Binding<A> binding = new Binding<>(what);
    bindings.add(binding);
    return binding;
  }

  List<DerivationSpec<A, ?>> derivations() {
    return List.copyOf(derivations);
  }

  /**
   * Includes labels and ceilings in refusal messages.
   *
   * <p>Off by default, because a label can itself be sensitive -- a tenant's name in a refusal
   * shown to a different tenant is a leak, and refusal text has a way of reaching places the value
   * never would. On for development, where the alternative is guessing.
   */
  public SurrogateStoreConfig<A, D> explainRefusals() {
    this.explainRefusals = true;
    return this;
  }

  boolean explainsRefusals() {
    return explainRefusals;
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
  public SurrogateStoreConfig<A, D> currentAccess(AccessContextProvider ambient) {
    this.ambient = Objects.requireNonNull(ambient, "an access source must not be null");
    return this;
  }

  /**
   * The context keys a call site may contribute, on top of what the edge established.
   *
   * <p>Empty by default, deliberately. Anything a caller says about who it is would otherwise be
   * taken at its word, and code holding a store could name itself whichever tenant or role it
   * pleased. Identity comes from {@link #currentAccess}; a caller contributes only what the edge
   * could not know, such as the purpose of an operation.
   *
   * <p>Never list an identity key here.
   */
  public SurrogateStoreConfig<A, D> callerMayContribute(String... keys) {
    this.callerMayContribute = java.util.Set.of(keys);
    return this;
  }

  java.util.Set<String> callerMayContribute() {
    return callerMayContribute;
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
  public SurrogateStoreConfig<A, D> mayErase(
      java.util.function.BiPredicate<A, AccessContext> mayErase) {
    this.mayErase = Objects.requireNonNull(mayErase, "an erasure policy must not be null");
    return this;
  }

  java.util.function.BiPredicate<A, AccessContext> mayErase() {
    return mayErase;
  }

  Lattice<A> lattice() {
    if (lattice == null) {
      throw new IllegalStateException(
          "a store needs a lattice: call lattice(...) with the order over your label type");
    }
    return lattice;
  }

  List<DestinationSpec<A>> destinations() {
    return List.copyOf(destinations);
  }

  java.util.Set<String> sources() {
    return java.util.Set.copyOf(sources);
  }
}
