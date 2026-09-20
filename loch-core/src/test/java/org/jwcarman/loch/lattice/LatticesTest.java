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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

@DisplayName("The lattices that ship in the box")
class LatticesTest {

  /** Least constrained first: the last constant is the one that may go fewest places. */
  enum Tlp {
    CLEAR,
    GREEN,
    AMBER,
    RED
  }

  enum Integrity {
    ENDORSED,
    UNENDORSED
  }

  @Nested
  @DisplayName("a ladder")
  class Ordinal extends LatticeTck<Tlp> {

    @Override
    protected Lattice<Tlp> lattice() {
      return Lattices.ladder(Tlp.CLEAR, Tlp.GREEN, Tlp.AMBER, Tlp.RED);
    }

    @Override
    protected List<Tlp> samples() {
      return List.of(Tlp.values());
    }

    @Test
    @DisplayName("takes the more constrained of the two")
    void takes_the_more_constrained() {
      Lattice<Tlp> tlp = Lattices.ladder(Tlp.CLEAR, Tlp.GREEN, Tlp.AMBER, Tlp.RED);
      assertThat(tlp.join(Tlp.CLEAR, Tlp.AMBER)).isEqualTo(Tlp.AMBER);
      assertThat(tlp.bottom()).isEqualTo(Tlp.CLEAR);
    }

    @Test
    @DisplayName("a value may reach a ceiling at or above it, and no further")
    void a_value_may_reach_a_ceiling_at_or_above_it() {
      Lattice<Tlp> tlp = Lattices.ladder(Tlp.CLEAR, Tlp.GREEN, Tlp.AMBER, Tlp.RED);
      assertThat(tlp.permits(Tlp.GREEN, Tlp.RED)).isTrue();
      assertThat(tlp.permits(Tlp.RED, Tlp.GREEN)).isFalse();
    }

    /** Two rungs is a lattice too, and it is the one integrity actually needs. */
    @Test
    @DisplayName("an unendorsed value cannot reach a ceiling that wants endorsement")
    void unendorsed_cannot_reach_an_endorsed_ceiling() {
      Lattice<Integrity> integrity = Lattices.ladder(Integrity.ENDORSED, Integrity.UNENDORSED);
      assertThat(integrity.permits(Integrity.UNENDORSED, Integrity.ENDORSED)).isFalse();
      assertThat(integrity.permits(Integrity.ENDORSED, Integrity.UNENDORSED)).isTrue();
    }

    @Test
    @DisplayName("an enum with no constants is refused, because it has no bottom")
    void an_enum_with_no_constants_is_refused() {
      assertThatThrownBy(() -> Lattices.ranked(Empty.class, Enum::ordinal))
          .isInstanceOf(IllegalArgumentException.class);
    }

    enum Empty {}
  }

  @Nested
  @DisplayName("a ladder whose order is said out loud")
  class Ranked extends LatticeTck<Classification> {

    @Override
    protected Lattice<Classification> lattice() {
      return Lattices.ranked(Classification.class, Classification::rung);
    }

    @Override
    protected List<Classification> samples() {
      return List.of(Classification.values());
    }

    /**
     * The hazard this whole API shape exists to prevent.
     *
     * <p>These constants are alphabetical, which is the sort of tidy-up nobody thinks twice about.
     * Read as declaration order it makes CONFIDENTIAL the least constrained thing in the system,
     * and confidential data sails through a ceiling that accepts only public data. There is
     * deliberately no factory that would do this, so the closest we can get is to show what the
     * ranks actually are.
     */
    @Test
    @DisplayName("declaration order is nothing like the real order here")
    void declaration_order_is_nothing_like_the_real_order() {
      assertThat(Classification.CONFIDENTIAL.ordinal()).isZero();
      assertThat(Classification.PUBLIC.ordinal()).isEqualTo(2);
      assertThat(Classification.PUBLIC.rung()).isLessThan(Classification.CONFIDENTIAL.rung());
    }

    @Test
    @DisplayName("an explicit rank gets it right, whatever the constants are reordered to")
    void an_explicit_rank_gets_it_right() {
      Lattice<Classification> byRung = Lattices.ranked(Classification.class, Classification::rung);

      assertThat(byRung.bottom()).isEqualTo(Classification.PUBLIC);
      assertThat(byRung.permits(Classification.CONFIDENTIAL, Classification.PUBLIC)).isFalse();
      assertThat(byRung.permits(Classification.SECRET, Classification.PUBLIC)).isFalse();
      assertThat(byRung.permits(Classification.PUBLIC, Classification.SECRET)).isTrue();
      assertThat(byRung.join(Classification.INTERNAL, Classification.SECRET))
          .isEqualTo(Classification.SECRET);
    }

