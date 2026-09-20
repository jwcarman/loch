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

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

@DisplayName("A label made of independent axes")
class ProductLatticeTest {

  enum Integrity {
    ENDORSED,
    UNENDORSED;

    static final Lattice<Integrity> LATTICE = Lattices.ladder(ENDORSED, UNENDORSED);
  }

  enum Sensitivity {
    ORDINARY,
    PERSONAL,
    CARDHOLDER;

    static final Lattice<Sensitivity> LATTICE = Lattices.ladder(ORDINARY, PERSONAL, CARDHOLDER);
  }

  record Labels(Exact<String> tenant, Integrity integrity, Sensitivity sensitivity) {

    static final Lattice<Labels> LATTICE =
        Lattices.product(
            Labels::new,
            Lattices.axis(Labels::tenant, Lattices.exact()),
            Lattices.axis(Labels::integrity, Integrity.LATTICE),
            Lattices.axis(Labels::sensitivity, Sensitivity.LATTICE));
  }

  @Nested
  @DisplayName("obeys every law its axes obey")
  class Laws extends LatticeTck<Labels> {

    @Override
    protected Lattice<Labels> lattice() {
      return Labels.LATTICE;
    }

    @Override
    protected List<Labels> samples() {
      List<Labels> samples = new ArrayList<>();
      for (Exact<String> tenant :
          List.of(
              Exact.<String>none(),
              Exact.of("acme"),
              Exact.of("globex"),
              Exact.<String>conflict())) {
        for (Integrity integrity : Integrity.values()) {
          for (Sensitivity sensitivity : Sensitivity.values()) {
            samples.add(new Labels(tenant, integrity, sensitivity));
          }
        }
      }
      return samples;
    }
  }

  @Test
  @DisplayName("takes the more constrained value on every axis independently")
  void takes_the_more_constrained_on_every_axis() {
    Labels ordinary = new Labels(Exact.of("acme"), Integrity.ENDORSED, Sensitivity.ORDINARY);
    Labels personal = new Labels(Exact.of("acme"), Integrity.UNENDORSED, Sensitivity.PERSONAL);

    Labels joined = Labels.LATTICE.join(ordinary, personal);

    assertThat(joined.tenant().resolved()).contains("acme");
    assertThat(joined.integrity()).isEqualTo(Integrity.UNENDORSED);
    assertThat(joined.sensitivity()).isEqualTo(Sensitivity.PERSONAL);
  }

  @Test
  @DisplayName("and one axis conflicting makes the whole label unusable")
  void one_axis_conflicting_makes_the_whole_label_unusable() {
    Labels acme = new Labels(Exact.of("acme"), Integrity.ENDORSED, Sensitivity.ORDINARY);
    Labels globex = new Labels(Exact.of("globex"), Integrity.ENDORSED, Sensitivity.ORDINARY);

    Labels joined = Labels.LATTICE.join(acme, globex);

    assertThat(joined.tenant().conflicted()).isTrue();
    assertThat(Labels.LATTICE.permits(joined, acme)).isFalse();
    assertThat(Labels.LATTICE.permits(joined, globex)).isFalse();
  }

  @Test
  @DisplayName("the bottom is every axis's own bottom")
  void the_bottom_is_every_axis_bottom() {
    Labels bottom = Labels.LATTICE.bottom();

    assertThat(bottom.tenant().empty()).isTrue();
    assertThat(bottom.integrity()).isEqualTo(Integrity.ENDORSED);
    assertThat(bottom.sensitivity()).isEqualTo(Sensitivity.ORDINARY);
  }
}
