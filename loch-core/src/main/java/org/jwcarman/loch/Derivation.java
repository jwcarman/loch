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
import java.util.function.UnaryOperator;

/**
 * A registered way to make one value from another.
 *
 * <p>Declared at wiring, referenced forever after by {@link DerivationId}. The function runs with
 * the parent's plaintext, so the whole point of registering is that the set of code which ever sees
 * a value is enumerable and reviewable.
 *
 * @param <A> the application's attribution type
 * @param <I> what it reads
 * @param <O> what it produces
 */
public interface Derivation<A, I, O> {

  DerivationId<I, O> id();

  Class<I> inputType();

  Class<O> outputType();

  /** Produces the new value, or declines. Receives plaintext. */
  Optional<O> apply(I input, AccessContext context);

  /**
   * Whether the same inputs always give the same output.
   *
   * <p>This decides how the derived value is named, and it matters more than it looks. A
   * deterministic derivation is content-addressed -- its id is a hash of its parents, its name and
   * its version -- so running it twice yields the same handle. Anything that replays work (an agent
   * re-running a turn, a retried job) depends on that: a fresh id each time would leave the first
   * run's handle referring to a value nothing else agrees with.
   *
   * <p>A model call or a database lookup is not reproducible, so it gets a fresh id and is simply
   * not replay-safe, which is a fact about the world rather than something to paper over.
   */
  boolean deterministic();

  /**
   * Bumped when the implementation changes.
   *
   * <p>Part of the content address, so changing what a derivation does gives new handles rather
   * than silently reinterpreting values already derived under the old behaviour.
   */
  int version();

  /**
   * The most constrained parent this will accept, if it is choosy.
   *
   * <p>A derivation receives plaintext, so it is a destination. Empty means it accepts whatever the
   * loch will give it, which is the usual answer for a projection.
   */
  default Optional<A> ceiling() {
    return Optional.empty();
  }

  /**
   * How the output is labelled, when this derivation is permitted to weaken it.
   *
   * <p>Empty for an ordinary derivation, whose output is simply the join of its parents and
   * therefore never weaker. Present for a privileged one: endorsement and declassification are the
   * same move -- down the lattice -- and differ only in which part of a label they touch.
   *
   * <p>Loch checks the result is genuinely below the join. It cannot check that <i>only</i> the
   * intended part moved, because the attribution type is the application's and Loch cannot see
   * inside it. Writing {@code joined -> joined.withIntegrity(ENDORSED)} is what keeps the rest
   * still, and that line is the thing a reviewer reads.
   */
  default Optional<UnaryOperator<A>> relabel() {
    return Optional.empty();
  }

  /** Whether this is offered at all, given who is asking. */
  default boolean availableTo(AccessContext context) {
    return true;
  }

  /** Whether this weakens labels, which is what a manifest wants to list. */
  default boolean privileged() {
    return relabel().isPresent();
  }
}
