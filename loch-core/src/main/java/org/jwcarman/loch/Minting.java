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
import java.util.Objects;
import java.util.Optional;
import org.jwcarman.loch.lattice.Ceiling;
import org.jwcarman.loch.lattice.Label;

/**
 * What a derivation still needs said about it before it becomes a capability.
 *
 * <p>One type for every arity, because everything left to say -- what it may read, whether it
 * lowers, where it is offered -- is about labels and contexts, not about how many parents there
 * are. {@code C} is whatever this eventually mints.
 */
public final class Minting<O, C> {

  private final DefaultCharter config;
  private final String name;
  private final List<SurrogateType<?>> inputTypes;
  private final SurrogateType<O> outputType;
  private final java.util.function.BiFunction<List<Object>, AccessContext, Optional<O>> function;
  private final boolean fold;
  private final java.util.function.BiFunction<
          DerivationSpec<O>, java.util.concurrent.atomic.AtomicReference<DefaultCharter.State>, C>
      capability;
  private java.util.function.Function<AccessContext, Ceiling> ceiling;
  private java.util.function.UnaryOperator<Label> relabel;
  private java.util.function.Predicate<AccessContext> availableTo = context -> true;

  Minting(
      DefaultCharter config,
      String name,
      List<SurrogateType<?>> inputTypes,
      SurrogateType<O> outputType,
      java.util.function.BiFunction<List<Object>, AccessContext, Optional<O>> function,
      boolean fold,
      java.util.function.BiFunction<
              DerivationSpec<O>,
              java.util.concurrent.atomic.AtomicReference<DefaultCharter.State>,
              C>
          capability) {
    this.config = config;
    this.name = name;
    this.inputTypes = inputTypes;
    this.outputType = outputType;
    this.function = function;
    this.fold = fold;
    this.capability = capability;
  }

  /** The most constrained parent this will accept. */
  public Minting<O, C> accepting(Ceiling ceiling) {
    Objects.requireNonNull(ceiling, "a ceiling must not be null");
    return accepting(context -> ceiling);
  }

  /** A ceiling that depends on who is asking, which a tenant always does. */
  public Minting<O, C> accepting(java.util.function.Function<AccessContext, Ceiling> ceiling) {
    this.ceiling = Objects.requireNonNull(ceiling, "a ceiling must not be null");
    return this;
  }

  /** Declares that the result is less constrained than its parents, and by how much. */
  public Minting<O, C> lowering(java.util.function.UnaryOperator<Label> relabel) {
    this.relabel = Objects.requireNonNull(relabel, "a lowering must not be null");
    return this;
  }

  /** Whether this is offered at all, given who is asking. */
  public Minting<O, C> availableTo(java.util.function.Predicate<AccessContext> availableTo) {
    this.availableTo = Objects.requireNonNull(availableTo, "an availability must not be null");
    return this;
  }

  /** Registers it and hands back the capability. Nothing can obtain one any other way. */
  public C mint() {
    if (ceiling == null) {
      throw new IllegalStateException(
          "'"
              + name
              + "' reads plaintext, so it needs a ceiling: call accepting(...) with what it may"
              + " look at, saying any() on the axes it is deliberately broad about");
    }
    DerivationSpec<O> spec =
        new DerivationSpec<>(
            name, inputTypes, outputType, function, ceiling, relabel, availableTo, fold);
    var lifecycle = config.lifecycle();
    SurrogateType<?>[] declared = new SurrogateType<?>[inputTypes.size() + 1];
    inputTypes.toArray(declared);
    declared[inputTypes.size()] = outputType;
    return config.declare(
        configuration ->
            new DefaultCharter.Declared<>(
                config.recording(configuration, declared).with(spec),
                capability.apply(spec, lifecycle)));
  }
}
