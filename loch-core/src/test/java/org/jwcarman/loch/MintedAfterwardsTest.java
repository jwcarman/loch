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
import org.jwcarman.loch.lattice.Exact;
import org.jwcarman.loch.lattice.Lattices;

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

  interface Value {}

  record Token(String value) implements Value {}

  private final SurrogateStoreConfig<Exact<String>, Value> config =
      new SurrogateStoreConfig<Exact<String>, Value>().lattice(Lattices.exact());

  private final SurrogateSource<Token> acmeTokens =
      config.source("acme-tokens", Token.class, ctx -> Exact.of("acme"));

  private final SurrogateStore<Exact<String>> store = MemorySurrogateStore.create(config);

  private final Surrogate<Token> secret = acmeTokens.exchange(new Token("acme's cardholder token"));

  @Test
  @DisplayName("proves the store itself still works, so the refusals below mean something")
  void the_loch_itself_still_works() {
    assertThat(store.label(secret.id())).isEqualTo(Exact.of("acme"));
  }

  @Test
  @DisplayName("cannot be a source planting a value at somebody else's label")
  void cannot_be_a_source() {
    SurrogateSource<Token> forged = config.source("forged", Token.class, ctx -> Exact.of("globex"));

    assertThatThrownBy(() -> forged.exchange(new Token("globex owes us 1,000,000")))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("attached to no store");
  }

  @Test
  @DisplayName("cannot be a derivation reading what it was never entitled to")
  void cannot_be_a_derivation() {
    Derivation<Token, Token> forged =
        config
            .derivation("forged", Token.class, Token.class, t -> new Token(t.value()))
            .acceptingAnything()
            .mint();

    assertThatThrownBy(() -> forged.derive(secret))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("attached to no store");
  }

  @Test
  @DisplayName("cannot be a sink with a ceiling of its own choosing")
  void cannot_be_a_sink() {
    SurrogateSink<Token> forged = config.sink("forged", Token.class, ctx -> Exact.conflict());

    assertThatThrownBy(() -> forged.exchange(secret))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("attached to no store");
  }

  @Test
  @DisplayName("cannot be a fold either")
  void cannot_be_a_fold() {
    Fold<Token, Token> forged =
        config
            .fold("forged-fold", Token.class, Token.class, all -> all.getFirst())
            .acceptingAnything()
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
                Token.class,
                String.class,
                (token, against, ctx) -> token.value().contains(against))
            .accepting(ctx -> Exact.conflict())
            .mint();

    assertThatThrownBy(() -> forged.ask(secret, "cardholder"))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("attached to no store");
  }
}
