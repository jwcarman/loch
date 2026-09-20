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

import java.util.Optional;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.UnaryOperator;
import org.jwcarman.codec.spi.TypeRef;

/** Declares a {@link Derivation}. Always at wiring; never at a call site. */
public final class Derivations {

  private Derivations() {}

  /**
   * A derivation whose output is the join of its parents: it cannot weaken anything.
   *
   * <p>The overwhelming majority. Pulling a field out of a record, formatting, normalising. Safe by
   * construction, so it needs no ceremony and no evidence.
   *
   * @param deterministic true when the same input always gives the same output, which makes the
   *     derived handle content-addressed and therefore replay-safe
   */
  public static <A, I, O> Builder<A, I, O> of(
      DerivationId<I, O> id, Class<I> inputType, Class<O> outputType, Function<I, O> function) {
    return of(id, TypeRef.of(inputType), TypeRef.of(outputType), function);
  }

  /** For a derivation over or into a generic container. */
  public static <A, I, O> Builder<A, I, O> of(
      DerivationId<I, O> id, TypeRef<I> inputType, TypeRef<O> outputType, Function<I, O> function) {
    return new Builder<>(
        id, inputType, outputType, (input, context) -> Optional.of(function.apply(input)));
  }

  /** A derivation that may decline -- a lookup that finds nothing, a check that fails. */
  public static <A, I, O> Builder<A, I, O> checking(
      DerivationId<I, O> id,
      Class<I> inputType,
      Class<O> outputType,
      BiFunction<I, AccessContext, Optional<O>> function) {
    return new Builder<>(id, TypeRef.of(inputType), TypeRef.of(outputType), function);
  }

  /** Collects the optional parts. Call {@link Builder#build()} last. */
  public static final class Builder<A, I, O> {

    private final DerivationId<I, O> id;
    private final TypeRef<I> inputType;
    private final TypeRef<O> outputType;
    private final BiFunction<I, AccessContext, Optional<O>> function;
    private boolean deterministic = true;
    private int version = 1;
    private java.util.function.Function<AccessContext, A> ceiling;
    private UnaryOperator<A> relabel;
    private Predicate<AccessContext> availableTo = context -> true;

    private Builder(
        DerivationId<I, O> id,
        TypeRef<I> inputType,
        TypeRef<O> outputType,
        BiFunction<I, AccessContext, Optional<O>> function) {
      this.id = id;
      this.inputType = inputType;
      this.outputType = outputType;
      this.function = function;
    }

    /**
     * Says this cannot be reproduced -- a model call, a lookup, anything reading the world.
     *
     * <p>Such a derivation gets a fresh handle each run rather than a content-addressed one, and is
     * therefore not replay-safe.
     */
    public Builder<A, I, O> nondeterministic() {
      this.deterministic = false;
      return this;
    }

    /** Bump when the implementation changes, so old handles are not silently reinterpreted. */
    public Builder<A, I, O> version(int version) {
      this.version = version;
      return this;
    }

    /** The most constrained parent this will accept. */
    /** Accepts the same thing regardless of who is asking. */
    public Builder<A, I, O> accepting(A ceiling) {
      return accepting(context -> ceiling);
    }

    /** Accepts something that depends on who is asking -- a tenant, usually. */
    public Builder<A, I, O> accepting(java.util.function.Function<AccessContext, A> ceiling) {
      this.ceiling = ceiling;
      return this;
    }

    /**
     * Makes this privileged: it may label its output below the join of its parents.
     *
     * <p>Endorsement and declassification are both this. Write it as a change to one part of the
     * label -- {@code joined -> joined.withIntegrity(ENDORSED)} -- because Loch can check that the
     * result is lower than the join but cannot check that nothing else moved.
     */
    public Builder<A, I, O> lowering(UnaryOperator<A> relabel) {
      this.relabel = relabel;
      return this;
    }

    /** Restricts where this is offered, using whatever the application puts in the context. */
    public Builder<A, I, O> availableTo(Predicate<AccessContext> availableTo) {
      this.availableTo = availableTo;
      return this;
    }

    public Derivation<A, I, O> build() {
      java.util.function.Function<AccessContext, A> theCeiling = ceiling;
      UnaryOperator<A> theRelabel = relabel;
      Predicate<AccessContext> theAvailability = availableTo;
      boolean isDeterministic = deterministic;
      int theVersion = version;
      return new Derivation<>() {
        @Override
        public DerivationId<I, O> id() {
          return id;
        }

        @Override
        public TypeRef<I> inputType() {
          return inputType;
        }

        @Override
        public TypeRef<O> outputType() {
          return outputType;
        }

        @Override
        public Optional<O> apply(I input, AccessContext context) {
          return function.apply(input, context);
        }

        @Override
        public boolean deterministic() {
          return isDeterministic;
        }

        @Override
        public int version() {
          return theVersion;
        }

        @Override
        public Optional<A> ceiling(AccessContext context) {
          return Optional.ofNullable(theCeiling).map(f -> f.apply(context));
        }

        @Override
        public Optional<UnaryOperator<A>> relabel() {
          return Optional.ofNullable(theRelabel);
        }

        @Override
        public boolean availableTo(AccessContext context) {
          return theAvailability.test(context);
        }
      };
    }
  }
}
