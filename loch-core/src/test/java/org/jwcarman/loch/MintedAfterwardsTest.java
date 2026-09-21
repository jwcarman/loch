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

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.jwcarman.loch.lattice.Axis;
import org.jwcarman.loch.lattice.Ceiling;
import org.jwcarman.loch.lattice.Constraint;
import org.jwcarman.loch.lattice.Label;

/**
 * A capability minted after its store was built is attached to nothing.
 *
 * <p>This is what makes holding a capability mean anything. The configuration is the mint, so
 * anyone still holding it could otherwise manufacture a source at any label, or a derivation
 * reading anything, long after startup decided what the application was allowed to do.
 *
 * <p>All of these were measured doing exactly that before capabilities were bound individually. A
 * source minted after startup planted a value at another tenant's label; a derivation minted after
 * startup read a cardholder token. The sink failed, but only because the engine happened to have
 * snapshotted its destinations, and an accident is not a control.
 *
 * <p>There is no policy here to misconfigure and no check to switch off. A capability reaches its
 * store through a binding attached when that store is built, so one minted afterwards has nothing
 * to reach.
 */
@DisplayName("A capability minted after the store was built")
class MintedAfterwardsTest {

  private static final SurrogateType<Token> TOKEN_TYPE = SurrogateType.of(Token.class);

  private static final Axis<String> TENANT = Axis.matching("tenant");

  interface Value {}

  record Token(String value) implements Value {}

  private final SurrogateStoreConfig config = new SurrogateStoreConfig().axes(TENANT);

  private final Conceal<Token> acmeTokens =
      config.source("acme-tokens", TOKEN_TYPE, ctx -> Label.of(TENANT, "acme"));

  private final SurrogateStore store = MemorySurrogateStore.create(config);

  private final Surrogate<Token> secret = acmeTokens.conceal(new Token("acme's cardholder token"));

  @Test
  @DisplayName("proves the store itself still works, so the refusals below mean something")
  void the_loch_itself_still_works() {
    assertThat(store.label(secret.id())).isEqualTo(Label.of(TENANT, "acme"));
  }

  @Test
  @DisplayName("cannot be a source planting a value at somebody else's label")
  void cannot_be_a_source() {
    Conceal<Token> forged = config.source("forged", TOKEN_TYPE, ctx -> Label.of(TENANT, "globex"));

    assertThatThrownBy(() -> forged.conceal(new Token("globex owes us 1,000,000")))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("attached to no store");
  }

  @Test
  @DisplayName("cannot be a derivation reading what it was never entitled to")
  void cannot_be_a_derivation() {
    Derivation<Token, Token> forged =
        config
            .derivation("forged", TOKEN_TYPE, TOKEN_TYPE, t -> new Token(t.value()))
            .accepting(ctx -> Ceiling.of(TENANT, Constraint.any()))
            .mint();

    assertThatThrownBy(() -> forged.derive(secret))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("attached to no store");
  }

  @Test
  @DisplayName("cannot be a sink with a ceiling of its own choosing")
  void cannot_be_a_sink() {
    Reveal<Token> forged =
        config.destination("forged", ctx -> Ceiling.nothing(), TOKEN_TYPE).reading(TOKEN_TYPE);

    assertThatThrownBy(() -> forged.reveal(secret))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("attached to no store");
  }

  @Test
  @DisplayName("cannot be a fold either")
  void cannot_be_a_fold() {
    Fold<Token, Token> forged =
        config
            .fold("forged-fold", TOKEN_TYPE, TOKEN_TYPE, all -> all.getFirst())
            .accepting(ctx -> Ceiling.of(TENANT, Constraint.any()))
            .mint();

    assertThatThrownBy(() -> forged.fold(List.of(secret)))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("attached to no store");
  }

  @Test
  @DisplayName("cannot be a query either")
  void cannot_be_a_query() {
    Query<Token, String> forged =
        config
            .query(
                "forged-query",
                TOKEN_TYPE,
                String.class,
                (token, against, ctx) -> token.value().contains(against))
            .accepting(ctx -> Ceiling.nothing())
            .mint();

    assertThatThrownBy(() -> forged.ask(secret, "cardholder"))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("attached to no store");
  }
}
