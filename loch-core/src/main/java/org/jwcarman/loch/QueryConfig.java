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

import java.util.Objects;
import java.util.function.Function;
import java.util.function.Predicate;
import org.jwcarman.loch.lattice.Ceiling;

/**
 * What a question needs said about it beyond what it asks.
 *
 * <p>Handed to a customizer while the derivation is being declared, and useless afterwards. It
 * carries no type parameters because nothing here depends on them: what a question may read and
 * where it is offered are about labels and contexts, not about what it asks or what it asks about.
 */
public final class QueryConfig {

  private Function<AccessContext, Ceiling> ceiling;
  private Predicate<AccessContext> availableTo = context -> true;

  QueryConfig() {}

  /** The most a parent may be labelled and still be read here. */
  public QueryConfig accepting(Ceiling ceiling) {
    Objects.requireNonNull(ceiling, "a ceiling must not be null");
    return accepting(context -> ceiling);
  }

  /** A ceiling that depends on who is asking, which a tenant always does. */
  public QueryConfig accepting(Function<AccessContext, Ceiling> ceiling) {
    this.ceiling = Objects.requireNonNull(ceiling, "a ceiling must not be null");
    return this;
  }

  /** Whether this is offered at all, given who is asking. */
  public QueryConfig availableTo(Predicate<AccessContext> availableTo) {
    this.availableTo = Objects.requireNonNull(availableTo, "an availability must not be null");
    return this;
  }

  Function<AccessContext, Ceiling> ceiling() {
    return ceiling;
  }

  Predicate<AccessContext> availableTo() {
    return availableTo;
  }
}
