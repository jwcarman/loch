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

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The laws every {@link Lattice} must satisfy, as executable tests.
 *
 * <p>Extend it, hand it a lattice and some sample labels, and the properties run. An application
 * defining its own label type should do this, because the failure modes here are all silent: a
 * lattice with a broken join type-checks, compiles, and permits exactly the wrong things.
 *
 * <pre>{@code
 * class ClearanceLatticeTest extends LatticeTck<Clearance> {
 *   protected Lattice<Clearance> lattice() { return Lattices.ordinal(Clearance.class); }
 *   protected List<Clearance> samples() { return List.of(Clearance.values()); }
 * }
 * }</pre>
 *
 * <p>Samples are exercised exhaustively in pairs and triples, so a handful is plenty and a hundred
 * is slow. Include the interesting ones: the bottom, the top, and anything that combines oddly.
 */
public abstract class LatticeTck<T> {

  /** The lattice under test. */
  protected abstract Lattice<T> lattice();

  /** Labels to exercise it with. Include the extremes. */
  protected abstract List<T> samples();

  @Test
  @DisplayName("joining is associative, so the order parents are folded in cannot matter")
  void joining_is_associative() {
    Lattice<T> lattice = lattice();
    for (T a : samples()) {
      for (T b : samples()) {
        for (T c : samples()) {
          assertThat(lattice.join(lattice.join(a, b), c))
              .as("(%s ⊔ %s) ⊔ %s", a, b, c)
              .isEqualTo(lattice.join(a, lattice.join(b, c)));
        }
      }
    }
  }

  @Test
  @DisplayName("joining is commutative, so which parent came first cannot matter")
  void joining_is_commutative() {
    Lattice<T> lattice = lattice();
    for (T a : samples()) {
      for (T b : samples()) {
        assertThat(lattice.join(a, b)).as("%s ⊔ %s", a, b).isEqualTo(lattice.join(b, a));
      }
    }
  }

  @Test
  @DisplayName("joining a label with itself changes nothing, so re-deriving cannot drift")
  void joining_is_idempotent() {
    Lattice<T> lattice = lattice();
    for (T a : samples()) {
      assertThat(lattice.join(a, a)).as("%s ⊔ %s", a, a).isEqualTo(a);
    }
  }

  @Test
  @DisplayName("bottom is the identity, so an unlabelled parent contributes nothing")
  void bottom_is_the_identity() {
    Lattice<T> lattice = lattice();
    T bottom = lattice.bottom();
    for (T a : samples()) {
      assertThat(lattice.join(a, bottom)).as("%s ⊔ ⊥", a).isEqualTo(a);
    }
  }

  /**
   * The one that matters most in practice.
   *
   * <p>{@code permits} is derived from {@code join} through {@code equals}, so a label type with
   * identity-based equality does not fail loudly — every comparison is false, and the gate refuses
   * everything including its own bottom. This catches that in one assertion.
   */
  @Test
  @DisplayName("permits agrees with join, and bottom is permitted everywhere")
  void permits_agrees_with_join() {
    Lattice<T> lattice = lattice();
    T bottom = lattice.bottom();
    for (T value : samples()) {
      assertThat(lattice.permits(bottom, value))
          .as("⊥ must reach every ceiling, including %s", value)
          .isTrue();
      assertThat(lattice.permits(value, value)).as("%s ⊑ itself", value).isTrue();
      for (T ceiling : samples()) {
        assertThat(lattice.permits(value, ceiling))
            .as("%s ⊑ %s", value, ceiling)
            .isEqualTo(lattice.join(value, ceiling).equals(ceiling));
      }
    }
  }

  /**
   * Labels are compared by value and shared across values and threads, so identity-based equality
   * or a mutable label would let one value's label change another's.
   */
  @Test
  @DisplayName("labels have value semantics, so equal labels stay equal")
  void labels_have_value_semantics() {
    Lattice<T> lattice = lattice();
    for (T a : samples()) {
      for (T b : samples()) {
        T once = lattice.join(a, b);
        T twice = lattice.join(a, b);
        assertThat(once).as("joining %s and %s twice", a, b).isEqualTo(twice);
        assertThat(once).as("a label must equal itself").isEqualTo(once);
        if (once.equals(twice)) {
          assertThat(once.hashCode())
              .as("equal labels must agree on hashCode")
              .isEqualTo(twice.hashCode());
        }
      }
    }
  }
}
