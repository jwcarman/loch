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
 * Nothing can be declared after a charter is sealed.
 *
 * <p>This is what makes holding a portal mean anything. A charter constitutes authority, so anyone
 * still holding one could otherwise manufacture a door at any label, or a derivation reading
 * anything, long after startup decided what the application was allowed to do.
 *
 * <p>All of these were measured doing exactly that before the lifecycle existed. A door declared
 * after startup planted a value at another tenant's label; a derivation declared after startup read
 * a cardholder token.
 *
 * <p>It used to be the <i>use</i> that failed, because a late portal was attached to nothing. Now
 * the declaration fails, which is the same property found one step earlier: at startup, rather than
 * at whichever request first reached the forged portal.
 */
@DisplayName("A charter that has been sealed")
class MintedAfterwardsTest {

  private static final SurrogateType<Token> TOKEN_TYPE = SurrogateType.of(Token.class);

  private static final Axis<String> TENANT = Axis.matching("tenant");

  interface Value {}

  record Token(String value) implements Value {}

  private final DefaultCharter config = new DefaultCharter(TENANT);

  private final Conceal<Token> acmeTokens =
      config.source("acme-tokens", TOKEN_TYPE, ctx -> Label.of(TENANT, "acme"));

  {
    config.seal(new MemoryStorage());
  }

  private final Surrogate<Token> secret = acmeTokens.conceal(new Token("acme's cardholder token"));

  @Test
  @DisplayName("still works, so the refusals below mean something")
  void the_charter_itself_still_works() {
    assertThat(config.label(secret.id())).isEqualTo(Label.of(TENANT, "acme"));
  }

  @Test
  @DisplayName("cannot be a source planting a value at somebody else's label")
  void cannot_be_a_source() {
    assertThatThrownBy(() -> config.source("forged", TOKEN_TYPE, ctx -> Label.of(TENANT, "globex")))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("has been sealed");
  }

  @Test
  @DisplayName("cannot be a derivation reading what it was never entitled to")
  void cannot_be_a_derivation() {
    assertThatThrownBy(
            () ->
                config.derivation(
                    "forged",
                    TOKEN_TYPE,
                    TOKEN_TYPE,
                    t -> new Token(t.value()),
                    d -> d.accepting(ctx -> Ceiling.of(TENANT, Constraint.any()))))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("has been sealed");
  }

  @Test
  @DisplayName("cannot be a sink with a ceiling of its own choosing")
  void cannot_be_a_sink() {
    assertThatThrownBy(
            () ->
                config
                    .destination("forged", ctx -> Ceiling.nothing(), TOKEN_TYPE)
                    .reading(TOKEN_TYPE))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("has been sealed");
  }

  @Test
  @DisplayName("cannot be a fold either")
  void cannot_be_a_fold() {
    assertThatThrownBy(
            () ->
                config.fold(
                    "forged-fold",
                    TOKEN_TYPE,
                    TOKEN_TYPE,
                    all -> all.getFirst(),
                    d -> d.accepting(ctx -> Ceiling.of(TENANT, Constraint.any()))))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("has been sealed");
  }

  @Test
  @DisplayName("cannot be a query either")
  void cannot_be_a_query() {
    assertThatThrownBy(
            () ->
                config.query(
                    "forged-query",
                    TOKEN_TYPE,
                    String.class,
                    (token, against, ctx) -> token.value().contains(against),
                    d -> d.accepting(ctx -> Ceiling.nothing())))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("has been sealed");
  }
}
