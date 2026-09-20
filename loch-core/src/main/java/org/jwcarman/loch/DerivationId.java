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

/**
 * The name of a way to make one value from another.
 *
 * <p><b>Inert, and that is the point.</b> A derivation's function lives in the registry, declared
 * at wiring. This carries only a name, so anyone may construct one and gain nothing: an
 * unregistered name is refused, and a registered one resolves to exactly the reviewed
 * implementation.
 *
 * <p>The alternative -- passing a {@code Derivation} object to the store -- cannot work, because
 * the store has no way to tell a reviewed constant from a lambda built at the call site a
 * nanosecond ago. A function handed to the store is a function that runs inside the trust boundary
 * with the plaintext, so the set of them has to be enumerable, and it is only enumerable if it is a
 * registry.
 *
 * <p>The type parameters are phantom: they exist so the compiler can check that a derivation is
 * applied to the right kind of handle, and are not present at runtime. The registry holds the real
 * types and checks them.
 */
public record DerivationId<I, O>(String value) {

  public DerivationId {
    Objects.requireNonNull(value, "a derivation needs a name");
    if (value.isBlank()) {
      throw new IllegalArgumentException("a derivation's name cannot be blank");
    }
  }

  public static <I, O> DerivationId<I, O> of(String value) {
    return new DerivationId<>(value);
  }

  @Override
  public String toString() {
    return value;
  }
}
