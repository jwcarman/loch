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

import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.jwcarman.loch.lattice.Lattices;

/**
 * Identity is known at the edge and needed at the gate, which may be many layers down.
 *
 * <p>Threading an {@code AccessContext} through all of them would make the safety feature the most
 * annoying thing in the codebase, and annoying safety features get routed around.
 */
@DisplayName("Who is asking")
class AmbientContextTest {

  enum Clearance {
    NONE,
    FINANCE
  }

  private final AtomicReference<String> currentUser = new AtomicReference<>("support");

  /**
   * One store, its source and its sink, built together.
   *
   * <p>Capabilities are attached when the store is built, so they have to be minted first. A record
   * keeps the three together without every test repeating the order.
   */
  record Wired(
      SurrogateStore<Clearance> store, SurrogateSource<String> cards, SurrogateSink<String> card) {}

  private static Wired wire(
      java.util.function.Consumer<SurrogateStoreConfig<Clearance, Object>> settings,
      java.util.function.Function<AccessContext, Clearance> ceiling) {
    SurrogateStoreConfig<Clearance, Object> config = new SurrogateStoreConfig<>();
    config.lattice(Lattices.ladder(Clearance.NONE, Clearance.FINANCE));
    settings.accept(config);
    SurrogateSource<String> cards = config.source("cards", String.class, ctx -> Clearance.FINANCE);
    SurrogateSink<String> card = config.sink("card", String.class, ceiling);
    return new Wired(MemorySurrogateStore.create(config), cards, card);
  }

  // Said once. A ThreadLocal, a ScopedValue, a SecurityContextHolder -- SurrogateStore does not
  // care
  // where the answer lives.
  private final Wired wired =
      wire(
          c -> c.askingWhoIsAsking(() -> AccessContext.of("clearance", currentUser.get())),
          ctx -> ctx.has("clearance", "finance") ? Clearance.FINANCE : Clearance.NONE);

  private Surrogate<String> last4() {
    return wired.cards().exchange("4821");
  }

  @Test
  @DisplayName("comes from the edge, with no context threaded through the call")
  void comes_from_the_edge() {
    Surrogate<String> value = last4();

    currentUser.set("finance");
    assertThat(wired.card().exchange(value).granted()).contains("4821");

    currentUser.set("support");
    assertThat(wired.card().exchange(value).allowed()).isFalse();
  }

  @Test
  @DisplayName("and a caller with something to add adds it rather than replacing everything")
  void a_caller_adds_rather_than_replaces() {
    currentUser.set("finance");
    AtomicReference<AccessContext> seen = new AtomicReference<>();
    Wired watching =
        wire(
            c ->
                c.askingWhoIsAsking(() -> AccessContext.of("clearance", currentUser.get()))
                    .callerMayContribute("purpose"),
            ctx -> {
              seen.set(ctx);
              return Clearance.FINANCE;
            });
    Surrogate<String> value = watching.cards().exchange("4821");

    watching.card().exchange(value, AccessContext.of("purpose", "refund"));

    assertThat(seen.get().attributes())
        .containsEntry("clearance", "finance")
        .containsEntry("purpose", "refund");
  }

  /** A call site is not entitled to revise what the edge established about who is asking. */
  @Test
  @DisplayName("but a caller cannot promote itself by claiming a better clearance")
  void a_caller_cannot_promote_itself() {
    currentUser.set("support");
    AtomicReference<AccessContext> seen = new AtomicReference<>();
    Wired watching =
        wire(
            c ->
                c.askingWhoIsAsking(() -> AccessContext.of("clearance", currentUser.get()))
                    .callerMayContribute("purpose", "clearance"),
            ctx -> {
              seen.set(ctx);
              return ctx.has("clearance", "finance") ? Clearance.FINANCE : Clearance.NONE;
            });
    Surrogate<String> value = watching.cards().exchange("4821");

    var claimed = watching.card().exchange(value, AccessContext.of("clearance", "finance"));

    assertThat(seen.get().attributes()).containsEntry("clearance", "support");
    assertThat(claimed.allowed()).isFalse();
  }

  /** And a key the application never allowed is ignored entirely. */
  @Test
  @DisplayName("a caller contributing an undeclared key is ignored")
  void an_undeclared_key_is_ignored() {
    AtomicReference<AccessContext> seen = new AtomicReference<>();
    Wired watching =
        wire(
            c -> c.askingWhoIsAsking(AccessContext::empty),
            ctx -> {
              seen.set(ctx);
              return Clearance.FINANCE;
            });

    watching
        .card()
        .exchange(watching.cards().exchange("x"), AccessContext.of("tenant", "whatever-i-like"));

    assertThat(seen.get().attributes()).isEmpty();
  }

  @Test
  @DisplayName("an application with no notion of identity says nothing and gets nothing")
  void no_identity_means_empty() {
    AtomicReference<AccessContext> seen = new AtomicReference<>();
    Wired anonymous =
        wire(
            c -> {},
            ctx -> {
              seen.set(ctx);
              return Clearance.FINANCE;
            });

    anonymous.card().exchange(anonymous.cards().exchange("x"));

    assertThat(seen.get().attributes()).isEmpty();
  }
}
