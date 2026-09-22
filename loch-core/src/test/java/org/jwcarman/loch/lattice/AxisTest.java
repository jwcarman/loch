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

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * An axis is a kind, not a name bolted onto an order, and the two kinds differ in exactly the way
 * that matters: what happens when you combine two values that disagree.
 *
 * <p>A ladder picks the more constrained rung and carries on. A matching axis cannot pick, because
 * neither tenant is above the other, so it produces a mixture instead -- a value that exists, keeps
 * its lineage, and reaches nobody. Everything else in this file is bookkeeping around that one
 * difference.
 */
@DisplayName("An axis")
class AxisTest {

  private enum Sensitivity {
    ORDINARY,
    PERSONAL,
    CARDHOLDER
  }

  @Nested
  @DisplayName("that is a ladder")
  class ALadder {

    private final Axis<Sensitivity> sensitivity =
        Axis.ladder(
            "sensitivity", Sensitivity.ORDINARY, Sensitivity.PERSONAL, Sensitivity.CARDHOLDER);

    private Object lift(Sensitivity value) {
      return sensitivity.lift(value);
    }

    @Test
    @DisplayName("combines two rungs into the more constrained one")
    void combines_into_the_more_constrained_rung() {
      assertThat(sensitivity.join(lift(Sensitivity.ORDINARY), lift(Sensitivity.CARDHOLDER)))
          .isEqualTo(Sensitivity.CARDHOLDER);
    }

    @Test
    @DisplayName("lets a ceiling admit everything at or below it")
    void admits_everything_at_or_below() {
      assertThat(sensitivity.permits(lift(Sensitivity.PERSONAL), lift(Sensitivity.CARDHOLDER)))
          .isTrue();
      assertThat(sensitivity.permits(lift(Sensitivity.PERSONAL), lift(Sensitivity.PERSONAL)))
          .isTrue();
      assertThat(sensitivity.permits(lift(Sensitivity.CARDHOLDER), lift(Sensitivity.PERSONAL)))
          .isFalse();
    }

    /**
     * Nothing on a ladder is poisoned, so a reader who constrains nothing really may see it all.
     */
    @Test
    @DisplayName("admits every rung to a reader who constrained it not at all")
    void admits_every_rung_to_an_unconstrained_reader() {
      assertThat(sensitivity.admitsAny(lift(Sensitivity.ORDINARY))).isTrue();
      assertThat(sensitivity.admitsAny(lift(Sensitivity.CARDHOLDER))).isTrue();
    }

    @Test
    @DisplayName("starts at the rung that was listed first")
    void starts_at_the_first_rung() {
      assertThat(sensitivity.bottom()).isEqualTo(Sensitivity.ORDINARY);
    }

    @Test
    @DisplayName("round-trips a rung through storage")
    void round_trips_through_storage() {
      String encoded = sensitivity.encode(lift(Sensitivity.PERSONAL));

      assertThat(sensitivity.decode(encoded)).isEqualTo(Sensitivity.PERSONAL);
    }

    /**
     * A rung that was dropped from the enum leaves rows behind that must not decode to something.
     */
    @Test
    @DisplayName("refuses to decode a rung it does not have")
    void refuses_to_decode_an_unknown_rung() {
      assertThatThrownBy(() -> sensitivity.decode("RETIRED"))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("sensitivity");
    }

    @Test
    @DisplayName("refuses a ladder that ranks the same constant twice")
    void refuses_a_repeated_rung() {
      assertThatThrownBy(
              () -> Axis.ladder("sensitivity", Sensitivity.ORDINARY, Sensitivity.ORDINARY))
          .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("refuses to be built with no rungs at all")
    void refuses_to_be_built_with_no_rungs() {
      assertThatThrownBy(() -> Axis.<Sensitivity>ladder("sensitivity"))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("sensitivity");
    }

    @Test
    @DisplayName("refuses to be built with a null array of rungs")
    void refuses_to_be_built_with_a_null_array_of_rungs() {
      assertThatThrownBy(() -> Axis.ladder("sensitivity", (Sensitivity[]) null))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("sensitivity");
    }

    @Test
    @DisplayName("names itself when printed")
    void names_itself_when_printed() {
      assertThat(sensitivity).hasToString("sensitivity");
    }

    /**
     * A ladder built with fewer rungs than the enum has is a real declaration, not a mistake: an
     * application may deliberately leave a constant unreachable. Lifting one anyway has to refuse
     * rather than silently rank it.
     */
    @Test
    @DisplayName("refuses to lift a rung it was not built with")
    void refuses_to_lift_a_rung_it_was_not_built_with() {
      Axis<Sensitivity> narrow =
          Axis.ladder("sensitivity", Sensitivity.ORDINARY, Sensitivity.PERSONAL);

      assertThatThrownBy(() -> narrow.lift(Sensitivity.CARDHOLDER))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("sensitivity");
    }
  }

  @Nested
  @DisplayName("that has to match exactly")
  class AMatchingAxis {

    private final Axis<String> tenant = Axis.matching("tenant");

    @Test
    @DisplayName("combines a value with itself into itself")
    void combines_a_value_with_itself() {
      Object combined = tenant.join(tenant.lift("acme"), tenant.lift("acme"));

      assertThat(tenant.render(combined)).isEqualTo("acme");
    }

