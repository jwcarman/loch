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
import org.jwcarman.loch.lattice.Ceiling;

public final class Querying<I, Q> {

  private final DefaultCharter config;
  private final String name;
  private final SurrogateType<I> inputType;
  private final Query.Asking<I, Q> asking;
  private java.util.function.Function<AccessContext, Ceiling> ceiling;
  private java.util.function.Predicate<AccessContext> availableTo = context -> true;

  Querying(
      DefaultCharter config, String name, SurrogateType<I> inputType, Query.Asking<I, Q> asking) {
    this.config = config;
    this.name = name;
    this.inputType = inputType;
    this.asking = asking;
  }

  /** The most constrained value this may be asked about. */
  public Querying<I, Q> accepting(java.util.function.Function<AccessContext, Ceiling> ceiling) {
    this.ceiling = Objects.requireNonNull(ceiling, "a ceiling must not be null");
    return this;
  }

  /** Accepts the same thing regardless of who is asking. */
  public Querying<I, Q> accepting(Ceiling ceiling) {
    Objects.requireNonNull(ceiling, "a ceiling must not be null");
    return accepting(context -> ceiling);
  }

  /** Whether this is offered at all, given who is asking. */
  public Querying<I, Q> availableTo(java.util.function.Predicate<AccessContext> availableTo) {
    this.availableTo = Objects.requireNonNull(availableTo, "an availability must not be null");
    return this;
  }

  /** Registers it and hands back the capability. Nothing can obtain one any other way. */
  public Query<I, Q> mint() {
    if (ceiling == null) {
      throw new IllegalStateException(
          "'"
              + name
              + "' reads plaintext to answer, so it needs a ceiling: call accepting(...) with"
              + " what it may look at, saying any() on the axes it is deliberately broad about");
    }
    QuerySpec<I, Q> spec = new QuerySpec<>(name, inputType, asking, ceiling, availableTo);
    var lifecycle = config.lifecycle();
    String what = "query '" + name + "'";
    return config.declare(
        configuration ->
            new DefaultCharter.Declared<>(
                config.recording(configuration, inputType).with(spec),
                new Query<I, Q>() {
                  @Override
                  public Answer ask(Surrogate<I> about, Q against) {
                    return DefaultCharter.engineOf(lifecycle, what).askVia(spec, about, against);
                  }

                  @Override
                  public String toString() {
                    return what;
                  }
                }));
  }
}
