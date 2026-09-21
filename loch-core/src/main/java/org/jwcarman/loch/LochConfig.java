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
import java.util.Optional;
import java.util.function.Function;
import org.jwcarman.codec.spi.TypeRef;
import org.jwcarman.loch.lattice.Lattice;

/**
 * How a loch is built: the lattice its labels live in, and the destinations values may reach.
 *
 * <p>Both are wiring-time decisions on purpose. A lattice supplied later could reorder what is
 * permitted underneath values already stored, and a destination supplied at a call site would let
 * any code invent its own permission.
 */
public class LochConfig<A, D> {

  private Lattice<A> lattice;
  private boolean explainRefusals;
  private java.util.function.Supplier<AccessContext> ambient = AccessContext::empty;
  private java.util.Set<String> callerMayContribute = java.util.Set.of();
  private java.util.function.BiPredicate<A, AccessContext> mayErase = (label, context) -> false;
  private final List<DestinationSpec<A>> destinations = new ArrayList<>();
  private final List<DerivationSpec<A, ?>> derivations = new ArrayList<>();
  final List<QuerySpec<A, ?, ?>> queries = new ArrayList<>();
  private final java.util.Set<String> sources = new java.util.LinkedHashSet<>();
  private DefaultLoch<A> bound;
  private final List<Binding<A>> bindings = new ArrayList<>();

  /** The order over this application's labels. Required. */
  public LochConfig<A, D> lattice(Lattice<A> lattice) {
    this.lattice = Objects.requireNonNull(lattice, "a loch needs a lattice");
    return this;
  }

  /** Somewhere values may go. Registered once; referenced by name forever after. */
  public LochConfig<A, D> destination(DestinationSpec<A> destination) {
    destinations.add(Objects.requireNonNull(destination, "a destination must not be null"));
    return this;
  }

  // ------------------------------------------------------------------ minting capabilities

