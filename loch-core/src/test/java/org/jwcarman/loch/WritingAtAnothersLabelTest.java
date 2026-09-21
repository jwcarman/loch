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
import org.jwcarman.loch.lattice.Exact;
import org.jwcarman.loch.lattice.Lattice;
import org.jwcarman.loch.lattice.Lattices;

/**
 * Writing at somebody else's label, which used to be refused and is now unsayable.
 *
 * <p>This test was written against a policy. Code acting for acme called {@code hold} with a label
 * naming globex, and {@code mayHold} turned it away. Both are gone: nothing in the API takes a
 * label, so the forgery has no way to be expressed, and the policy that caught it has nothing left
 * to police.
 *
 * <p>What replaced it is stronger and is what these tests now assert. A source carries its own
 * label, decided when it was minted, and reads the tenant from the access rather than the caller.
 * Two tenants using the same source get two different labels and neither of them chose.
 */
@DisplayName("Writing at somebody else's label")
class WritingAtAnothersLabelTest {

  enum Integrity {
    ENDORSED,
    UNENDORSED
  }

  interface Value {}

  record Note(String text) implements Value {}

  record Labels(Exact<String> tenant, Integrity integrity) {
    static final Lattice<Labels> LATTICE =
        Lattices.product(
            Labels::new,
            Lattices.axis(Labels::tenant, Lattices.exact()),
            Lattices.axis(
                Labels::integrity, Lattices.ladder(Integrity.ENDORSED, Integrity.UNENDORSED)));
  }

  private final AtomicReference<AccessContext> edge = new AtomicReference<>(AccessContext.empty());

  private final LochConfig<Labels, Value> config =
      new LochConfig<Labels, Value>()
          .lattice(Labels.LATTICE)
          .withoutAudit()
          .askingWhoIsAsking(edge::get);

  /** One source, used by whoever is acting. It is the access that decides, never the caller. */
  private final Inlet<Note> notes =
      config.inlet(
          "notes",
          Note.class,
          ctx ->
              new Labels(
                  ctx.get("tenant").<Exact<String>>map(Exact::of).orElseGet(Exact::none),
                  Integrity.ENDORSED));

  private final Outlet<Note> reporting =
      config.outlet(
          "reporting",
          Note.class,
          ctx ->
              new Labels(
                  ctx.get("tenant").<Exact<String>>map(Exact::of).orElseGet(Exact::none),
                  Integrity.UNENDORSED));

  private final Loch<Labels> loch = MemoryLoch.create(config);

  @Test
  @DisplayName("is refused, so a forgery never becomes somebody else's fact")
  void is_refused() {
    edge.set(AccessContext.of(Map.of("tenant", "acme")));

    Surrogate<Note> written = notes.exchange(new Note("globex owes us 1,000,000"));

    // Acme wrote it and acme owns it. There was no argument through which to claim otherwise.
    assertThat(loch.label(written.id()).tenant()).isEqualTo(Exact.of("acme"));

    // And globex does not read it as its own.
    edge.set(AccessContext.of(Map.of("tenant", "globex")));
    assertThat(reporting.exchange(written).allowed()).isFalse();
  }

  @Test
  @DisplayName("while writing at your own is ordinary")
  void your_own_label_is_ordinary() {
    edge.set(AccessContext.of(Map.of("tenant", "acme")));

    Surrogate<Note> mine = notes.exchange(new Note("our own note"));

    assertThat(loch.holds(mine.id())).isTrue();
    assertThat(reporting.exchange(mine).granted()).contains(new Note("our own note"));
  }
}
