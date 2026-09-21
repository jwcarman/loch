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
 * Writing data at a label you do not own.
 *
 * <p>Every other gate here decides whether a value may be read. This is the one that decides
 * whether it may be written, and the two are different questions: Bell-LaPadula permits a low
 * subject to write a high object it cannot read -- a blind write up -- while Biba forbids it,
 * because creating data more trusted than you are is how a forgery becomes a fact.
 *
 * <p>Without a policy, code acting for one tenant can hold a value labelled as another tenant's
 * endorsed record, and that tenant later reads it as its own authoritative data. Nothing downstream
 * can tell: by then it is correctly labelled.
 */
@DisplayName("Writing at somebody else's label")
class WritingAtAnothersLabelTest {

  enum Integrity {
    ENDORSED,
    UNENDORSED
  }

  record Labels(Exact<String> tenant, Integrity integrity) {
    static final Lattice<Labels> LATTICE =
        Lattices.product(
            Labels::new,
            Lattices.axis(Labels::tenant, Lattices.exact()),
            Lattices.axis(
                Labels::integrity, Lattices.ladder(Integrity.ENDORSED, Integrity.UNENDORSED)));
  }

  static final DestinationId REPORTING = DestinationId.of("reporting");

  private final AtomicReference<AccessContext> edge = new AtomicReference<>(AccessContext.empty());

  private final Loch<Labels> loch =
      MemoryLoch.create(
          c ->
              c.lattice(Labels.LATTICE)
                  .withoutAudit()
                  .askingWhoIsAsking(edge::get)
                  .mayHold(
                      (label, ctx) ->
                          label.tenant().resolved().filter(t -> ctx.has("tenant", t)).isPresent())
                  .destination(
                      Destinations.varying(
                          REPORTING,
                          ctx ->
                              new Labels(
                                  ctx.get("tenant")
                                      .<Exact<String>>map(Exact::of)
                                      .orElseGet(Exact::none),
                                  Integrity.ENDORSED))));

  @Test
  @DisplayName("is refused, so a forgery never becomes somebody else's fact")
  void is_refused() {
    edge.set(AccessContext.of(Map.of("tenant", "acme")));
    Labels asGlobex = new Labels(Exact.of("globex"), Integrity.ENDORSED);

    Throwable thrown =
        catchThrowable(() -> loch.hold("globex owes us 1,000,000", String.class, asGlobex));

    assertThat(thrown).isInstanceOf(AccessDeniedException.class);
  }

  @Test
  @DisplayName("while writing at your own is ordinary")
  void your_own_label_is_ordinary() {
    edge.set(AccessContext.of(Map.of("tenant", "acme")));

    Handle<String> mine =
        loch.hold("our own note", String.class, new Labels(Exact.of("acme"), Integrity.ENDORSED));

    assertThat(loch.holds(mine.id())).isTrue();
    assertThat(loch.dereference(mine, REPORTING).granted()).contains("our own note");
  }
}
