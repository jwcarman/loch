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
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.UnaryOperator;
import org.jwcarman.loch.lattice.Ceiling;
import org.jwcarman.loch.lattice.Label;

/**
 * Everything the engine needs to run a derivation, flattened to one arity-blind shape.
 *
 * <p>Package-private, and deliberately so. Arity is a property of the capability the application
 * holds, not of the machinery: a {@link Derivation} guarantees one parent of the right type and a
 * {@link Fold} guarantees many of one type, so what arrives here is a list whose length and element
 * types were already settled by the compiler. That is why {@code function} can take {@code
 * List<Object>} without it being a hole.
 *
 * @param inputTypes one per parent, positionally; for a fold, exactly one, applying to all of them
 */
record DerivationSpec<O>(
    String name,
    List<SurrogateType<?>> inputTypes,
    SurrogateType<O> outputType,
    BiFunction<List<Object>, AccessContext, Optional<O>> function,
    Function<AccessContext, Ceiling> ceiling,
    UnaryOperator<Label> relabel,
    Predicate<AccessContext> availableTo,
    boolean fold) {

  /** Whether this weakens labels, which is what a manifest wants to list. */
  boolean privileged() {
    return relabel != null;
  }

  /** What the parent in this position must be. A fold applies its one type to every parent. */
  SurrogateType<?> typeAt(int position) {
    return fold ? inputTypes.getFirst() : inputTypes.get(position);
  }
}