    @Test
    @DisplayName("gaps in the ranks are fine")
    void gaps_in_the_ranks_are_fine() {
      assertThat(Lattices.ranked(Classification.class, Classification::rung).bottom())
          .isEqualTo(Classification.PUBLIC);
    }

    @Test
    @DisplayName(
        "two constants on the same rung are refused, because joining them would not commute")
    void two_constants_on_the_same_rung_are_refused() {
      assertThatThrownBy(() -> Lattices.ranked(Classification.class, c -> 1))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("distinct");
    }
  }

  /** Deliberately alphabetical, to show why the rung is written down. */
  enum Classification {
    CONFIDENTIAL(20),
    INTERNAL(10),
    PUBLIC(0),
    SECRET(30);

    private final int rung;

    Classification(int rung) {
      this.rung = rung;
    }

    int rung() {
      return rung;
    }
  }

  @Nested
  @DisplayName("agree or become unusable")
  class ExactMatch extends LatticeTck<Exact<String>> {

    @Override
    protected Lattice<Exact<String>> lattice() {
      return Lattices.exact();
    }

    @Override
    protected List<Exact<String>> samples() {
      return List.of(Exact.none(), Exact.of("acme"), Exact.of("globex"), Exact.conflict());
    }

    @Test
    @DisplayName("the same tenant twice is still that tenant")
    void the_same_tenant_twice_is_still_that_tenant() {
      Lattice<Exact<String>> tenant = Lattices.exact();
      assertThat(tenant.join(Exact.of("acme"), Exact.of("acme"))).isEqualTo(Exact.of("acme"));
    }

    /** The claim the whole design rests on, made concrete. */
    @Test
    @DisplayName("two tenants join to a conflict, which no ceiling admits")
    void two_tenants_join_to_a_conflict_which_no_ceiling_admits() {
      Lattice<Exact<String>> tenant = Lattices.exact();
      Exact<String> mixed = tenant.join(Exact.of("acme"), Exact.of("globex"));

      assertThat(mixed.conflicted()).isTrue();
      assertThat(mixed.resolved()).isEmpty();
      assertThat(samples()).isNotEmpty();
      assertThat(samples())
          .allSatisfy(
              ceiling ->
                  assertThat(tenant.permits(mixed, ceiling)).isEqualTo(ceiling.conflicted()));
    }

    @Test
    @DisplayName("saying nothing joins to whatever the other side said")
    void saying_nothing_joins_to_the_other_side() {
      Lattice<Exact<String>> tenant = Lattices.exact();
      assertThat(tenant.join(Exact.none(), Exact.of("acme"))).isEqualTo(Exact.of("acme"));
    }

    @Test
    @DisplayName("a conflict is permanent; nothing rescues it")
    void a_conflict_is_permanent() {
      Lattice<Exact<String>> tenant = Lattices.exact();
      assertThat(tenant.join(Exact.conflict(), Exact.of("acme")).conflicted()).isTrue();
      assertThat(tenant.join(Exact.conflict(), Exact.none()).conflicted()).isTrue();
    }

    @Test
    @DisplayName("an exact label needs a value")
    void an_exact_label_needs_a_value() {
      assertThatThrownBy(() -> Exact.of(null)).isInstanceOf(IllegalArgumentException.class);
    }
  }

  @Nested
  @DisplayName("accumulating sources")
  class Union extends LatticeTck<Set<String>> {

    @Override
    protected Lattice<Set<String>> lattice() {
      return Lattices.setUnion();
    }

    @Override
    protected List<Set<String>> samples() {
      return List.of(Set.of(), Set.of("crm"), Set.of("email"), Set.of("crm", "email"));
    }

    @Test
    @DisplayName("a value from two sources carries both, and needs a ceiling accepting both")
    void a_value_from_two_sources_carries_both() {
      Lattice<Set<String>> sources = Lattices.setUnion();
      Set<String> both = sources.join(Set.of("crm"), Set.of("email"));

      assertThat(both).containsExactlyInAnyOrder("crm", "email");
      assertThat(sources.permits(both, Set.of("crm"))).isFalse();
      assertThat(sources.permits(both, Set.of("crm", "email", "billing"))).isTrue();
    }

    @Test
    @DisplayName("the union is unmodifiable, so a label cannot be edited after the fact")
    void the_union_is_unmodifiable() {
      Set<String> both = Lattices.<String>setUnion().join(Set.of("crm"), Set.of("email"));
      assertThatThrownBy(() -> both.add("sneaky"))
          .isInstanceOf(UnsupportedOperationException.class);
    }
  }
}