    @Test
    @DisplayName("combines two different values into a mixture")
    void combines_two_different_values_into_a_mixture() {
      Object combined = tenant.join(tenant.lift("acme"), tenant.lift("globex"));

      assertThat(tenant.render(combined)).isEqualTo("(mixed)");
    }

    /** The claim the whole design rests on, stated as a test rather than as a comment. */
    @Test
    @DisplayName("lets a mixture reach no ceiling at all")
    void lets_a_mixture_reach_no_ceiling() {
      Object mixed = tenant.join(tenant.lift("acme"), tenant.lift("globex"));

      assertThat(tenant.permits(mixed, tenant.lift("acme"))).isFalse();
      assertThat(tenant.permits(mixed, tenant.lift("globex"))).isFalse();
      assertThat(tenant.permits(mixed, tenant.bottom())).isFalse();
    }

    /**
     * Breadth means any one tenant, never a mixture.
     *
     * <p>This is what lets a reporting job read across tenants safely: each individual row is a
     * single tenant and passes, and anything it combines from them is unusable. Admitting the
     * mixture here would block nothing; refusing the rows would block the wrong thing.
     */
    @Test
    @DisplayName("admits any one value to an unconstrained reader, but never a mixture")
    void admits_one_value_but_never_a_mixture() {
      Object mixed = tenant.join(tenant.lift("acme"), tenant.lift("globex"));

      assertThat(tenant.admitsAny(tenant.lift("acme"))).isTrue();
      assertThat(tenant.admitsAny(tenant.lift("globex"))).isTrue();
      assertThat(tenant.admitsAny(mixed)).isFalse();
    }

    @Test
    @DisplayName("refuses one value to another's ceiling")
    void refuses_one_value_to_anothers_ceiling() {
      assertThat(tenant.permits(tenant.lift("acme"), tenant.lift("acme"))).isTrue();
      assertThat(tenant.permits(tenant.lift("acme"), tenant.lift("globex"))).isFalse();
    }

    /** Unsaid is the identity, which is what makes it sit below every ceiling. */
    @Test
    @DisplayName("leaves an unsaid value below every ceiling")
    void leaves_an_unsaid_value_below_every_ceiling() {
      assertThat(tenant.permits(tenant.bottom(), tenant.lift("acme"))).isTrue();
      assertThat(tenant.join(tenant.bottom(), tenant.lift("acme"))).isEqualTo(tenant.lift("acme"));
    }

    /**
     * Which is exactly why an axis can be marked required.
     *
     * <p>An unsaid tenant is readable by everybody, and that is the most dangerous outcome
     * available, so a value that leaves one unsaid is a gap rather than a label.
     */
    @Test
    @DisplayName("reports an unsaid value as a gap once it is required")
    void reports_an_unsaid_value_as_a_gap_once_required() {
      Axis<String> required = tenant.required();

      assertThat(required.unsaid(required.bottom())).isTrue();
      assertThat(required.unsaid(required.lift("acme"))).isFalse();
      assertThat(tenant.unsaid(tenant.bottom())).isFalse();
    }

    @Test
    @DisplayName("round-trips a value, an absence and a mixture through storage")
    void round_trips_through_storage() {
      Object one = tenant.lift("acme");
      Object mixed = tenant.join(one, tenant.lift("globex"));

      assertThat(tenant.decode(tenant.encode(one))).isEqualTo(one);
      assertThat(tenant.decode(tenant.encode(tenant.bottom()))).isEqualTo(tenant.bottom());
      assertThat(tenant.decode(tenant.encode(mixed))).isEqualTo(mixed);
    }

    @Test
    @DisplayName("renders an absence distinctly from a mixture")
    void renders_an_absence_distinctly_from_a_mixture() {
      Object mixed = tenant.join(tenant.lift("acme"), tenant.lift("globex"));

      assertThat(tenant.render(tenant.bottom())).isEqualTo("(unsaid)");
      assertThat(tenant.render(mixed)).isEqualTo("(mixed)");
    }

    /** Bottom is the identity on either side of the combination, not only on the left. */
    @Test
    @DisplayName("leaves an unsaid value below every ceiling from either side of the combination")
    void leaves_an_unsaid_value_below_every_ceiling_from_either_side() {
      assertThat(tenant.join(tenant.lift("acme"), tenant.bottom())).isEqualTo(tenant.lift("acme"));
    }

    /** Once either side is a mixture, the result is a mixture, whichever side carries it. */
    @Test
    @DisplayName("stays a mixture once either side already is one")
    void stays_a_mixture_once_either_side_already_is_one() {
      Object mixed = tenant.join(tenant.lift("acme"), tenant.lift("globex"));

      Object mixedOnTheLeft = tenant.join(mixed, tenant.lift("acme"));
      Object mixedOnTheRight = tenant.join(tenant.lift("acme"), mixed);

      assertThat(tenant.render(mixedOnTheLeft)).isEqualTo("(mixed)");
      assertThat(tenant.render(mixedOnTheRight)).isEqualTo("(mixed)");
    }

    /** A value written by a schema this store no longer understands is refused, not misread. */
    @Test
    @DisplayName("refuses to decode a value in a shape it does not recognise")
    void refuses_to_decode_an_unrecognised_shape() {
      assertThatThrownBy(() -> tenant.decode("garbage"))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("garbage");
    }
  }
}
