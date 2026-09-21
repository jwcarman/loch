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
 * The name an inlet is known by in the manifest and in every audit line it writes.
 *
 * <p>A name, never a key. Constructing this does not obtain the inlet it names, and there is no
 * method anywhere that trades one for the other: an {@link Inlet} is minted during configuration
 * and reaches code by being handed to it. That is the whole point of the type, so the public
 * factory here is not the hazard it would have been when doors were opened by naming them.
 */
public record InletId(String value) {

  public InletId {
    Objects.requireNonNull(value, "an inlet needs a name");
    if (value.isBlank()) {
      throw new IllegalArgumentException("an inlet's name cannot be blank");
    }
  }

  public static InletId of(String value) {
    return new InletId(value);
  }

  @Override
  public String toString() {
    return value;
  }
}
