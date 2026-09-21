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
import org.jwcarman.loch.lattice.Axis;
import org.jwcarman.loch.lattice.Ceiling;
import org.jwcarman.loch.lattice.Constraint;
import org.jwcarman.loch.lattice.Label;

/**
 * Identity is known at the edge and needed at the gate, which may be many layers down.
 *
 * <p>Threading an {@code AccessContext} through all of them would make the safety feature the most
 * annoying thing in the codebase, and annoying safety features get routed around.
 */
@DisplayName("Who is asking")
class AmbientContextTest {

  private static final SurrogateType<String> STRING_TYPE = SurrogateType.of(String.class);

  enum Clearance {
    NONE,
    FINANCE
  }

  private static final Axis<Clearance> CLEARANCE =
      Axis.ladder("clearance", Clearance.NONE, Clearance.FINANCE);

  private final AtomicReference<String> currentUser = new AtomicReference<>("support");

  /**
   * One store, its source and its sink, built together.
   *
   * <p>Capabilities are attached when the store is built, so they have to be minted first. A record
   * keeps the three together without every test repeating the order.
   */
  record Wired(SurrogateStore store, Conceal<String> cards, Reveal<String> card) {}

  private static Wired wire(
      java.util.function.Consumer<SurrogateStoreConfig> settings,
      java.util.function.Function<AccessContext, Clearance> ceiling) {
    SurrogateStoreConfig config = new SurrogateStoreConfig();
    config.axes(CLEARANCE);
    settings.accept(config);
    Conceal<String> cards =
        config.source("cards", STRING_TYPE, ctx -> Label.of(CLEARANCE, Clearance.FINANCE));
    Reveal<String> card =
        config
            .destination(
                "card",
                ctx -> Ceiling.of(CLEARANCE, Constraint.atMost(ceiling.apply(ctx))),
                STRING_TYPE)
            .reading(STRING_TYPE);
    return new Wired(MemorySurrogateStore.create(config), cards, card);
  }

  // Said once. A ThreadLocal, a ScopedValue, a SecurityContextHolder -- SurrogateStore does not
  // care
  // where the answer lives.
  private final Wired wired =
      wire(
          c -> c.currentAccess(() -> AccessContext.of("clearance", currentUser.get())),
          ctx -> ctx.has("clearance", "finance") ? Clearance.FINANCE : Clearance.NONE);

  private Surrogate<String> last4() {
    return wired.cards().conceal("4821");
  }

  @Test
  @DisplayName("comes from the edge, with no context threaded through the call")
  void comes_from_the_edge() {
    Surrogate<String> value = last4();

    currentUser.set("finance");
    assertThat(wired.card().reveal(value).granted()).contains("4821");

    currentUser.set("support");
    assertThat(wired.card().reveal(value).allowed()).isFalse();
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

    anonymous.card().reveal(anonymous.cards().conceal("x"));

    assertThat(seen.get().attributes()).isEmpty();
  }
}
