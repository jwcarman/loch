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

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.jwcarman.loch.lattice.Axis;
import org.jwcarman.loch.lattice.Ceiling;
import org.jwcarman.loch.lattice.Constraint;
import org.jwcarman.loch.lattice.Label;

/**
 * What a charter permits is answerable without a database.
 *
 * <p>Not a convenience. It is what lets a build render the manifest, diff one release against the
 * last, and fail on a change nobody meant to make -- in a unit test, with no container, from a
 * charter that was never sealed to anything.
 *
 * <p>Everything here runs against a charter in its configuring state. There is no storage in this
 * file at all, which is the point of it.
 */
@DisplayName("What a charter permits")
class WhatACharterPermitsTest {

  private enum Sensitivity {
    ORDINARY,
    PERSONAL
  }

  private record Card(String number) {}

  private record Last4(String digits) {}

  private static final Axis<String> TENANT = Axis.matching("tenant");
  private static final Axis<Sensitivity> SENSITIVITY =
      Axis.ladder("sensitivity", Sensitivity.ORDINARY, Sensitivity.PERSONAL);

  private static final SurrogateType<Card> CARD = SurrogateType.of(Card.class);
  private static final SurrogateType<Last4> LAST4 = SurrogateType.of(Last4.class);

  private static Ceiling upTo(Sensitivity sensitivity) {
    return Ceiling.of(TENANT, Constraint.any()).with(SENSITIVITY, Constraint.atMost(sensitivity));
  }

  private final Charter charter = new Charter(TENANT, SENSITIVITY);

  private final Conceal<Card> cards =
      charter.source(
          "card-intake",
          CARD,
          ctx -> Label.of(TENANT, "acme").with(SENSITIVITY, Sensitivity.PERSONAL));

  private final Reveal<Last4> approvalDesk =
      charter.destination("approval-desk", ctx -> upTo(Sensitivity.ORDINARY), LAST4).reading(LAST4);

  private final Derivation<Card, Last4> truncate =
      charter
          .derivation("card.last4", CARD, LAST4, card -> new Last4(card.number().substring(12)))
          .accepting(ctx -> upTo(Sensitivity.PERSONAL))
          .lowering(joined -> joined.with(SENSITIVITY, Sensitivity.ORDINARY))
          .mint();

  private final Query<Card, String> mentions =
      charter
          .query(
              "card.startsWith",
              CARD,
              String.class,
              (card, prefix, ctx) -> card.number().startsWith(prefix))
          .accepting(ctx -> upTo(Sensitivity.PERSONAL))
          .mint();

  @Test
  @DisplayName("is answerable before it has been sealed to anything")
  void is_answerable_before_sealing() {
    assertThat(charter.sealed()).isFalse();

    assertThat(charter.manifest()).isNotNull();
  }

  @Test
  @DisplayName("names every door and what it accepts")
  void names_every_door() {
    Manifest manifest = charter.manifest();

    assertThat(manifest.destinations()).isNotEmpty();
    assertThat(manifest.destinations())
        .anySatisfy(
            entry -> {
              assertThat(entry.name()).isEqualTo("approval-desk");
              assertThat(entry.detail()).contains("sensitivity=ORDINARY");
            });
  }

  /** The question an auditor actually asks, and it is answered without reading a single value. */
  @Test
  @DisplayName("singles out the operations that weaken a label")
  void singles_out_what_weakens() {
    Manifest manifest = charter.manifest();

    assertThat(manifest.weakening()).isNotEmpty();
    assertThat(manifest.weakening()).extracting(Manifest.Entry::name).containsExactly("card.last4");
  }

  @Test
  @DisplayName("says what each derivation reads and produces")
  void says_what_a_derivation_reads() {
    Manifest manifest = charter.manifest();

    assertThat(manifest.derivations()).isNotEmpty();
    assertThat(manifest.derivations())
        .anySatisfy(
            entry -> {
              assertThat(entry.name()).isEqualTo("card.last4");
              assertThat(entry.detail()).isEqualTo("card -> last4");
            });
  }

  /** Queries were built into the manifest and never added to it, so nobody could see them. */
  @Test
  @DisplayName("lists the questions that may be asked, which it once silently omitted")
  void lists_the_questions() {
    Manifest manifest = charter.manifest();

    assertThat(manifest.questions()).isNotEmpty();
    assertThat(manifest.questions())
        .anySatisfy(
            entry -> {
              assertThat(entry.name()).isEqualTo("card.startsWith");
              assertThat(entry.detail()).contains("card");
            });
  }

  /** Portals exist and are inert: the charter can be read, but nothing it made can act. */
  @Test
  @DisplayName("does not let anything it constituted act until it is sealed")
  void nothing_acts_before_sealing() {
    org.assertj.core.api.Assertions.assertThatThrownBy(
            () -> cards.conceal(new Card("4111111111114821")))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("before its charter is sealed");
    org.assertj.core.api.Assertions.assertThatThrownBy(() -> truncate.derive(Surrogate.of("sur_x")))
        .isInstanceOf(IllegalStateException.class);
    org.assertj.core.api.Assertions.assertThatThrownBy(
            () -> approvalDesk.reveal(Surrogate.of("sur_x")))
        .isInstanceOf(IllegalStateException.class);
    org.assertj.core.api.Assertions.assertThatThrownBy(
            () -> mentions.ask(Surrogate.of("sur_x"), "4111"))
        .isInstanceOf(IllegalStateException.class);
  }
}
