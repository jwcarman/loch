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
import org.junit.jupiter.api.Test;
import org.jwcarman.loch.lattice.Exact;
import org.jwcarman.loch.lattice.Lattice;
import org.jwcarman.loch.lattice.Lattices;

/** Who may erase is not enough; a policy has to see what is about to be destroyed. */
@org.junit.jupiter.api.DisplayName("Erasing somebody else's data")
class ErasingAcrossTenantsTest {

  enum Level {
    LOW,
    HIGH
  }

  record Labels(Exact<String> tenant, Level level) {
    static final Lattice<Labels> LATTICE =
        Lattices.product(
            Labels::new,
            Lattices.axis(Labels::tenant, Lattices.exact()),
            Lattices.axis(Labels::level, Lattices.ladder(Level.LOW, Level.HIGH)));
  }

  @Test
  @org.junit.jupiter.api.DisplayName("is refused, even for a compliance officer")
  void is_refused_even_for_a_compliance_officer() {
    AtomicReference<AccessContext> edge = new AtomicReference<>(AccessContext.empty());
    Loch<Labels> loch =
        MemoryLoch.create(
            c ->
                c.lattice(Labels.LATTICE)
                    .withoutAudit()
                    .askingWhoIsAsking(edge::get)
                    .mayErase(
                        (label, ctx) ->
                            ctx.has("role", "compliance")
                                && label
                                    .tenant()
                                    .resolved()
                                    .filter(t -> ctx.has("tenant", t))
                                    .isPresent()));

    Handle<String> globexRecord =
        loch.hold("globex's records", String.class, new Labels(Exact.of("globex"), Level.HIGH));

    edge.set(AccessContext.of(Map.of("tenant", "acme", "role", "compliance")));
    assertThat(org.assertj.core.api.Assertions.catchThrowable(() -> loch.erase(globexRecord)))
        .isInstanceOf(AccessDeniedException.class);
    assertThat(loch.holds(globexRecord.id())).isTrue();
  }
}
