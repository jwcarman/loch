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
package org.jwcarman.loch.lattice;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Ranked constants: the rungs of a ladder, least constrained first.
 *
 * <p>Combining two gives the higher rung. Nothing here is poisoned, so an unconstrained reader sees
 * every rung -- which is correct, and is why breadth is safe to offer on an ordered axis without
 * anyone having to think about it.
 */
final class Ladder implements Order {

  private final String axis;
  private final Map<Enum<?>, Integer> ranks;
  private final Map<String, Enum<?>> byName;
  private final Enum<?> bottom;

  private Ladder(String axis, Map<Enum<?>, Integer> ranks, Map<String, Enum<?>> byName) {
    this.axis = axis;
    this.ranks = ranks;
    this.byName = byName;
    this.bottom = byName.values().iterator().next();
  }

  static <E extends Enum<E>> Ladder of(String axis, E[] leastConstrainedFirst) {
    Map<Enum<?>, Integer> ranks = new LinkedHashMap<>();
    Map<String, Enum<?>> byName = new LinkedHashMap<>();
    for (int rung = 0; rung < leastConstrainedFirst.length; rung++) {
      E constant = Objects.requireNonNull(leastConstrainedFirst[rung], "a rung must not be null");
      if (ranks.put(constant, rung) != null) {
        throw new IllegalArgumentException(
            constant + " appears twice in '" + axis + "', so combining it would depend on order");
      }
      byName.put(constant.name(), constant);
    }
    return new Ladder(axis, ranks, byName);
  }

  private int rankOf(Object value) {
    Integer rank = ranks.get(value);
    if (rank == null) {
      throw new IllegalArgumentException(value + " is not a rung of '" + axis + "'");
    }
    return rank;
  }

  @Override
  public Object lift(Object written) {
    rankOf(written);
    return written;
  }

  @Override
  public Object bottom() {
    return bottom;
  }

  @Override
  public Object join(Object left, Object right) {
    return rankOf(left) >= rankOf(right) ? left : right;
  }

  @Override
  public boolean admitsAny(Object value) {
    return true;
  }

  @Override
  public String render(Object value) {
    return ((Enum<?>) value).name();
  }

  @Override
  public String encode(Object value) {
    return ((Enum<?>) value).name();
  }

  @Override
  public Object decode(String encoded) {
    Enum<?> constant = byName.get(encoded);
    if (constant == null) {
      throw new IllegalArgumentException(
          encoded + " is not a rung of '" + axis + "'; it was written by a different schema");
    }
    return constant;
  }
}
