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

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.jwcarman.loch.lattice.Axis;
import org.jwcarman.loch.lattice.Ceiling;
import org.jwcarman.loch.lattice.Constraint;
import org.jwcarman.loch.lattice.Label;

/**
 * A fold reads many values of one type; a derivation reads exactly one.
 *
 * <p>Arity is part of the type now, so the mismatch this used to guard at runtime cannot be written
 * at all. What is left worth asserting is the boundary a fold still has to decide: none is a
 * refusal, because nothing having arrived is data, not a mistake in the caller.
 */
@DisplayName("Arity")
class ArityTest {

  interface Value {}

  record Note(String text) implements Value {}

  /** Declared once. Nothing special about it: a name and how to decode one. */
  private static final SurrogateType<Note> NOTE = SurrogateType.of(Note.class);

  private static final Axis<String> TENANT = Axis.matching("tenant");

  private final SurrogateStoreConfig<Value> config = new SurrogateStoreConfig<Value>().axes(TENANT);

  private final Conceal<Note> notes = config.source("notes", NOTE, ctx -> Label.of(TENANT, "acme"));

  private final Fold<Note, Note> joined =
      config
          .fold(
              "join",
              NOTE,
              NOTE,
              notes -> new Note(notes.stream().map(Note::text).reduce("", String::concat)))
          .accepting(ctx -> Ceiling.of(TENANT, Constraint.any()))
          .mint();

  private final SurrogateStore store = MemorySurrogateStore.create(config);

  private final Surrogate<Note> first = notes.conceal(new Note("a"));
  private final Surrogate<Note> second = notes.conceal(new Note("b"));

  @Test
  @DisplayName("of one is fine for a fold, which is what folding over one value means")
  void one_is_fine_for_a_fold() {
    assertThat(joined.fold(List.of(first)).succeeded()).isTrue();
  }

  @Test
  @DisplayName("of several is what a fold is for")
  void several_is_what_a_fold_is_for() {
    assertThat(joined.fold(List.of(first, second)).succeeded()).isTrue();
  }

  @Test
  @DisplayName("of none is a refusal, not a bug: an empty list is data, not a mistake")
  void none_is_a_refusal() {
    assertThat(joined.fold(List.of()).made()).isEmpty();
  }

  @Test
  @DisplayName("carries the join of every parent's label")
  void carries_the_join_of_every_parents_label() {
    Surrogate<Note> result = joined.fold(List.of(first, second)).orThrow();

    assertThat(store.label(result.id())).isEqualTo(Label.of(TENANT, "acme"));
  }
}
