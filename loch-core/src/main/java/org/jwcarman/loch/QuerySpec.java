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
import java.util.function.Function;
import java.util.function.Predicate;

/** Everything the engine needs to answer one question. Package-private, like every other spec. */
record QuerySpec<A, I, Q>(
    String name,
    SurrogateType<I> inputType,
    Query.Asking<I, Q> asking,
    Function<AccessContext, A> ceiling,
    Predicate<AccessContext> availableTo) {

  Optional<A> ceilingFor(AccessContext context) {
    return Optional.ofNullable(ceiling).map(f -> f.apply(context));
  }
}
