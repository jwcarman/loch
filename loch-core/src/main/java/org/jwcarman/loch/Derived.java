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

/** What came of asking for a value to be derived from another. */
public sealed interface Derived<O> {

  /** A new handle, stored, labelled and with its lineage recorded. */
  record Made<O>(Held<O> value) implements Derived<O> {}

  /** No new value, and why. */
  record Refused<O>(Reason reason, String detail) implements Derived<O> {}

  /** Why a derivation did not happen. */
  enum Reason {
    /** No derivation registered under that name. A bug: startup validated the registry. */
    NO_SUCH_DERIVATION,
    /** No such parent value. */
    NO_SUCH_VALUE,
    /** The parent is not the type this derivation takes. */
    WRONG_TYPE,
    /**
     * The parent's label is above what this derivation accepts. A derivation receives plaintext in
     * order to compute, so it is a destination like any other.
     */
    ABOVE_CEILING,
    /** This derivation is not offered in this context. */
    NOT_AVAILABLE_HERE,
    /**
     * A privileged derivation's relabelling did not actually lower anything. Raising is what
     * ordinary derivation already does, so declaring it here is a mistake worth naming.
     */
    NOT_A_LOWERING,
    /** The registered function refused, on its own terms. */
    DECLINED
  }

  default Optional<Held<O>> made() {
    return this instanceof Made<O> made ? Optional.of(made.value()) : Optional.empty();
  }

  default boolean succeeded() {
    return this instanceof Made<O>;
  }

  /** The handle, or an exception naming the refusal. */
  default Held<O> orThrow() {
    if (this instanceof Made<O> made) {
      return made.value();
    }
    Refused<O> refused = (Refused<O>) this;
    throw new DerivationRefusedException(refused.reason(), refused.detail());
  }
}
