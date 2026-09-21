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
import static org.assertj.core.api.Assertions.catchThrowable;

import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.jwcarman.loch.lattice.Exact;
import org.jwcarman.loch.lattice.Lattice;
import org.jwcarman.loch.lattice.Lattices;

/**
 * Erasing somebody else's data.
 *
 * <p>Erasure is the one operation a label cannot decide on its own, because destroying a value is
 * not reading it: a compliance officer is expected to remove data they were never entitled to look
 * at. So the policy sees both what is being erased and who is asking, and this is the test that it
 * really sees both. It was written after a version that saw only the who, under which acme's
 * compliance officer could erase globex's records.
 */
@DisplayName("Erasing somebody else's data")
class ErasingAcrossTenantsTest {

  enum Level {
    LOW,
    HIGH
  }

  record Record(String text) {}

  record Labels(Exact<String> tenant, Level level) {
    static final Lattice<Labels> LATTICE =
        Lattices.product(
            Labels::new,
            Lattices.axis(Labels::tenant, Lattices.exact()),
            Lattices.axis(Labels::level, Lattices.ladder(Level.LOW, Level.HIGH)));
  }

  @Test
  @DisplayName("is refused, even for a compliance officer")
  void is_refused_even_for_a_compliance_officer() {
    AtomicReference<AccessContext> edge = new AtomicReference<>(AccessContext.empty());

    LochConfig<Labels, Object> config =
        new LochConfig<Labels, Object>()
            .lattice(Labels.LATTICE)
            .withoutAudit()
            .askingWhoIsAsking(edge::get)
            .mayErase(
                (label, ctx) ->
                    ctx.has("role", "compliance")
                        && label.tenant().resolved().filter(t -> ctx.has("tenant", t)).isPresent());

    Inlet<Record> globexRecords =
        config.inlet(
            "globex-records", Record.class, ctx -> new Labels(Exact.of("globex"), Level.HIGH));

    Loch<Labels> loch = MemoryLoch.create(config);

    Handle<Record> globexRecord = globexRecords.hold(new Record("globex's records"));

    edge.set(AccessContext.of(Map.of("tenant", "acme", "role", "compliance")));

    assertThat(catchThrowable(() -> loch.erase(globexRecord)))
        .isInstanceOf(AccessDeniedException.class);
    assertThat(loch.holds(globexRecord.id())).isTrue();
  }
}
