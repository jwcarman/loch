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
import java.util.UUID;

/**
 * The name of a value in the loch, and nothing else.
 *
 * <p>Deliberately inert. It carries no labels, because labels come from the store -- otherwise
 * anything that could write an id could claim to be trusted. And it carries no structure: no
 * parents, no derivation, no recipe. If an id described how to make a value, then presenting one
 * would be a request to compute, and ids arrive from untrusted places.
 *
 * <p>So the rule this type exists to enforce is that <b>lookup never computes</b>. An id is found
 * or it is refused. Manufacturing one gains nothing, which is why they can be passed anywhere.
 */
public record HandleId(String value) {

  private static final String PREFIX = "loch_";

  public HandleId {
    Objects.requireNonNull(value, "an id needs a value");
    if (value.isBlank()) {
      throw new IllegalArgumentException("an id cannot be blank");
    }
  }

  /** A fresh id, for a value that was held or non-deterministically derived. */
  public static HandleId fresh() {
    return new HandleId(PREFIX + UUID.randomUUID());
  }

  @Override
  public String toString() {
    return value;
  }
}
