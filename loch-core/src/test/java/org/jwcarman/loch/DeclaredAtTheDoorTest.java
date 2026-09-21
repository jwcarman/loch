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
import org.jwcarman.loch.lattice.Axis;
import org.jwcarman.loch.lattice.Ceiling;
import org.jwcarman.loch.lattice.Constraint;
import org.jwcarman.loch.lattice.Label;

/**
 * A destination settles both its restrictions when it is declared.
 *
 * <p>The ceiling says which labels may reach it and the type list says what it reads. Neither can
 * be widened afterwards, and that is what makes a reader safe to hand out and safe to throw away: a
 * reader is a typed view of a door, not a grant of anything the door did not already have.
 *
 * <p>Which in turn is what makes the destination itself handable. Give a service the door to the
 * subsystem it talks to and let it take whatever readers it needs. It holds no configuration, so it
 * cannot declare a new door -- only look through this one.
 */
@DisplayName("A destination")
class DeclaredAtTheDoorTest {

  private static final SurrogateType<Card> CARD_TYPE = SurrogateType.of(Card.class);
  private static final SurrogateType<SessionToken> SESSION_TOKEN_TYPE =
      SurrogateType.of(SessionToken.class);

  private static final Axis<String> TENANT = Axis.matching("tenant");

  interface Value {}

  record Card(String number) implements Value {}

  record Last4(String digits) implements Value {}

  record SessionToken(String token) implements Value {}

  private final SurrogateStoreConfig<Value> config = new SurrogateStoreConfig<Value>().axes(TENANT);

  // Declared once, named by the strategy: card, last4, session-token.
  private final SurrogateType<Card> cardType = SurrogateType.of(Card.class);
  private final SurrogateType<Last4> last4Type = SurrogateType.of(Last4.class);
  private final SurrogateType<SessionToken> tokenType = SurrogateType.of(SessionToken.class);

  private final SurrogateSource<Card> cards =
      config.source("cards", CARD_TYPE, ctx -> Label.of(TENANT, "acme"));

  private final SurrogateSource<SessionToken> tokens =
      config.source("tokens", SESSION_TOKEN_TYPE, ctx -> Label.of(TENANT, "acme"));

  private final SurrogateDestination<Value> processor =
      config.destination(
          "payment-processor",
          ctx -> Ceiling.of(TENANT, Constraint.atMost("acme")),
          cardType,
          last4Type);

  private final SurrogateStore store = MemorySurrogateStore.create(config);

  @Test
  @DisplayName("reads the types it was declared to read")
  void reads_the_types_it_was_declared_to_read() {
    Surrogate<Card> card = cards.exchange(new Card("4111111111114821"));

    assertThat(processor.reading(cardType).exchange(card).granted())
        .contains(new Card("4111111111114821"));
  }

  /**
   * The narrowing the ceiling cannot do.
   *
   * <p>A session token stored at the same label as a card is indistinguishable to a ceiling -- same
   * tenant, same everything. The type list is what keeps it away from the payment processor, and it
   * was settled before any reader existed.
   */
  @Test
  @DisplayName("refuses to make a reader for a type it was not declared to read")
  void refuses_a_reader_for_an_undeclared_type() {
    assertThatThrownBy(() -> processor.reading(tokenType))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("does not read")
        // The stored vocabulary, not the Java one: session-token is what it is written down as.
        .hasMessageContaining("session-token")
        .hasMessageContaining("[card, last4]");
  }

  /** Same label, same ceiling, and still out of reach, because the door never read that type. */
  @Test
  @DisplayName("so a value it was never meant to see stays out of reach")
  void a_value_it_was_never_meant_to_see_stays_out_of_reach() {
    Surrogate<SessionToken> token = tokens.exchange(new SessionToken("sess_abc"));

    assertThat(store.label(token.id())).isEqualTo(Label.of(TENANT, "acme"));
    assertThatThrownBy(() -> processor.reading(tokenType))
        .isInstanceOf(IllegalStateException.class);
  }

  @Test
  @DisplayName("has to say what it reads, because a door that reads anything reads everything")
  void has_to_say_what_it_reads() {
    assertThatThrownBy(
            () -> config.destination("vague", ctx -> Ceiling.of(TENANT, Constraint.atMost("acme"))))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("which types it reads");
  }

  /** Readers are views, so taking the same one twice is not taking anything twice. */
  @Test
  @DisplayName("mints readers on demand, because they grant nothing the door did not have")
  void mints_readers_on_demand() {
    Surrogate<Card> card = cards.exchange(new Card("4111111111114821"));

    assertThat(processor.reading(cardType).exchange(card).allowed()).isTrue();
    assertThat(processor.reading(cardType).exchange(card).allowed()).isTrue();
  }
}
