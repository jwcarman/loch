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

import java.util.List;
import java.util.Optional;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.UnaryOperator;
import org.jwcarman.codec.spi.TypeRef;

/**
 * A registered way to make one value out of several.
 *
 * <p>Summarising a conversation, averaging a column, reconciling a claim against a record, joining
 * two customers' data into a report. The same rules as a single-parent derivation, with one
 * consequence that only shows up here:
 *
 * <p><b>The output carries the join of every parent's label.</b> Combining a tenant's data with
 * another tenant's produces a value labelled for both, which in an exact-match dimension is a
 * conflict -- and a conflict is permitted by no destination. The merged value exists, keeps honest
 * lineage to both parents, and can never be dereferenced anywhere. That is the claim this library
 * makes about cross-tenant leakage, and it cannot be demonstrated without this.
 *
 * @param <A> the application's attribution type
 * @param <I> what it reads, of which there are several
 * @param <O> what it produces
 */
public interface Fold<A, I, O> {

  FoldId<I, O> id();

  TypeRef<I> inputType();

  TypeRef<O> outputType();

  /** Produces the new value, or declines. Receives plaintext, in the order it was given. */
  Optional<O> apply(List<I> inputs, AccessContext context);

  /** See {@link Derivation#deterministic()}. The content address covers all parents, in order. */
  boolean deterministic();

  int version();

  /** The most constrained parent this will accept. Checked against each. */
  default Optional<A> ceiling() {
    return Optional.empty();
  }

  /** See {@link Derivation#relabel()}. Checked against the join of every parent. */
  default Optional<UnaryOperator<A>> relabel() {
    return Optional.empty();
  }

  default boolean availableTo(AccessContext context) {
    return true;
  }

  default boolean privileged() {
    return relabel().isPresent();
  }

  /** Declares one. Always at wiring; never at a call site. */
  static <A, I, O> Builder<A, I, O> of(
      FoldId<I, O> id, Class<I> inputType, Class<O> outputType, Function<List<I>, O> function) {
    return of(id, TypeRef.of(inputType), TypeRef.of(outputType), function);
  }

  /** For a fold over or into a generic container. */
  static <A, I, O> Builder<A, I, O> of(
      FoldId<I, O> id, TypeRef<I> inputType, TypeRef<O> outputType, Function<List<I>, O> function) {
    return new Builder<>(
        id, inputType, outputType, (inputs, context) -> Optional.of(function.apply(inputs)));
  }

  /** Collects the optional parts. */
  final class Builder<A, I, O> {

    private final FoldId<I, O> id;
    private final TypeRef<I> inputType;
    private final TypeRef<O> outputType;
    private final Folding<I, O> function;
    private boolean deterministic = true;
    private int version = 1;
    private A ceiling;
    private UnaryOperator<A> relabel;
    private Predicate<AccessContext> availableTo = context -> true;

    private Builder(
        FoldId<I, O> id, TypeRef<I> inputType, TypeRef<O> outputType, Folding<I, O> function) {
      this.id = id;
      this.inputType = inputType;
      this.outputType = outputType;
      this.function = function;
    }

    public Builder<A, I, O> nondeterministic() {
      this.deterministic = false;
      return this;
    }

    public Builder<A, I, O> version(int version) {
      this.version = version;
      return this;
    }

    public Builder<A, I, O> accepting(A ceiling) {
      this.ceiling = ceiling;
      return this;
    }

    /** See {@link Derivations.Builder#lowering}. Checked against the join of every parent. */
    public Builder<A, I, O> lowering(UnaryOperator<A> relabel) {
      this.relabel = relabel;
      return this;
    }

    public Builder<A, I, O> availableTo(Predicate<AccessContext> availableTo) {
      this.availableTo = availableTo;
      return this;
    }

    public Fold<A, I, O> build() {
      A theCeiling = ceiling;
      UnaryOperator<A> theRelabel = relabel;
      Predicate<AccessContext> theAvailability = availableTo;
      boolean isDeterministic = deterministic;
      int theVersion = version;
      return new Fold<>() {
        @Override
        public FoldId<I, O> id() {
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
        public Optional<O> apply(List<I> inputs, AccessContext context) {
          return function.apply(inputs, context);
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
        public Optional<A> ceiling() {
          return Optional.ofNullable(theCeiling);
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

  /** A fold that may decline, and reads the access context. */
  @FunctionalInterface
  interface Folding<I, O> {
    Optional<O> apply(List<I> inputs, AccessContext context);
  }
}
