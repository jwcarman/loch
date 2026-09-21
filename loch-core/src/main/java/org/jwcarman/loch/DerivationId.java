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
 * The name a derivation or a fold is known by in the manifest and in every audit line it writes.
 *
 * <p>Untyped, and that is the change minting bought. It used to carry the input and output types so
 * that {@code loch.derive(handle, ID)} could type-check at the call site. With the capability
 * itself typed, the call site no longer names anything -- and five arities would otherwise have
 * needed five id types whose only job was compile-time safety at a call that no longer exists.
 *
 * <p>A name, never a key. Constructing one does not obtain the derivation it names.
 */
public record DerivationId(String value) {

  public DerivationId {
    Objects.requireNonNull(value, "a derivation needs a name");
    if (value.isBlank()) {
      throw new IllegalArgumentException("a derivation's name cannot be blank");
    }
  }

  public static DerivationId of(String value) {
    return new DerivationId(value);
  }

  @Override
  public String toString() {
    return value;
  }
}
