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
import java.util.Optional;
import java.util.function.Function;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.jwcarman.codec.spi.TypeRef;
import org.jwcarman.loch.lattice.Axis;
import org.jwcarman.loch.lattice.Ceiling;
import org.jwcarman.loch.lattice.Constraint;
import org.jwcarman.loch.lattice.Label;

/**
 * {@link Storage} is an SPI an application implements, and every check above it is written to
 * survive one that lies: a row whose metadata says it is there but whose value no longer decodes,
 * or a write that fails after every gate already opened. Every check exists to keep the second half
 * of that failure from becoming an unrecorded exception past the audit.
 */
@DisplayName("A storage that stops agreeing with itself between two calls")
class StorageInconsistencyTest {

  private static final SurrogateType<String> STRING_TYPE = SurrogateType.of(String.class);
  private static final Axis<String> TENANT = Axis.matching("tenant");

  /** A store whose metadata is honest but whose value has quietly gone missing. */
  private static final class ValueDisappears implements Storage {
    private final MemoryStorage delegate = new MemoryStorage();

    @Override
    public void put(String id, StoredValue value, AuditRecord entry) {
      delegate.put(id, value, entry);
    }

    @Override
    public void record(AuditRecord entry) {
      delegate.record(entry);
    }

    @Override
    public Optional<StoredMetadata> metadata(String id) {
      return delegate.metadata(id);
    }

    @Override
    public <T> Optional<T> value(String id, TypeRef<T> type) {
      return Optional.empty();
    }

    @Override
    public boolean contains(String id) {
      return delegate.contains(id);
    }

    @Override
    public List<String> erase(String root, Function<String, AuditRecord> lineFor) {
      return delegate.erase(root, lineFor);
    }
  }

  /** A store whose second write fails, standing in for a parent erased mid-derivation. */
  private static final class FailsOnSecondPut implements Storage {
    private final MemoryStorage delegate = new MemoryStorage();
    private int puts;

    @Override
    public void put(String id, StoredValue value, AuditRecord entry) {
      puts++;
      if (puts > 1) {
        throw new IllegalStateException("this store is no longer accepting writes");
      }
      delegate.put(id, value, entry);
    }

    @Override
    public void record(AuditRecord entry) {
      delegate.record(entry);
    }

    @Override
    public Optional<StoredMetadata> metadata(String id) {
      return delegate.metadata(id);
    }

    @Override
    public <T> Optional<T> value(String id, TypeRef<T> type) {
      return delegate.value(id, type);
    }

    @Override
    public boolean contains(String id) {
      return delegate.contains(id);
    }

    @Override
    public List<String> erase(String root, Function<String, AuditRecord> lineFor) {
      return delegate.erase(root, lineFor);
    }
  }

  @Test
  @DisplayName("refuses a question about a value whose metadata is there but whose value is not")
  void refuses_a_question_when_the_value_is_gone() {
    DefaultCharter charter = new DefaultCharter(TENANT);
    Conceal<String> source = charter.source("mail", STRING_TYPE, Label.of(TENANT, "acme"));
    Query<String, String> mentions =
        charter.query(
            "mentions",
            STRING_TYPE,
            String.class,
            (v, q, ctx) -> v.contains(q),
            d -> d.accepting(Ceiling.of(TENANT, Constraint.any())));
    charter.seal(new ValueDisappears());

    Surrogate<String> held = source.conceal("hello");
    Answer answer = mentions.ask(held, "hello");

    assertThat(answer)
        .isInstanceOfSatisfying(
            Answer.Refused.class,
            r -> assertThat(r.reason()).isEqualTo(Answer.Reason.NO_SUCH_VALUE));
  }

  @Test
  @DisplayName("refuses to derive from a parent whose metadata is there but whose value is not")
  void refuses_to_derive_when_the_parent_value_is_gone() {
    DefaultCharter charter = new DefaultCharter(TENANT);
    Conceal<String> source = charter.source("mail", STRING_TYPE, Label.of(TENANT, "acme"));
    Derivation<String, String> upper =
        charter.derivation(
            "upper",
            STRING_TYPE,
            STRING_TYPE,
            String::toUpperCase,
            d -> d.accepting(Ceiling.of(TENANT, Constraint.any())));
    charter.seal(new ValueDisappears());

    Surrogate<String> held = source.conceal("hello");
    Derived<String> result = upper.derive(held);

    assertThat(result)
        .isInstanceOfSatisfying(
            Derived.Refused.class,
            r -> assertThat(r.reason()).isEqualTo(Derived.Reason.NO_SUCH_VALUE));
  }

  @Test
  @DisplayName("denies a reveal of a value whose metadata is there but whose value is not")
  void denies_a_reveal_when_the_value_is_gone() {
    DefaultCharter charter = new DefaultCharter(TENANT);
    Conceal<String> source = charter.source("mail", STRING_TYPE, Label.of(TENANT, "acme"));
    Reveal<String> outbox =
        charter
            .destination("outbox", Ceiling.of(TENANT, Constraint.any()), STRING_TYPE)
            .reading(STRING_TYPE);
    charter.seal(new ValueDisappears());

    Surrogate<String> held = source.conceal("hello");
    Revealed<String> result = outbox.reveal(held);

    assertThat(result)
        .isInstanceOfSatisfying(
            Revealed.Denied.class,
            d -> assertThat(d.reason()).isEqualTo(Revealed.Reason.NO_SUCH_VALUE));
  }

  /**
   * A parent can be erased while the derivation function is running: every gate already opened, the
   * plaintext was already read, and by the time the child is written its ancestry is gone. That has
   * to be recorded like any other refusal, not thrown past the audit.
   */
  @Test
  @DisplayName("refuses a derivation whose result could not be written, after reading the parent")
  void refuses_a_derivation_that_could_not_be_written() {
    FailsOnSecondPut storage = new FailsOnSecondPut();
    DefaultCharter charter = new DefaultCharter(TENANT);
    Conceal<String> source = charter.source("mail", STRING_TYPE, Label.of(TENANT, "acme"));
    Derivation<String, String> upper =
        charter.derivation(
            "upper",
            STRING_TYPE,
            STRING_TYPE,
            String::toUpperCase,
            d -> d.accepting(Ceiling.of(TENANT, Constraint.any())));
    charter.seal(storage);

    Surrogate<String> held = source.conceal("hello");
    Derived<String> result = upper.derive(held);

    assertThat(result)
        .isInstanceOfSatisfying(
            Derived.Refused.class,
            r -> assertThat(r.reason()).isEqualTo(Derived.Reason.NO_SUCH_VALUE));
  }
}
