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
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * A label is built, combined and rendered, and that is the whole of it.
 *
 * <p>The interesting behaviour is all in combining, because combining is where a label stops being
 * whatever somebody wrote down and starts being a fact about provenance. Two labels that disagree
 * about a tenant produce one that no reader can use, and no amount of later relabelling gets it
 * back.
 */
@DisplayName("A label")
class LabelTest {

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

  @Test
  @DisplayName("takes the more constrained answer on every axis at once")
  void takes_the_more_constrained_answer_on_every_axis() {
    Label endorsedCard = labelled("acme", Integrity.ENDORSED, Sensitivity.CARDHOLDER);
    Label unendorsedOrdinary = labelled("acme", Integrity.UNENDORSED, Sensitivity.ORDINARY);

    Label combined = endorsedCard.join(unendorsedOrdinary);

    assertThat(combined).isEqualTo(labelled("acme", Integrity.UNENDORSED, Sensitivity.CARDHOLDER));
  }

  /**
   * Mixing a trusted value with an untrusted one gives an untrusted result.
   *
   * <p>Stated separately from the axis-by-axis rule above because it is the one people expect to be
   * negotiable. It is not: there is no combination that launders provenance.
   */
  @Test
  @DisplayName("never launders an untrusted parent into a trusted result")
  void never_launders_an_untrusted_parent() {
    Label trusted = Label.of(INTEGRITY, Integrity.ENDORSED);
    Label untrusted = Label.of(INTEGRITY, Integrity.UNENDORSED);

    assertThat(trusted.join(untrusted)).isEqualTo(untrusted);
  }

  @Test
  @DisplayName("combines two tenants into something neither of them said")
  void combines_two_tenants_into_a_mixture() {
    Label acme = Label.of(TENANT, "acme");
    Label globex = Label.of(TENANT, "globex");

    Label mixed = acme.join(globex);

    assertThat(mixed).isNotEqualTo(acme).isNotEqualTo(globex);
    assertThat(mixed.toString()).contains("(mixed)");
  }

  /**
   * What a label does not say sits at bottom, and bottom is the identity of combining.
   *
   * <p>So two labels describing different axes combine into one describing both, rather than one
   * quietly erasing what the other knew.
   */
  @Test
  @DisplayName("keeps what only one side said")
  void keeps_what_only_one_side_said() {
    Label tenantOnly = Label.of(TENANT, "acme");
    Label sensitivityOnly = Label.of(SENSITIVITY, Sensitivity.CARDHOLDER);

    Label combined = tenantOnly.join(sensitivityOnly);

    assertThat(combined)
        .isEqualTo(Label.of(TENANT, "acme").with(SENSITIVITY, Sensitivity.CARDHOLDER));
  }

  @Test
  @DisplayName("is unchanged by combining it with one that says nothing")
  void is_unchanged_by_a_label_that_says_nothing() {
    Label label = labelled("acme", Integrity.ENDORSED, Sensitivity.PERSONAL);

    assertThat(label.join(Label.nothing())).isEqualTo(label);
    assertThat(Label.nothing().join(label)).isEqualTo(label);
  }

  /** Declassification is a write, which is the only reason a label needs no getter. */
  @Test
  @DisplayName("replaces one axis and leaves the rest alone")
  void replaces_one_axis_and_leaves_the_rest() {
    Label untrusted = labelled("acme", Integrity.UNENDORSED, Sensitivity.PERSONAL);

    Label vouched = untrusted.with(INTEGRITY, Integrity.ENDORSED);

    assertThat(vouched).isEqualTo(labelled("acme", Integrity.ENDORSED, Sensitivity.PERSONAL));
  }

  @Test
  @DisplayName("does not change the label it was derived from")
  void does_not_change_what_it_was_derived_from() {
    Label untrusted = labelled("acme", Integrity.UNENDORSED, Sensitivity.PERSONAL);

    Label ignored = untrusted.with(INTEGRITY, Integrity.ENDORSED);

    assertThat(untrusted).isEqualTo(labelled("acme", Integrity.UNENDORSED, Sensitivity.PERSONAL));
    assertThat(ignored).isNotEqualTo(untrusted);
  }

