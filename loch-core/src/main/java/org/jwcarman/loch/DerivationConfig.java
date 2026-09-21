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
import java.util.function.UnaryOperator;
import org.jwcarman.loch.lattice.Ceiling;
import org.jwcarman.loch.lattice.Label;

/**
 * What a derivation needs said about it beyond what it computes.
 *
 * <p>Handed to a customizer while the derivation is being declared, and useless afterwards. It
 * carries no type parameters because nothing here depends on them: what a derivation may read,
 * whether it weakens a label, and where it is offered are about labels and contexts, not about how
 * many parents there are or what comes out.
 */
public final class DerivationConfig {

  private Function<AccessContext, Ceiling> ceiling;
  private UnaryOperator<Label> relabel;
  private Predicate<AccessContext> availableTo = context -> true;

  DerivationConfig() {}

  /** The most a parent may be labelled and still be read here. */
  public DerivationConfig accepting(Ceiling ceiling) {
    Objects.requireNonNull(ceiling, "a ceiling must not be null");
    return accepting(context -> ceiling);
  }

  /** A ceiling that depends on who is asking, which a tenant always does. */
  public DerivationConfig accepting(Function<AccessContext, Ceiling> ceiling) {
    this.ceiling = Objects.requireNonNull(ceiling, "a ceiling must not be null");
    return this;
  }

  /**
   * Declares that the result is less constrained than its parents, and by how much.
   *
   * <p>The only way a label is ever weakened, and the engine still checks the result is genuinely
   * below the combination of the parents. Saying so here is what puts it in the manifest, under the
   * heading an auditor reads first.
   */
  public DerivationConfig lowering(UnaryOperator<Label> relabel) {
    this.relabel = Objects.requireNonNull(relabel, "a lowering must not be null");
    return this;
  }

  /** Whether this is offered at all, given who is asking. */
  public DerivationConfig availableTo(Predicate<AccessContext> availableTo) {
    this.availableTo = Objects.requireNonNull(availableTo, "an availability must not be null");
    return this;
  }

  Function<AccessContext, Ceiling> ceiling() {
    return ceiling;
  }

  UnaryOperator<Label> relabel() {
    return relabel;
  }

  Predicate<AccessContext> availableTo() {
    return availableTo;
  }
}
