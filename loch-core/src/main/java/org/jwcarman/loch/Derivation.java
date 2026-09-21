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
import java.util.function.UnaryOperator;
import org.jwcarman.codec.spi.TypeRef;

/**
 * A registered way to make one value out of one or more others.
 *
 * <p><b>The output carries the join of every parent's label.</b> With one parent that is the
 * parent's label unchanged; with several it is the most constrained of them, which is what makes
 * combining two tenants' data produce something no destination will accept.
 *
 * <p>Declared at wiring, referenced forever after by {@link DerivationId}. The function runs with
 * the parent's plaintext, so the whole point of registering is that the set of code which ever sees
 * a value is enumerable and reviewable.
 *
 * @param <A> the application's label type
 * @param <I> what it reads
 * @param <O> what it produces
 */
public interface Derivation<A, I, O> {

  DerivationId<I, O> id();

  TypeRef<I> inputType();

  TypeRef<O> outputType();

  /**
   * Produces the new value, or declines. Receives plaintext, in the order it was given.
   *
   * <p>A list whatever the arity, because one parent and several are the same operation with the
   * same ceiling, the same lowering, the same lineage and the same audit line. They were two types
   * once, and the second one was quietly left out of the manifest for exactly as long as nobody
   * looked.
   */
  Optional<O> apply(List<I> inputs, AccessContext context);

  /**
   * Whether this reads several values at once, or exactly one.
   *
   * <p>Declared rather than discovered, so the engine can turn away a call of the wrong arity
   * before it decodes anybody's plaintext. Finding out inside {@link #apply} is too late twice
   * over: the values have already been read, and a function that blew up on the data is by then
   * indistinguishable from a caller that passed the wrong number of handles.
   */
  default boolean readsMany() {
    return false;
  }

  /**
   * The most constrained parent this will accept, if it is choosy.
   *
   * <p>A derivation receives plaintext, so it is a destination. Empty means it accepts whatever the
   * loch will give it, which is the usual answer for a projection.
   */
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

  /**
   * How the output is labelled, when this derivation is permitted to weaken it.
   *
   * <p>Empty for an ordinary derivation, whose output is simply the join of its parents and
   * therefore never weaker. Present for a privileged one: endorsement and declassification are the
   * same move -- down the lattice -- and differ only in which part of a label they touch.
   *
   * <p>Loch checks the result is genuinely below the join. It cannot check that <i>only</i> the
   * intended part moved, because the label type is the application's and Loch cannot see inside it.
   * Writing {@code joined -> joined.withIntegrity(ENDORSED)} is what keeps the rest still, and that
   * line is the thing a reviewer reads.
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
