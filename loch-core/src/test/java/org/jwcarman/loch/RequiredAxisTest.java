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

import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.jwcarman.loch.lattice.Exact;
import org.jwcarman.loch.lattice.Lattice;
import org.jwcarman.loch.lattice.Lattices;

/**
 * An axis that has to be said.
 *
 * <p>Bottom means "constrains nothing". On an exact-match axis that is also what "nobody said"
 * looks like, and the two readings are opposites: a value whose tenant nobody set is not private to
 * nobody, it is readable by everybody, because bottom sits below every ceiling.
 *
 * <p>This was measured before the axis could be marked. A request arriving with no tenant header
 * wrote a value labelled {@code none}, and both acme and globex could read it. The natural thing to
 * write -- {@code orElseGet(Exact::none)} for a missing tenant -- was the leak.
 */
@DisplayName("A required axis")
class RequiredAxisTest {

  enum Level {
    LOW,
    HIGH
  }

  record Labels(Exact<String> tenant, Level level) {
    /** The tenant has to be said. The level has a meaningful bottom and does not. */
    static final Lattice<Labels> LATTICE =
        Lattices.product(
            Labels::new,
            Lattices.axis(Labels::tenant, Lattices.<String>exact()).required(),
            Lattices.axis(Labels::level, Lattices.ladder(Level.LOW, Level.HIGH)));
  }

  record Note(String text) {}

  private final AtomicReference<AccessContext> edge = new AtomicReference<>(AccessContext.empty());

  private final LochConfig<Labels, Object> config =
      new LochConfig<Labels, Object>().lattice(Labels.LATTICE).askingWhoIsAsking(edge::get);

  /** Exactly what an application would naturally write, including the part that was the leak. */
  private final SurrogateSource<Note> notes =
      config.source(
          "notes",
          Note.class,
          ctx ->
              new Labels(
                  ctx.get("tenant").<Exact<String>>map(Exact::of).orElseGet(Exact::none),
                  Level.HIGH));

  private final SurrogateSink<Note> reporting =
      config.sink(
          "reporting",
          Note.class,
          ctx ->
              new Labels(
                  ctx.get("tenant").<Exact<String>>map(Exact::of).orElseGet(Exact::none),
                  Level.HIGH));

  private final Loch<Labels> loch = MemoryLoch.create(config);

  @Test
  @DisplayName("is written when it was said")
  void is_written_when_it_was_said() {
    edge.set(AccessContext.of(Map.of("tenant", "acme")));

    Surrogate<Note> note = notes.exchange(new Note("ours"));

    assertThat(loch.label(note.id()).tenant()).isEqualTo(Exact.of("acme"));
  }

  /** The whole point: a value nobody can attribute is a value everybody can read. */
  @Test
  @DisplayName("refuses the write when it was not, rather than storing something anyone can read")
  void refuses_the_write_when_it_was_not_said() {
    edge.set(AccessContext.empty());

    assertThatThrownBy(() -> notes.exchange(new Note("who does this belong to?")))
        .isInstanceOf(AccessDeniedException.class)
        .hasMessageContaining("readable by everyone");
  }

  /** And the refusal is in the record, because a refused write is what an auditor came to see. */
  @Test
  @DisplayName("and says so in the record")
  void and_says_so_in_the_record() {
    LochConfig<Labels, Object> own =
        new LochConfig<Labels, Object>().lattice(Labels.LATTICE).askingWhoIsAsking(edge::get);
    SurrogateSource<Note> watched =
        own.source(
            "notes",
            Note.class,
            ctx ->
                new Labels(
                    ctx.get("tenant").<Exact<String>>map(Exact::of).orElseGet(Exact::none),
                    Level.HIGH));
    MemoryStorage<Labels> storage = new MemoryStorage<>();
    Loch<Labels> unused = new DefaultLoch<>(own, storage);
    edge.set(AccessContext.empty());

    assertThatThrownBy(() -> watched.exchange(new Note("orphan")))
        .isInstanceOf(AccessDeniedException.class);

    assertThat(unused.holds("nothing")).isFalse();
    assertThat(storage.everything()).isEmpty();
    assertThat(storage.audit(AuditRecord.Operation.HOLD))
        .isNotEmpty()
        .allSatisfy(entry -> assertThat(entry.outcome()).isEqualTo(AuditRecord.Outcome.REFUSED));
  }

  /** An axis with a meaningful bottom is left alone: LOW is a level, not a gap. */
  @Test
  @DisplayName("does not constrain an axis whose bottom means something")
  void does_not_constrain_an_axis_whose_bottom_means_something() {
    edge.set(AccessContext.of(Map.of("tenant", "acme")));
    LochConfig<Labels, Object> own = new LochConfig<Labels, Object>().lattice(Labels.LATTICE);
    SurrogateSource<Note> low =
        own.source("low", Note.class, ctx -> new Labels(Exact.of("acme"), Level.LOW));
    Loch<Labels> other = MemoryLoch.create(own);

    assertThat(other.label(low.exchange(new Note("fine")).id()).level()).isEqualTo(Level.LOW);
  }

  @Test
  @DisplayName("so nothing unattributed is ever there to be read")
  void nothing_unattributed_is_ever_there_to_be_read() {
    edge.set(AccessContext.empty());

    assertThatThrownBy(() -> notes.exchange(new Note("orphan")))
        .isInstanceOf(AccessDeniedException.class);

    edge.set(AccessContext.of(Map.of("tenant", "globex")));
    assertThat(reporting.exchange(Surrogate.<Note>of("loch_nothing-like-this")).allowed())
        .isFalse();
  }
}
