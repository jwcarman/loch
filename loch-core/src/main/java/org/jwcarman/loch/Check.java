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
import java.util.function.BiPredicate;
import org.jwcarman.codec.spi.TypeRef;

/**
 * A question about a held value, answered without surrendering it.
 *
 * <p><b>This is the mitigation for the one gap the architecture cannot close.</b> Once a value has
 * been dereferenced, the code holding it can do anything with it and Loch cannot follow. A check
 * runs inside the store, sees the plaintext, and returns a boolean -- so the caller learns the one
 * bit it needed and the value never enters code that could keep it.
 *
 * <pre>{@code
 * // hands plaintext to code Loch can no longer follow
 * Account account = loch.dereference(handle, SOMEWHERE).orThrow();
 * boolean ok = account.email().equals(sender);
 *
 * // the same answer, and the account never leaves
 * boolean ok = loch.check(handle, OWNED_BY, sender).isTrue();
 * }</pre>
 *
 * <p>The design target is that most callers never dereference anything.
 *
 * <p>A check reads plaintext in order to answer, so it is a destination like any other and passes
 * the same gate. It also leaks exactly one bit per call by construction, which is the honest reason
 * a ceiling still applies: a thousand checks against a thousand guesses is a thousand bits.
 *
 * @param <A> the application's attribution type
 * @param <I> the kind of value this asks about
 * @param <Q> what the caller supplies to ask it
 */
public interface Check<A, I, Q> {

  CheckId<I, Q> id();

  TypeRef<I> inputType();

  /** Answers, seeing the plaintext. Must not retain it. */
  boolean test(I value, Q question, AccessContext context);

  /** The most constrained value this will look at. Empty accepts whatever the loch will give it. */
  default Optional<A> ceiling() {
    return Optional.empty();
  }

  /** Whether this is offered at all, given who is asking. */
  default boolean availableTo(AccessContext context) {
    return true;
  }

  /** Declares one. Always at wiring; never at a call site. */
  static <A, I, Q> Builder<A, I, Q> of(
      CheckId<I, Q> id, Class<I> inputType, BiPredicate<I, Q> test) {
    return of(id, TypeRef.of(inputType), test);
  }

  /** For a question about a generic container. */
  static <A, I, Q> Builder<A, I, Q> of(
      CheckId<I, Q> id, TypeRef<I> inputType, BiPredicate<I, Q> test) {
    return new Builder<>(id, inputType, (value, question, context) -> test.test(value, question));
  }

  /** Declares one that also reads the access context. */
  static <A, I, Q> Builder<A, I, Q> of(CheckId<I, Q> id, Class<I> inputType, Asking<I, Q> test) {
    return new Builder<>(id, TypeRef.of(inputType), test);
  }

  /** A question that also reads the access context. */
  @FunctionalInterface
  interface Asking<I, Q> {
    boolean test(I value, Q question, AccessContext context);
  }

  /** Collects the optional parts. */
  final class Builder<A, I, Q> {

    private final CheckId<I, Q> id;
    private final TypeRef<I> inputType;
    private final Asking<I, Q> test;
    private A ceiling;
    private java.util.function.Predicate<AccessContext> availableTo = context -> true;

    private Builder(CheckId<I, Q> id, TypeRef<I> inputType, Asking<I, Q> test) {
      this.id = id;
      this.inputType = inputType;
      this.test = test;
    }

    public Builder<A, I, Q> accepting(A ceiling) {
      this.ceiling = ceiling;
      return this;
    }

    public Builder<A, I, Q> availableTo(java.util.function.Predicate<AccessContext> availableTo) {
      this.availableTo = availableTo;
      return this;
    }

    public Check<A, I, Q> build() {
      A theCeiling = ceiling;
      java.util.function.Predicate<AccessContext> theAvailability = availableTo;
      return new Check<>() {
        @Override
        public CheckId<I, Q> id() {
          return id;
        }

        @Override
        public TypeRef<I> inputType() {
          return inputType;
        }

        @Override
        public boolean test(I value, Q question, AccessContext context) {
          return test.test(value, question, context);
        }

        @Override
        public Optional<A> ceiling() {
          return Optional.ofNullable(theCeiling);
        }

        @Override
        public boolean availableTo(AccessContext context) {
          return theAvailability.test(context);
        }
      };
    }
  }
}
