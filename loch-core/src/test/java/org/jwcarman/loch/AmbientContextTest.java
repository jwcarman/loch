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

import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.jwcarman.loch.lattice.Lattice;
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

  static final DestinationId CARD = DestinationId.of("card");

  private final AtomicReference<String> currentUser = new AtomicReference<>("support");

  private final Loch<Clearance> loch =
      MemoryLoch.create(
          c ->
              c.lattice(Lattices.ladder(Clearance.NONE, Clearance.FINANCE))
                  .withoutAudit()
                  // Said once. A ThreadLocal, a ScopedValue, a SecurityContextHolder -- Loch does
                  // not care where the answer lives.
                  .askingWhoIsAsking(() -> AccessContext.of("clearance", currentUser.get()))
                  .destination(
                      Destinations.varying(
                          CARD,
                          ctx ->
                              ctx.has("clearance", "finance")
                                  ? Clearance.FINANCE
                                  : Clearance.NONE)));

  private Held<String> last4() {
    return loch.hold("4821", String.class, Clearance.FINANCE);
  }

  @Test
  @DisplayName("comes from the edge, with no context threaded through the call")
  void comes_from_the_edge() {
    Held<String> value = last4();

    currentUser.set("finance");
    assertThat(loch.dereference(value, CARD).granted()).contains("4821");

    currentUser.set("support");
    assertThat(loch.dereference(value, CARD).allowed()).isFalse();
  }

  @Test
  @DisplayName("and a caller with something to add adds it rather than replacing everything")
  void a_caller_adds_rather_than_replaces() {
    currentUser.set("finance");
    AtomicReference<AccessContext> seen = new AtomicReference<>();
    Loch<Clearance> watching =
        MemoryLoch.create(
            c ->
                c.lattice(Lattices.ladder(Clearance.NONE, Clearance.FINANCE))
                    .withoutAudit()
                    .askingWhoIsAsking(() -> AccessContext.of("clearance", currentUser.get()))
                    .destination(
                        Destinations.varying(
                            CARD,
                            ctx -> {
                              seen.set(ctx);
                              return Clearance.FINANCE;
                            })));
    Held<String> value = watching.hold("4821", String.class, Clearance.FINANCE);

    watching.dereference(value, CARD, AccessContext.of("purpose", "refund"));

    assertThat(seen.get().attributes())
        .containsEntry("clearance", "finance")
        .containsEntry("purpose", "refund");
  }

  @Test
  @DisplayName("and the caller wins where both say something about the same thing")
  void the_caller_wins_on_a_conflict() {
    AccessContext ambient = AccessContext.of(Map.of("tenant", "acme", "clearance", "support"));
    AccessContext explicit = AccessContext.of("clearance", "finance");

    assertThat(explicit.over(ambient).attributes())
        .containsEntry("tenant", "acme")
        .containsEntry("clearance", "finance");
  }

  @Test
  @DisplayName("an application with no notion of identity says nothing and gets nothing")
  void no_identity_means_empty() {
    Lattice<Clearance> lattice = Lattices.ladder(Clearance.NONE, Clearance.FINANCE);
    AtomicReference<AccessContext> seen = new AtomicReference<>();
    Loch<Clearance> anonymous =
        MemoryLoch.create(
            c ->
                c.lattice(lattice)
                    .withoutAudit()
                    .destination(
                        Destinations.varying(
                            CARD,
                            ctx -> {
                              seen.set(ctx);
                              return Clearance.FINANCE;
                            })));

    anonymous.dereference(anonymous.hold("x", String.class, Clearance.NONE), CARD);

    assertThat(seen.get().attributes()).isEmpty();
  }
}
