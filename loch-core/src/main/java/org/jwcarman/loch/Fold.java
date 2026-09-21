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

/**
 * The authority to make one new held value from any number of existing ones, all of the same type.
 *
 * <p>The counterpart to the fixed arities. {@link Derivation2} through {@link Derivation5} cover
 * parents whose types differ and whose number is known when the code is written; a fold covers the
 * other case, where the type is one and the number is whatever arrived. Summarising a mail thread,
 * totalling a set of invoices, folding retrieved passages into one context.
 *
 * <p>This split is not invented here. {@code kotlinx.coroutines} gives {@code Flow.combine}
 * heterogeneous overloads for two through five flows and a {@code vararg} overload for many of one
 * type; RxJava's {@code zip} and Reactor's {@code zip} do the same. Every one of them stops at a
 * fixed arity for the same reason this does: the JVM has no variadic generics.
 *
 * <p>Reading many values needs no equivalent, and deliberately has none. In a semilattice {@code a
 * ⊔ b ⊑ c} holds exactly when {@code a ⊑ c} and {@code b ⊑ c}, so five values that each passed an
 * outlet's ceiling are proof the combination passes. A fold exists only because a <i>derived</i>
 * value's joined label has to be stored and carried forward.
 */
public interface Fold<I, O> {

  /** What this is called in the manifest and in the record. */
  DerivationId<?, ?> id();

  /**
   * Folds the values into a new one, or refuses.
   *
   * <p>An empty list is a refusal rather than a thrown error: nothing arrived is data, not a
   * mistake in the caller.
   */
  Derived<O> fold(List<Handle<I>> parents);

  /** The same, with attributes the caller is contributing to the decision. */
  Derived<O> fold(List<Handle<I>> parents, AccessContext context);
}