  /**
   * Mints the authority to put values into this loch at one label. Configuration time only.
   *
   * <p>Returns the {@link SurrogateSource} rather than this config, so the fluent chain stops here
   * and the caller has to capture what it was given. That is the point: there is no way to ask for
   * an source afterwards, so a capability nobody kept is a capability nobody has.
   *
   * <p>The function fixes the parts of the label that are properties of the door -- what this is,
   * how far it is trusted, what kind of data arrives here -- and may read the rest, typically a
   * tenant, from ambient context.
   */
  public <T extends D> SurrogateSource<T> source(
      String name, TypeRef<T> type, java.util.function.BiFunction<T, AccessContext, A> labelling) {
    Objects.requireNonNull(name, "a source needs a name");
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

  /** The same, for a type with no generic parameters of its own. */
  public <T extends D> SurrogateSource<T> source(
      String name, Class<T> type, java.util.function.BiFunction<T, AccessContext, A> labelling) {
    return source(name, TypeRef.of(type), labelling);
  }

  /** The same, for a generic container whose label does not depend on what is arriving. */
  public <T extends D> SurrogateSource<T> source(
      String name, TypeRef<T> type, java.util.function.Function<AccessContext, A> labelling) {
    return source(name, type, (value, context) -> labelling.apply(context));
  }

  /**
   * The same, for a label that does not depend on what is arriving.
   *
   * <p>The common case: a door knows what it is, so mail from customers is untrusted whatever it
   * says. Reach for the other form when the label is a property of the value -- a classification
   * marking inside a document, a sender the ingest verified, a scan that found card numbers.
   */
  public <T extends D> SurrogateSource<T> source(
      String name, Class<T> type, java.util.function.Function<AccessContext, A> labelling) {
    return source(name, TypeRef.of(type), (value, context) -> labelling.apply(context));
  }

  /** A source whose label depends on neither who is acting nor what is arriving. */
  public <T extends D> SurrogateSource<T> source(String name, Class<T> type, A label) {
    Objects.requireNonNull(label, "a source needs a label");
    return source(name, TypeRef.of(type), (value, context) -> label);
  }

  /**
   * Declares somewhere values may go, and what it will accept. Configuration time only.
   *
   * <p>The restriction lives here and is said once. Typed readers are minted from it with {@link
   * Destination#reading}, and a reader carries this ceiling rather than one of its own -- so a
   * reader is always strictly narrower than the destination that made it, never broader.
   *
   * <p><b>A destination is more authority than any reader it mints</b>, because it can mint a
   * reader for any type. It belongs beside the configuration that created it and is never handed to
   * a service; hand out the readers instead. It looks like an inert descriptor and is not one.
   */
  public Destination<A, D> destination(
      String name, java.util.function.Function<AccessContext, A> ceiling) {
    Objects.requireNonNull(name, "a destination needs a name");
    Objects.requireNonNull(ceiling, "a destination needs a ceiling");
    destination(Destinations.varying(name, ceiling));
    return new Destination<>(this, name);
  }

  /** A destination whose ceiling does not depend on who is asking. */
  public Destination<A, D> destination(String name, A ceiling) {
    Objects.requireNonNull(ceiling, "a destination needs a ceiling");
    return destination(name, context -> ceiling);
  }

  /**
   * Somewhere values may go, and the only thing that can mint a reader for it.
   *
   * <p>Minting a reader adds no authority: every reader enforces the ceiling declared when this was
   * created, evaluated against whoever is asking at the time of the read. What a reader adds is a
   * narrowing by type, so a service handed a {@code SurrogateSink<Card>} cannot read a {@code
   * Last4} even though this destination would accept one.
   */
  public static final class Destination<A, D> {

    private final LochConfig<A, D> config;
    private final String name;

    private Destination(LochConfig<A, D> config, String name) {
      this.config = config;
      this.name = name;
    }

    /** A reader for one type at this destination's ceiling. */
    public <T extends D> SurrogateSink<T> reading(Class<T> type) {
      return reading(TypeRef.of(type));
    }

    /** The same, for a generic container. */
    public <T extends D> SurrogateSink<T> reading(TypeRef<T> type) {
      Objects.requireNonNull(type, "a reader needs to say what comes out of it");
      String door = name;
      Binding<A> binding = config.binding("reader for '" + door + "'");
      return new SurrogateSink<>() {
        @Override
        public TypeRef<T> type() {
          return type;
        }

        @Override
        public Dereferenced<T> exchange(Surrogate<T> surrogate) {
          return exchange(surrogate, AccessContext.empty());
        }

        @Override
        public Dereferenced<T> exchange(Surrogate<T> surrogate, AccessContext context) {
          return binding.engine().dereference(surrogate, type, door, context);
        }

        @Override
        public String toString() {
          return "'" + door + "' reading " + type.getType().getTypeName();
        }
      };
    }
  }

  /**
   * A destination with exactly one reader, which is the common case.
   *
   * <p>Shorthand for declaring a destination and immediately reading one type at it. Reach for
   * {@link #destination} when the same door reads several types, so its ceiling is written once
   * rather than copied per type.
   */
  public <T extends D> SurrogateSink<T> sink(
      String name, TypeRef<T> type, java.util.function.Function<AccessContext, A> ceiling) {
    return destination(name, ceiling).reading(type);
  }

  /** The same, for a type with no generic parameters of its own. */
  public <T extends D> SurrogateSink<T> sink(
      String name, Class<T> type, java.util.function.Function<AccessContext, A> ceiling) {
    return sink(name, TypeRef.of(type), ceiling);
  }

  /** An sink whose ceiling does not depend on who is asking. */
  public <T extends D> SurrogateSink<T> sink(String name, Class<T> type, A ceiling) {
    Objects.requireNonNull(ceiling, "a sink needs a ceiling");
    return sink(name, TypeRef.of(type), context -> ceiling);
  }

  // ------------------------------------------------------------------ derivations and folds

  /**
   * Mints the authority to make one value from one other. Configuration time only.
   *
   * <p>Five arities, one per shape, because the JVM has no variadic generics and every library that
   * has faced this made the same choice: {@code kotlinx.coroutines} gives {@code Flow.combine}
   * overloads for two through five flows, as do RxJava and Reactor for {@code zip}. Beyond five, or
   * where the parents share a type, use {@link #fold}.
   */
  public <I extends D, O extends D> Minting<A, O, Derivation<I, O>> derivation(
      String name, Class<I> input, Class<O> output, Function<I, O> function) {
    return new Minting<>(
        this,
        name,
        List.of(TypeRef.of(input)),
        TypeRef.of(output),
        (values, context) -> Optional.ofNullable(function.apply(input.cast(values.getFirst()))),
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
  public <I extends D, O extends D> Minting<A, O, Derivation<I, O>> checking(
      String name,
      Class<I> input,
      Class<O> output,
      java.util.function.BiFunction<I, AccessContext, Optional<O>> function) {
    return new Minting<>(
        this,
        name,
        List.of(TypeRef.of(input)),
        TypeRef.of(output),
        (values, context) -> function.apply(input.cast(values.getFirst()), context),
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
  public <I extends D, O extends D> Minting<A, O, Fold<I, O>> fold(
      String name, Class<I> input, Class<O> output, Function<List<I>, O> function) {
    return new Minting<>(
        this,
        name,
        List.of(TypeRef.of(input)),
        TypeRef.of(output),
        (values, context) ->
            Optional.ofNullable(function.apply(values.stream().map(input::cast).toList())),
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

    private final LochConfig<A, ?> config;
    private final String name;
    private final List<TypeRef<?>> inputTypes;
    private final TypeRef<O> outputType;
    private final java.util.function.BiFunction<List<Object>, AccessContext, Optional<O>> function;
    private final boolean fold;
    private final java.util.function.BiFunction<DerivationSpec<A, O>, Binding<A>, C> capability;
    private java.util.function.Function<AccessContext, A> ceiling;
    private boolean anything;
    private java.util.function.UnaryOperator<A> relabel;
    private java.util.function.Predicate<AccessContext> availableTo = context -> true;

    private Minting(
        LochConfig<A, ?> config,
        String name,
        List<TypeRef<?>> inputTypes,
        TypeRef<O> outputType,
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
    bindings.forEach(binding -> binding.attach(loch));
  }

  /**
   * Mints the authority to ask one question of a value without the value leaving.
   *
   * <p>Boolean, and registered here rather than named at a call site, for the reasons set out on
   * {@link Query}.
   */
  /**
   * Mints the authority to ask one question of a value without the value leaving.
   *
   * <p>Boolean, and registered here rather than named at a call site, for the reasons set out on
   * {@link Query}.
   */
  public <I extends D, Q> Querying<A, I, Q> query(
      String name, Class<I> input, Class<Q> against, Query.Asking<I, Q> asking) {
    Objects.requireNonNull(name, "a query needs a name");
    Objects.requireNonNull(against, "a query needs to say what it is asked against");
    Objects.requireNonNull(asking, "a query needs something to ask");
    return new Querying<>(this, name, TypeRef.of(input), asking);
  }

  /** What a query still needs said about it before it becomes a capability. */
  public static final class Querying<A, I, Q> {

    private final LochConfig<A, ?> config;
    private final String name;
    private final TypeRef<I> inputType;
    private final Query.Asking<I, Q> asking;
    private java.util.function.Function<AccessContext, A> ceiling;
    private boolean anything;
    private java.util.function.Predicate<AccessContext> availableTo = context -> true;

    private Querying(
        LochConfig<A, ?> config, String name, TypeRef<I> inputType, Query.Asking<I, Q> asking) {
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
   * The loch a capability reaches through, attached when that loch is built.
   *
   * <p>Per capability, not per config, and that distinction is the whole safety property. If a
   * capability reached the loch through the config, one minted <i>after</i> the loch was built
   * would find it already there and work perfectly -- measured doing exactly that: an source minted
   * after startup planted a value at another tenant's label, and a derivation minted after startup
   * read a cardholder token. Binding each capability at construction means a late one is attached
   * to nothing, and says so.
   */
  static final class Binding<A> {

    private final String what;
    private DefaultLoch<A> loch;

    private Binding(String what) {
      this.what = what;
    }

    private void attach(DefaultLoch<A> loch) {
      this.loch = loch;
    }

    DefaultLoch<A> engine() {
      if (loch == null) {
        throw new IllegalStateException(
            what
                + " is attached to no loch. Capabilities are minted while a loch is being"
                + " configured and are attached when it is built; this one was minted afterwards,"
                + " so there is nothing for it to act on.");
      }
      return loch;
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
  public LochConfig<A, D> explainRefusals() {
    this.explainRefusals = true;
    return this;
  }

  boolean explainsRefusals() {
    return explainRefusals;
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
  public LochConfig<A, D> askingWhoIsAsking(java.util.function.Supplier<AccessContext> ambient) {
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
  public LochConfig<A, D> callerMayContribute(String... keys) {
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
  public LochConfig<A, D> mayErase(java.util.function.BiPredicate<A, AccessContext> mayErase) {
    this.mayErase = Objects.requireNonNull(mayErase, "an erasure policy must not be null");
    return this;
  }

  java.util.function.BiPredicate<A, AccessContext> mayErase() {
    return mayErase;
  }

  Lattice<A> lattice() {
    if (lattice == null) {
      throw new IllegalStateException(
          "a loch needs a lattice: call lattice(...) with the order over your label type");
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
