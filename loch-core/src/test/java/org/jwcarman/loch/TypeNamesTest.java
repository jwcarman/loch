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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.jwcarman.loch.lattice.Exact;
import org.jwcarman.loch.lattice.Lattices;

/**
 * A stored name has to identify one type.
 *
 * <p>The name is what gets written beside a value and compared when one is read back. Two types
 * sharing a name means a reader is handed the wrong one, and the failure surfaces as a decode error
 * somewhere far from the cause. Finding it at startup is the whole point.
 *
 * <p>The check lives at the point of use rather than at construction, so it applies however the
 * type was declared -- by the configuration, or written out by hand.
 */
@DisplayName("A stored type name")
class TypeNamesTest {

  private static final SurrogateType<Card> CARD_TYPE = SurrogateType.of(Card.class);
  private static final SurrogateType<Invoice> INVOICE_TYPE = SurrogateType.of(Invoice.class);

  interface Value {}

  record Card(String number) implements Value {}

  record Invoice(String number) implements Value {}

  @SurrogateName("billing.mail/v2")
  record Mail(String body) implements Value {}

  record DisputeClaim(String reason) implements Value {}

  private SurrogateStoreConfig<Exact<String>, Value> config() {
    return new SurrogateStoreConfig<Exact<String>, Value>().lattice(Lattices.exact());
  }

  @Test
  @DisplayName("comes from the kebab-cased simple name when nobody says otherwise")
  void comes_from_the_kebab_cased_simple_name() {
    var c = config();

    assertThat(SurrogateType.of(Card.class).name()).isEqualTo("card");
    assertThat(SurrogateType.of(DisputeClaim.class).name()).isEqualTo("dispute-claim");
  }

  @Test
  @DisplayName("comes from the annotation when there is one")
  void comes_from_the_annotation_when_there_is_one() {
    assertThat(SurrogateType.of(Mail.class).name()).isEqualTo("billing.mail/v2");
  }

  @Test
  @DisplayName("comes from the caller when they say it, over both")
  void comes_from_the_caller_over_both() {
    assertThat(SurrogateType.of("billing.mail/v3", Mail.class).name()).isEqualTo("billing.mail/v3");
  }

  /** The check the short default needs in order to be safe. */
  /**
   * The check the short default needs in order to be safe.
   *
   * <p>It lives where a type reaches a portal, not in a constructor, because types are written out
   * by hand and there is nothing to hook. Every type reaches a portal eventually, so every type
   * gets checked.
   */
  @Test
  @DisplayName("cannot mean two different types")
  void cannot_mean_two_different_types() {
    var c = config();
    c.source("cards", SurrogateType.of("thing", Card.class), ctx -> Exact.of("acme"));

    assertThatThrownBy(
            () ->
                c.source(
                    "invoices", SurrogateType.of("thing", Invoice.class), ctx -> Exact.of("acme")))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("both want the name 'thing'");
  }

  @Test
  @DisplayName("said twice for the same type is not saying two things")
  void said_twice_for_the_same_type_is_fine() {
    var c = config();

    assertThat(SurrogateType.of("thing", Card.class).name()).isEqualTo("thing");
    assertThat(SurrogateType.of("thing", Card.class).name()).isEqualTo("thing");
  }
}