  /**
   * An unsaid tenant is the bottom of its order, which sits below every ceiling.
   *
   * <p>So a value labelled with one would be readable by every tenant -- silently the most
   * dangerous outcome available, and the reason an axis can be marked required at all.
   */
  @Test
  @DisplayName("reports a required axis it never mentioned")
  void reports_a_required_axis_it_never_mentioned() {
    Label withoutTenant = Label.of(INTEGRITY, Integrity.ENDORSED);

    assertThat(withoutTenant.unsaid(TENANT)).isTrue();
    assertThat(Label.of(TENANT, "acme").unsaid(TENANT)).isFalse();
  }

  @Test
  @DisplayName("renders every axis it says something about")
  void renders_every_axis_it_mentions() {
    Label label = labelled("acme", Integrity.ENDORSED, Sensitivity.PERSONAL);

    assertThat(label.toString())
        .contains("tenant=acme")
        .contains("integrity=ENDORSED")
        .contains("sensitivity=PERSONAL");
  }

  @Test
  @DisplayName("says nothing, legibly, when it was told nothing")
  void says_nothing_legibly() {
    assertThat(Label.nothing().toString()).isEqualTo("{}");
  }

  @Test
  @DisplayName("round-trips through storage")
  void round_trips_through_storage() {
    Label label = labelled("acme", Integrity.UNENDORSED, Sensitivity.CARDHOLDER);

    Label read = Label.decode(label.encode(), List.of(TENANT, INTEGRITY, SENSITIVITY));

    assertThat(read).isEqualTo(label);
  }

  /** A mixture is stored honestly, so a row that reaches nobody still reaches nobody tomorrow. */
  @Test
  @DisplayName("round-trips a mixture through storage")
  void round_trips_a_mixture() {
    Label mixed = Label.of(TENANT, "acme").join(Label.of(TENANT, "globex"));

    Label read = Label.decode(mixed.encode(), List.of(TENANT));

    assertThat(read).isEqualTo(mixed);
    assertThat(read.toString()).contains("(mixed)");
  }

  /**
   * Adding an axis does not invalidate what is already written, which is the point of storing names
   * rather than positions.
   *
   * <p>A record's components are positional, so a fourth one makes every blob in the table
   * undecodable. An older row simply says nothing about the new axis, and saying nothing is a thing
   * a label is already able to do.
   */
  @Test
  @DisplayName("still reads a row written before an axis existed")
  void still_reads_a_row_written_before_an_axis_existed() {
    Label written = Label.of(TENANT, "acme").with(INTEGRITY, Integrity.ENDORSED);

    Label read = Label.decode(written.encode(), List.of(TENANT, INTEGRITY, SENSITIVITY));

    assertThat(read).isEqualTo(written);
    assertThat(read.unsaid(SENSITIVITY)).isFalse();
  }

  /**
   * And removing one does, on purpose.
   *
   * <p>This is the direction that is not symmetrical. The row was written under a constraint, and
   * quietly dropping it would make the value readable by more than it was ever labelled for -- a
   * control that stops working without anything failing. A store that has stopped declaring an axis
   * has rows to reckon with, and this is how it finds out.
   */
  @Test
  @DisplayName("refuses a row labelled on an axis the store no longer declares")
  void refuses_a_row_on_an_axis_no_longer_declared() {
    Map<String, String> written =
        labelled("acme", Integrity.ENDORSED, Sensitivity.CARDHOLDER).encode();
    List<Axis<?>> nowDeclared = List.of(TENANT, INTEGRITY);

    assertThatThrownBy(() -> Label.decode(written, nowDeclared))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("sensitivity");
  }

  /** Two labels saying the same things are the same label, whatever order they were built in. */
  @Test
  @DisplayName("does not care what order it was built in")
  void does_not_care_about_build_order() {
    Label oneWay = Label.of(TENANT, "acme").with(SENSITIVITY, Sensitivity.PERSONAL);
    Label theOther = Label.of(SENSITIVITY, Sensitivity.PERSONAL).with(TENANT, "acme");

    assertThat(oneWay).isEqualTo(theOther).hasSameHashCodeAs(theOther);
  }
}
