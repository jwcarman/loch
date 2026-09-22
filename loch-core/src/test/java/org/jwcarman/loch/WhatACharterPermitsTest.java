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

  private final DefaultCharter charter = new DefaultCharter(TENANT, SENSITIVITY);

  private final Conceal<Card> cards =
      charter.source(
          "card-intake",
          CARD,
          ctx -> Label.of(TENANT, "acme").with(SENSITIVITY, Sensitivity.PERSONAL));

  private final Reveal<Last4> approvalDesk =
      charter.destination("approval-desk", ctx -> upTo(Sensitivity.ORDINARY), LAST4).reading(LAST4);

  private final Derivation<Card, Last4> truncate =
      charter.derivation(
          "card.last4",
          CARD,
          LAST4,
          card -> new Last4(card.number().substring(12)),
          d ->
              d.accepting(ctx -> upTo(Sensitivity.PERSONAL))
                  .lowering(joined -> joined.with(SENSITIVITY, Sensitivity.ORDINARY)));

  private final Query<Card, String> mentions =
      charter.query(
          "card.startsWith",
          CARD,
          String.class,
          (card, prefix, ctx) -> card.number().startsWith(prefix),
          d -> d.accepting(ctx -> upTo(Sensitivity.PERSONAL)));

  /**
   * A door whose ceiling reads the access cannot be rendered without one.
   *
   * <p>In a multi-tenant application that is every door, so a manifest rendered for nobody reported
   * that it could not evaluate a single ceiling -- the report being useless in exactly the case it
   * exists for. There is no such thing as what a door accepts in general.
   */
  @Test
  @DisplayName("shows a tenant-scoped ceiling when it is rendered for a tenant")
  void shows_a_tenant_scoped_ceiling_for_a_tenant() {
    DefaultCharter scoped = new DefaultCharter(TENANT, SENSITIVITY);
    scoped.destination(
        "reporting",
        ctx ->
            Ceiling.of(TENANT, Constraint.atMost(ctx.get("tenant").orElseThrow()))
                .with(SENSITIVITY, Constraint.atMost(Sensitivity.ORDINARY)),
        LAST4);

    assertThat(scoped.manifest().toString()).contains("could not decide for this access");

    String forAcme =
        scoped.manifest(AccessContext.of(java.util.Map.of("tenant", "acme"))).toString();

    assertThat(forAcme).contains("acme").doesNotContain("could not decide");
  }

  @Test
  @DisplayName("says which access it was rendered for, because the ceilings depend on it")
  void says_which_access_it_was_rendered_for() {
    assertThat(charter.manifest().toString()).contains("nobody in particular");
    assertThat(charter.manifest(AccessContext.of(java.util.Map.of("tenant", "acme"))).toString())
        .contains("tenant=acme");
  }

  /**
   * A question looks like the safe way to use a value, and a reviewer should meet that where they
   * meet the list of questions rather than in a javadoc nobody opens.
   */
  @Test
  @DisplayName("says that questions read a value a bit at a time")
  void says_that_questions_read_a_value_a_bit_at_a_time() {
    assertThat(charter.manifest().toString())
        .contains("enough questions read the value")
        .contains("a ceiling is what limits who may ask");
  }

  /**
   * What an auditor came to find out, and the part of it that is provable.
   *
   * <p>Which types anything can produce, which types anything reads, and whether a path exists
   * between them is a graph over the declarations -- so a door nobody can reach, or one reading a
   * type nothing makes, is a fact rather than a suspicion. The second is usually a rename that went
   * half-applied, and without this it fails as a refusal at request time instead of at startup.
   */
  @Test
  @DisplayName("proves that nothing is unreachable when nothing is")
  void proves_that_nothing_is_unreachable() {
    assertThat(charter.manifest().findings()).isEmpty();
  }

  @Test
  @DisplayName("finds a door that reads a type nothing can produce")
  void finds_a_door_nothing_can_feed() {
    DefaultCharter orphan = new DefaultCharter(TENANT, SENSITIVITY);
    orphan.source("card-intake", CARD, ctx -> Label.of(TENANT, "acme"));
    orphan.destination("desk", upTo(Sensitivity.ORDINARY), LAST4);

    assertThat(orphan.manifest().findings("no-writer"))
        .singleElement()
        .satisfies(
            finding -> {
              assertThat(finding.about()).isEqualTo("desk");
              assertThat(finding.detail()).contains("last4").contains("nothing in this charter");
            });
  }

  @Test
  @DisplayName("finds a source whose values could never be revealed anywhere")
  void finds_a_source_with_nowhere_to_go() {
    DefaultCharter stranded = new DefaultCharter(TENANT, SENSITIVITY);
    stranded.source("card-intake", CARD, ctx -> Label.of(TENANT, "acme"));
    stranded.destination("desk", upTo(Sensitivity.ORDINARY), LAST4);

    assertThat(stranded.manifest().findings("no-reader"))
        .anySatisfy(finding -> assertThat(finding.about()).isEqualTo("card-intake"));
  }

  /** A derivation in the middle is exactly what makes this worth walking rather than assuming. */
  @Test
  @DisplayName("follows a derivation when deciding whether a source has a reader")
  void follows_a_derivation_when_deciding_reachability() {
    assertThat(charter.manifest().findings("no-reader")).isEmpty();
  }

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

  /**
   * A snapshot cannot be changed once anyone can see it.
   *
   * <p>Declaring is one atomic transition over an immutable configuration, which is what stops it
   * racing a seal. That rests on the snapshot really being immutable rather than on every future
   * edit to those methods remembering to copy.
   */
  @Test
  @DisplayName("hands out a view of itself that nothing can change underneath it")
  void hands_out_a_view_nothing_can_change() {
    Manifest before = charter.manifest();

    assertThat(before.destinations()).isNotEmpty();
    org.assertj.core.api.Assertions.assertThatThrownBy(() -> before.destinations().clear())
        .isInstanceOf(UnsupportedOperationException.class);
    assertThat(charter.manifest().destinations()).hasSameSizeAs(before.destinations());
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
