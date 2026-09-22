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
import org.jwcarman.loch.lattice.Axis;
import org.jwcarman.loch.lattice.Ceiling;
import org.jwcarman.loch.lattice.Constraint;
import org.jwcarman.loch.lattice.Label;

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

  private static final SurrogateType<Note> NOTE_TYPE = SurrogateType.of(Note.class);

  enum Level {
    LOW,
    HIGH
  }

  /** The tenant has to be said. The level has a meaningful bottom and does not. */
  private static final Axis<String> TENANT = Axis.matching("tenant").required();

  private static final Axis<Level> LEVEL = Axis.ladder("level", Level.LOW, Level.HIGH);

  record Note(String text) {}

  private final AtomicReference<AccessContext> edge = new AtomicReference<>(AccessContext.empty());

  private final DefaultCharter config = new DefaultCharter(TENANT, LEVEL).currentAccess(edge::get);

  /** Exactly what an application would naturally write, including the part that was the leak. */
  private final Conceal<Note> notes =
      config.source(
          "notes",
          NOTE_TYPE,
          ctx ->
              ctx.get("tenant")
                  .map(tenant -> Label.of(TENANT, tenant))
                  .orElseGet(Label::nothing)
                  .with(LEVEL, Level.HIGH));

  private final Reveal<Note> reporting =
      config
          .destination(
              "reporting",
              ctx ->
                  ctx.get("tenant")
                      .map(tenant -> Ceiling.of(TENANT, Constraint.atMost(tenant)))
                      .orElseGet(() -> Ceiling.of(TENANT, Constraint.any()))
                      .with(LEVEL, Constraint.atMost(Level.HIGH)),
              NOTE_TYPE)
          .reading(NOTE_TYPE);

  private final MemoryStorage kept = new MemoryStorage();

  {
    config.seal(kept);
  }

  // that used to read `.tenant()` / `.level()` off a stored label are re-expressed against
  // Label.toString(), which is documented for rendering only ("Never for a decision"). This is a
  // deviation from PRESERVE-exactly, flagged for James: there is no supported way in the new API to
  // assert what a stored label says on a given axis other than string-matching its render.
  @Test
  @DisplayName("is written when it was said")
  void is_written_when_it_was_said() {
    edge.set(AccessContext.of(Map.of("tenant", "acme")));

    Surrogate<Note> note = notes.conceal(new Note("ours"));

    assertThat(config.label(note.id()).says(TENANT, "acme")).isTrue();
  }

  /** The whole point: a value nobody can attribute is a value everybody can read. */
  @Test
  @DisplayName("refuses the write when it was not, rather than storing something anyone can read")
  void refuses_the_write_when_it_was_not_said() {
    edge.set(AccessContext.empty());

    assertThatThrownBy(() -> notes.conceal(new Note("who does this belong to?")))
        .isInstanceOf(AccessDeniedException.class)
        .hasMessageContaining("readable by everyone");
  }

  /** And the refusal is in the record, because a refused write is what an auditor came to see. */
  @Test
  @DisplayName("and says so in the record")
  void and_says_so_in_the_record() {
    DefaultCharter own = new DefaultCharter(TENANT, LEVEL).currentAccess(edge::get);
    Conceal<Note> watched =
        own.source(
            "notes",
            NOTE_TYPE,
            ctx ->
                ctx.get("tenant")
                    .map(tenant -> Label.of(TENANT, tenant))
                    .orElseGet(Label::nothing)
                    .with(LEVEL, Level.HIGH));
    MemoryStorage storage = new MemoryStorage();
    own.seal(storage);
    edge.set(AccessContext.empty());

    assertThatThrownBy(() -> watched.conceal(new Note("orphan")))
        .isInstanceOf(AccessDeniedException.class);

    assertThat(own.holds("nothing")).isFalse();
    assertThat(kept.everything()).isEmpty();
    assertThat(storage.audit(AuditRecord.Operation.CONCEAL))
        .isNotEmpty()
        .allSatisfy(entry -> assertThat(entry.outcome()).isEqualTo(AuditRecord.Outcome.REFUSED));
  }

  /** An axis with a meaningful bottom is left alone: LOW is a level, not a gap. */
  @Test
  @DisplayName("does not constrain an axis whose bottom means something")
  void does_not_constrain_an_axis_whose_bottom_means_something() {
    edge.set(AccessContext.of(Map.of("tenant", "acme")));
    DefaultCharter own = new DefaultCharter(TENANT, LEVEL);
    Conceal<Note> low =
        own.source("low", NOTE_TYPE, ctx -> Label.of(TENANT, "acme").with(LEVEL, Level.LOW));
    own.seal(new MemoryStorage());

    assertThat(own.label(low.conceal(new Note("fine")).id()).says(LEVEL, Level.LOW)).isTrue();
  }

  /**
   * The invariant has to survive derivation, not just the door.
   *
   * <p>Concealing is the only place a label is asserted rather than computed, so it is where the
   * required-axis check was written. But a privileged derivation may rewrite the label, and {@code
   * atOrBelow} cannot catch a label that simply stops mentioning an axis: an unsaid axis joins as
   * bottom, so {@code Label.nothing()} is at or below absolutely everything. The natural way to
   * write the bug is an {@code of} where a {@code with} was meant.
   */
  @Test
  @DisplayName("cannot be dropped by a lowering, which is where atOrBelow cannot see it")
  void cannot_be_dropped_by_a_lowering() {
    DefaultCharter own = new DefaultCharter(TENANT, LEVEL).currentAccess(edge::get);
    Conceal<Note> door =
        own.source("notes", NOTE_TYPE, ctx -> Label.of(TENANT, "acme").with(LEVEL, Level.HIGH));
    Derivation<Note, Note> redact =
        own.derivation(
            "redact",
            NOTE_TYPE,
            NOTE_TYPE,
            note -> new Note("redacted"),
            d ->
                d.accepting(
                        ctx ->
                            Ceiling.of(TENANT, Constraint.any())
                                .with(LEVEL, Constraint.atMost(Level.HIGH)))
                    // `of` where `with` was meant: the level survives, the tenant vanishes.
                    .lowering(joined -> Label.of(LEVEL, Level.LOW)));
    MemoryStorage storage = new MemoryStorage();
    own.seal(storage);
    edge.set(AccessContext.of(Map.of("tenant", "acme")));

    Derived<Note> result = redact.derive(door.conceal(new Note("ours")));

    assertThat(result.made()).isEmpty();
    assertThat(((Derived.Refused<Note>) result).reason()).isEqualTo(Derived.Reason.NOT_A_LOWERING);
    assertThat(storage.audit(AuditRecord.Operation.DERIVE))
        .isNotEmpty()
        .allSatisfy(entry -> assertThat(entry.outcome()).isEqualTo(AuditRecord.Outcome.REFUSED));
  }

  /**
   * The guarantee has to be about values, not about writes.
   *
   * <p>Refusing an incomplete label at the door covers everything written through a door. It does
   * not cover what is already in the store: mark an axis {@code required()} on a system that has
   * been writing without it, and every row already there leaves it unsaid. Unsaid is the bottom of
   * its order, which is below every ceiling, so those rows are not unreadable -- they are readable
   * by <i>everyone</i>, which is the opposite of what the marking was for.
   *
   * <p>Written here through the storage SPI, which is what a pre-existing row looks like by the
   * time the engine reads it back.
   */
  @Test
  @DisplayName("is enforced when a stored label is read back, not only when one is written")
  void is_enforced_when_a_stored_label_is_read_back() {
    MemoryStorage storage = new MemoryStorage();
    DefaultCharter own = new DefaultCharter(TENANT, LEVEL).currentAccess(edge::get);
    Reveal<Note> anyTenant =
        own.destination(
                "reporting",
                Ceiling.of(TENANT, Constraint.any()).with(LEVEL, Constraint.atMost(Level.HIGH)),
                NOTE_TYPE)
            .reading(NOTE_TYPE);
    own.seal(storage);

    // A row from before the tenant axis was required: it says nothing about tenant at all.
    storage.put(
        "sur_from-before",
        new StoredValue(
            new Note("whose is this?"),
            NOTE_TYPE,
            Label.of(LEVEL, Level.HIGH),
            Lineage.concealed()),
        new AuditRecord(
            AuditRecord.Operation.CONCEAL,
            "sur_from-before",
            java.util.Optional.empty(),
            AuditRecord.Outcome.ALLOWED,
            java.util.Optional.empty(),
            java.util.Optional.empty(),
            java.util.Optional.empty(),
            java.util.Map.of()));

    edge.set(AccessContext.of(Map.of("tenant", "globex")));

    assertThat(anyTenant.reveal(Surrogate.<Note>of("sur_from-before")).allowed()).isFalse();
  }

  @Test
  @DisplayName("so nothing unattributed is ever there to be read")
  void nothing_unattributed_is_ever_there_to_be_read() {
    edge.set(AccessContext.empty());

    assertThatThrownBy(() -> notes.conceal(new Note("orphan")))
        .isInstanceOf(AccessDeniedException.class);

    // Nothing was stored, so there is nothing for any tenant to read. Asserting against a
    // fabricated identifier proved nothing: it is refused whether or not the orphan was written.
    edge.set(AccessContext.of(Map.of("tenant", "globex")));
    assertThat(kept.everything()).isEmpty();
  }
}
