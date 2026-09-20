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
 * The name of somewhere a value might go.
 *
 * <p>Inert, like every other reference here. A destination's ceiling lives in the registry,
 * declared at wiring; this is only the name used to look it up. If ceilings could be supplied at a
 * call site, granting yourself permission would be one line of code.
 */
public record DestinationId(String value) {

  public DestinationId {
    Objects.requireNonNull(value, "a destination needs a name");
    if (value.isBlank()) {
      throw new IllegalArgumentException("a destination's name cannot be blank");
    }
  }

  public static DestinationId of(String value) {
    return new DestinationId(value);
  }

  @Override
  public String toString() {
    return value;
  }
}
