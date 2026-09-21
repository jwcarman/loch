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
 * <p>This is a <b>predicate</b> in the sense the zero-knowledge and selective-disclosure literature
 * means: something proved about protected data without the data being disclosed. That is the
 * accurate word, and it is taken -- {@code java.util.function.Predicate} is already used in this
 * package -- so the type says "question" and the javadoc says what it is.
 *
 * <p><b>The mitigation for the one gap the architecture cannot close.</b> Once a value has been
 * dereferenced, the code holding it can do anything and Loch cannot follow. A question runs inside
 * the store, sees the plaintext, and returns a boolean -- so the caller learns the one bit it
 * needed and the value never enters code that could keep it.
 *
 * <pre>{@code
 * // hands plaintext to code Loch can no longer follow
 * Account account = loch.dereference(handle, SOMEWHERE).orThrow();
 * boolean ok = account.email().equals(sender);
 *
 * // the same answer, and the account never leaves
 * boolean ok = loch.ask(handle, OWNED_BY, sender).isTrue();
 * }</pre>
 *
 * <p>The oldest form of this is a key you may use but not read: PKCS#11's non-extractable keys, and
 * every HSM since. A password hash is the everyday one -- {@code checkpw(candidate, hash)} answers
 * yes or no and there is no {@code read()}.
 *
 * <p><b>Why the answer must be a boolean.</b> Not because small is tidy. Everything else that comes
 * out of a held value is governed: a derivation produces a labelled value, a dereference is checked
 * against a destination. An answer is neither -- it escapes unlabelled and ungated, straight to the
 * caller. So the rule is that <i>anything escaping unlabelled must be too small to be the
 * value</i>, and anything richer becomes a derived value instead, where it gets a label and a gate.
 * Wanting four digits is a derivation; wanting to know whether an account belongs to the sender is
 * this.
 *
 * <p><b>And why a bounded answer is still not free.</b> One bit per call is small; a thousand calls
 * is not. That is the tracker attack, studied in statistical databases long before anyone needed it
 * here -- Denning, Denning and Schwartz (1979), and Denning and Schlörer on inference controls
 * (1983) -- where a sequence of individually harmless aggregate queries reconstructs the record. A
 * ceiling and an audit line per call are this library's inference control, and that is what they
 * should be called.
 *
 * <p>A question reads plaintext in order to answer, so it is a destination like any other and
 * passes the same gate.
 *
 * @param <A> the application's label type
 * @param <I> the kind of value this asks about
 * @param <Q> what the caller supplies to ask it
 */
public interface Question<A, I, Q> {

  QuestionId<I, Q> id();

  TypeRef<I> inputType();

  /** Answers, seeing the plaintext. Must not retain it. */
  boolean test(I value, Q question, AccessContext context);

  /** The most constrained value this will look at. Empty accepts whatever the loch will give it. */
  /**
   * The most constrained value this will look at, for this particular access.
   *
   * <p>Takes the context for the same reason a destination's ceiling does: an exact-match dimension
   * such as a tenant cannot be held constant. There is no fixed ceiling meaning "any one tenant but
   * not a mixture", so the tenant has to come from whoever is asking.
   */
  default Optional<A> ceiling(AccessContext context) {
    return Optional.empty();
  }

  /** Whether this is offered at all, given who is asking. */
  default boolean availableTo(AccessContext context) {
    return true;
  }

  /** Declares one. Always at wiring; never at a call site. */
  static <A, I, Q> Builder<A, I, Q> of(
      QuestionId<I, Q> id, Class<I> inputType, BiPredicate<I, Q> test) {
    return of(id, TypeRef.of(inputType), test);
  }

  /** For a question about a generic container. */
  static <A, I, Q> Builder<A, I, Q> of(
      QuestionId<I, Q> id, TypeRef<I> inputType, BiPredicate<I, Q> test) {
    return new Builder<>(id, inputType, (value, question, context) -> test.test(value, question));
  }

  /** Declares one that also reads the access context. */
  static <A, I, Q> Builder<A, I, Q> of(QuestionId<I, Q> id, Class<I> inputType, Asking<I, Q> test) {
    return of(id, TypeRef.of(inputType), test);
  }

  /** One that reads the access context, about a generic container. */
  static <A, I, Q> Builder<A, I, Q> of(
      QuestionId<I, Q> id, TypeRef<I> inputType, Asking<I, Q> test) {
    return new Builder<>(id, inputType, test);
  }

  /** A question that also reads the access context. */
  @FunctionalInterface
  interface Asking<I, Q> {
    boolean test(I value, Q question, AccessContext context);
  }

  /** Collects the optional parts. */
  final class Builder<A, I, Q> {

    private final QuestionId<I, Q> id;
    private final TypeRef<I> inputType;
    private final Asking<I, Q> test;
    private java.util.function.Function<AccessContext, A> ceiling;
    private boolean anything;
    private java.util.function.Predicate<AccessContext> availableTo = context -> true;

    private Builder(QuestionId<I, Q> id, TypeRef<I> inputType, Asking<I, Q> test) {
      this.id = id;
      this.inputType = inputType;
      this.test = test;
    }

    /** Accepts the same thing regardless of who is asking. */
    public Builder<A, I, Q> accepting(A ceiling) {
      return accepting(context -> ceiling);
    }

    /**
     * Accepts anything the loch will give it, in writing.
     *
     * <p>Required if no ceiling is set, for the same reason the auditor is: this reads plaintext,
     * so it is a destination, and a destination that accepts everything from everyone should be a
     * sentence somebody wrote rather than the consequence of not typing one. An exact-match
     * dimension such as a tenant is the case that bites -- without a ceiling this reads any
     * tenant's data on any caller's behalf, and the filtering has to be written by hand inside the
     * function, which is the thing this library exists to stop.
     */
    public Builder<A, I, Q> acceptingAnything() {
      this.anything = true;
      return this;
    }

    /** Accepts something that depends on who is asking -- a tenant, usually. */
    public Builder<A, I, Q> accepting(java.util.function.Function<AccessContext, A> ceiling) {
      this.ceiling = ceiling;
      return this;
    }

    public Builder<A, I, Q> availableTo(java.util.function.Predicate<AccessContext> availableTo) {
      this.availableTo = availableTo;
      return this;
    }

    public Question<A, I, Q> build() {
      if (ceiling == null && !anything) {
        throw new IllegalStateException(
            "'"
                + id
                + "' reads plaintext, so it needs a ceiling: call accepting(...) with what it may"
                + " look at, or acceptingAnything() if it really may look at everything");
      }
      java.util.function.Function<AccessContext, A> theCeiling = ceiling;
      java.util.function.Predicate<AccessContext> theAvailability = availableTo;
      return new Question<>() {
        @Override
        public QuestionId<I, Q> id() {
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
        public Optional<A> ceiling(AccessContext context) {
          return Optional.ofNullable(theCeiling).map(f -> f.apply(context));
        }

        @Override
        public boolean availableTo(AccessContext context) {
          return theAvailability.test(context);
        }
      };
    }
  }
}
