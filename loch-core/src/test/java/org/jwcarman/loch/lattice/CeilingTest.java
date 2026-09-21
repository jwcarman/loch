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
import static org.jwcarman.loch.lattice.Constraint.any;
import static org.jwcarman.loch.lattice.Constraint.atMost;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * A ceiling is the most a reader is entitled to, and it is said one axis at a time.
 *
 * <p>Which is the whole point of the redesign. As a single point in the lattice it could not
 * express the reporting job below -- entitled to every tenant, but still only endorsed and still
 * only ordinary -- so the library offered a way to turn the check off instead, buying breadth on
 * one axis by surrendering all three. The last two tests here are the ones that could not be
 * written before.
 */
@DisplayName("A ceiling")
class CeilingTest {

  private enum Integrity {
    ENDORSED,
    UNENDORSED
  }

  private enum Sensitivity {
    ORDINARY,
    PERSONAL,
    CARDHOLDER
  }

  private static final Axis<String> TENANT = Axis.matching("tenant").required();
  private static final Axis<Integrity> INTEGRITY =
      Axis.ladder("integrity", Integrity.ENDORSED, Integrity.UNENDORSED);
  private static final Axis<Sensitivity> SENSITIVITY =
      Axis.ladder(
          "sensitivity", Sensitivity.ORDINARY, Sensitivity.PERSONAL, Sensitivity.CARDHOLDER);

  private static Label labelled(String tenant, Integrity integrity, Sensitivity sensitivity) {
    return Label.of(TENANT, tenant).with(INTEGRITY, integrity).with(SENSITIVITY, sensitivity);
  }

  private static Ceiling entitledTo(String tenant, Integrity integrity, Sensitivity sensitivity) {
    return Ceiling.of(TENANT, atMost(tenant))
        .with(INTEGRITY, atMost(integrity))
        .with(SENSITIVITY, atMost(sensitivity));
  }

  private final Ceiling paymentProcessor =
      entitledTo("acme", Integrity.ENDORSED, Sensitivity.CARDHOLDER);

  @Test
  @DisplayName("admits a value at exactly what it is entitled to")
  void admits_a_value_at_exactly_its_entitlement() {
    assertThat(
            paymentProcessor.permits(labelled("acme", Integrity.ENDORSED, Sensitivity.CARDHOLDER)))
        .isTrue();
  }

  @Test
  @DisplayName("admits a value less constrained than its entitlement")
  void admits_a_less_constrained_value() {
    assertThat(paymentProcessor.permits(labelled("acme", Integrity.ENDORSED, Sensitivity.ORDINARY)))
        .isTrue();
  }

  @Test
  @DisplayName("refuses a value too sensitive for it, however right the rest is")
  void refuses_a_value_that_is_too_sensitive() {
    Ceiling supportUi = entitledTo("acme", Integrity.ENDORSED, Sensitivity.ORDINARY);

    assertThat(supportUi.permits(labelled("acme", Integrity.ENDORSED, Sensitivity.CARDHOLDER)))
        .isFalse();
  }

  @Test
  @DisplayName("refuses another tenant's value, however ordinary it is")
  void refuses_another_tenants_value() {
    assertThat(
            paymentProcessor.permits(labelled("globex", Integrity.ENDORSED, Sensitivity.ORDINARY)))
        .isFalse();
  }

  @Test
  @DisplayName("refuses untrusted data to a reader entitled only to endorsed")
  void refuses_untrusted_data() {
    assertThat(
            paymentProcessor.permits(labelled("acme", Integrity.UNENDORSED, Sensitivity.ORDINARY)))
        .isFalse();
  }

  /**
   * The difference between a reader who declared breadth and one who forgot an axis.
   *
   * <p>These must not look the same. If silence meant permission, an axis added to a running system
   * would quietly stop being enforced on every ceiling written before it existed -- and it would do
   * so without failing anything, which is the worst way for a control to stop working.
   */
  @Test
  @DisplayName("refuses a value labelled on an axis it never mentioned")
  void refuses_an_axis_it_never_mentioned() {
    Ceiling forgotTheTenant =
        Ceiling.of(INTEGRITY, atMost(Integrity.ENDORSED))
            .with(SENSITIVITY, atMost(Sensitivity.CARDHOLDER));

    assertThat(forgotTheTenant.constrains(TENANT)).isFalse();
    assertThat(forgotTheTenant.permits(labelled("acme", Integrity.ENDORSED, Sensitivity.ORDINARY)))
        .isFalse();
  }

  @Test
  @DisplayName("admits a label that says nothing at all")
  void admits_a_label_that_says_nothing() {
    assertThat(paymentProcessor.permits(Label.nothing())).isTrue();
  }

  /**
   * The reporting job: entitled to every tenant, and to nothing else it was not entitled to.
   *
   * <p>This is the shape the old ceiling could not express. Getting breadth on the tenant axis used
   * to mean turning off the integrity and sensitivity checks as well, which is why the only uses of
   * it in the repository were tests -- nobody wanted to write that into an application.
   */
  @Test
  @DisplayName("reads every tenant while still refusing what it may not see")
  void reads_every_tenant_while_still_refusing_the_rest() {
    Ceiling reporting =
        Ceiling.of(TENANT, any())
            .with(INTEGRITY, atMost(Integrity.ENDORSED))
            .with(SENSITIVITY, atMost(Sensitivity.ORDINARY));

    assertThat(reporting.permits(labelled("acme", Integrity.ENDORSED, Sensitivity.ORDINARY)))
        .isTrue();
    assertThat(reporting.permits(labelled("globex", Integrity.ENDORSED, Sensitivity.ORDINARY)))
        .isTrue();
    assertThat(reporting.permits(labelled("acme", Integrity.UNENDORSED, Sensitivity.ORDINARY)))
        .isFalse();
    assertThat(reporting.permits(labelled("acme", Integrity.ENDORSED, Sensitivity.CARDHOLDER)))
        .isFalse();
  }

  /**
   * And the reason breadth on a tenant is safe to offer at all.
   *
   * <p>The reporting job reads each tenant's rows legitimately. What it makes by combining them is
   * a mixture, and a mixture reaches nobody -- including the reader broad enough to have read both
   * halves. Refusing the individual reads would block the wrong thing; admitting the mixture would
   * block nothing.
   */
  @Test
  @DisplayName("still refuses the mixture made from the tenants it may read")
  void still_refuses_the_mixture_it_could_make() {
    Ceiling reporting = Ceiling.of(TENANT, any());
    Label mixed = Label.of(TENANT, "acme").join(Label.of(TENANT, "globex"));

    assertThat(reporting.permits(Label.of(TENANT, "acme"))).isTrue();
    assertThat(reporting.permits(Label.of(TENANT, "globex"))).isTrue();
    assertThat(reporting.permits(mixed)).isFalse();
  }

  @Test
  @DisplayName("says what it is entitled to, for a refusal message")
  void says_what_it_is_entitled_to() {
    Ceiling reporting = Ceiling.of(TENANT, any()).with(SENSITIVITY, atMost(Sensitivity.ORDINARY));

    assertThat(reporting.toString()).contains("tenant=(any)").contains("sensitivity=ORDINARY");
  }
}
