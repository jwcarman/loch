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
import org.jwcarman.loch.lattice.Axis;
import org.jwcarman.loch.lattice.Ceiling;
import org.jwcarman.loch.lattice.Constraint;
import org.jwcarman.loch.lattice.Label;

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

  private static final SurrogateType<Record> RECORD_TYPE = SurrogateType.of(Record.class);

  enum Level {
    LOW,
    HIGH
  }

  record Record(String text) {}

  private static final Axis<String> TENANT = Axis.matching("tenant");
  private static final Axis<Level> LEVEL = Axis.ladder("level", Level.LOW, Level.HIGH);

  @Test
  @DisplayName("is refused, even for a compliance officer")
  void is_refused_even_for_a_compliance_officer() {
    AtomicReference<AccessContext> edge = new AtomicReference<>(AccessContext.empty());

    SurrogateStoreConfig<Object> config =
        new SurrogateStoreConfig<Object>()
            .axes(TENANT, LEVEL)
            .currentAccess(edge::get)
            .mayErase(
                (label, ctx) ->
                    ctx.has("role", "compliance")
                        && ctx.get("tenant")
                            .map(
                                tenant ->
                                    Ceiling.of(TENANT, Constraint.atMost(tenant))
                                        .with(LEVEL, Constraint.any())
                                        .permits(label))
                            .orElse(false));

    SurrogateSource<Record> globexRecords =
        config.source(
            "globex-records",
            RECORD_TYPE,
            ctx -> Label.of(TENANT, "globex").with(LEVEL, Level.HIGH));

    SurrogateStore store = MemorySurrogateStore.create(config);

    Surrogate<Record> globexRecord = globexRecords.exchange(new Record("globex's records"));

    edge.set(AccessContext.of(Map.of("tenant", "acme", "role", "compliance")));

    assertThat(catchThrowable(() -> store.erase(globexRecord)))
        .isInstanceOf(AccessDeniedException.class);
    assertThat(store.holds(globexRecord.id())).isTrue();
  }
}
