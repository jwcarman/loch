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
package org.jwcarman.loch.jdbc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import javax.sql.DataSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.jwcarman.codec.jackson.JacksonCodecFactory;
import org.jwcarman.loch.lattice.Axes;
import org.jwcarman.loch.lattice.Axis;
import tools.jackson.databind.json.JsonMapper;

/**
 * Two hand-written {@code equals}/{@code hashCode}/{@code toString} implementations, and one
 * resource lookup, that nothing on {@link JdbcStorage}'s public surface ever exercises.
 *
 * <p>{@code Predecessor} and {@code ValueRow} are private records nested inside {@link
 * JdbcStorage}: internal bookkeeping, never compared or printed by any code path this library
 * ships. Their equality and printing are still real invariants worth pinning down -- an array-typed
 * record component gets identity equality for free and a silently wrong one the moment somebody
 * puts a row in a set, which is exactly the trap the comments on both records call out. Reflection
 * is the only way to reach them without changing their visibility, so this class reaches for it
 * rather than leaving the invariant unchecked.
 */
@DisplayName("Internal bookkeeping nothing else exercises")
class JdbcStorageWhiteboxTest {

  private static final Axis<String> TENANT = Axis.matching("tenant");

  private Object newPredecessor(byte[] digest, Instant recordedAt)
      throws ReflectiveOperationException {
    Constructor<?> ctor =
        Class.forName("org.jwcarman.loch.jdbc.JdbcStorage$Predecessor")
            .getDeclaredConstructor(byte[].class, Instant.class);
    ctor.setAccessible(true);
    return ctor.newInstance(digest, recordedAt);
  }

  @Test
  @DisplayName("a predecessor is equal to itself")
  void predecessor_equals_itself() throws ReflectiveOperationException {
    Object predecessor = newPredecessor("digest".getBytes(StandardCharsets.UTF_8), Instant.EPOCH);

    assertThat(predecessor).isEqualTo(predecessor);
  }

  @Test
  @DisplayName("a predecessor is equal to another carrying the same digest and moment")
  void predecessor_equals_an_equivalent_one() throws ReflectiveOperationException {
    Instant recordedAt = Instant.parse("2026-01-01T00:00:00Z");
    Object first = newPredecessor("digest".getBytes(StandardCharsets.UTF_8), recordedAt);
    Object second = newPredecessor("digest".getBytes(StandardCharsets.UTF_8), recordedAt);

    assertThat(first).isEqualTo(second).hasSameHashCodeAs(second);
  }

  @Test
  @DisplayName("a predecessor is unequal to something that is not one")
  void predecessor_is_unequal_to_a_foreign_type() throws ReflectiveOperationException {
    Object predecessor = newPredecessor("digest".getBytes(StandardCharsets.UTF_8), Instant.EPOCH);

    assertThat(predecessor).isNotEqualTo("not a predecessor");
  }

  @Test
  @DisplayName("a predecessor is unequal to one with a different digest or a different moment")
  void predecessor_is_unequal_when_a_field_differs() throws ReflectiveOperationException {
    Instant recordedAt = Instant.parse("2026-01-01T00:00:00Z");
    Object baseline = newPredecessor("digest".getBytes(StandardCharsets.UTF_8), recordedAt);
    Object differentDigest = newPredecessor("other".getBytes(StandardCharsets.UTF_8), recordedAt);
    Object differentMoment =
        newPredecessor("digest".getBytes(StandardCharsets.UTF_8), Instant.EPOCH);

    assertThat(baseline).isNotEqualTo(differentDigest).isNotEqualTo(differentMoment);
  }

  @Test
  @DisplayName("a predecessor prints its digest and its moment, not a memory address")
  void predecessor_prints_its_fields() throws ReflectiveOperationException {
    Instant recordedAt = Instant.parse("2026-01-01T00:00:00Z");
    Object predecessor = newPredecessor("digest".getBytes(StandardCharsets.UTF_8), recordedAt);

    assertThat(predecessor.toString()).contains("Predecessor").contains(recordedAt.toString());
  }

  private Object newValueRow(
      byte[] digest, byte[] payload, byte[] label, String derivation, String type, String rootId)
      throws ReflectiveOperationException {
    Constructor<?> ctor =
        Class.forName("org.jwcarman.loch.jdbc.JdbcStorage$ValueRow")
            .getDeclaredConstructor(
                byte[].class, byte[].class, byte[].class, String.class, String.class, String.class);
    ctor.setAccessible(true);
    return ctor.newInstance(digest, payload, label, derivation, type, rootId);
  }

  private Object aValueRow() throws ReflectiveOperationException {
    return newValueRow(
        "digest".getBytes(StandardCharsets.UTF_8),
        "payload".getBytes(StandardCharsets.UTF_8),
        "label".getBytes(StandardCharsets.UTF_8),
        "Card.last4",
        "card",
        "r1");
  }

  @Test
  @DisplayName("a value row is unequal to something that is not one")
  void value_row_is_unequal_to_a_foreign_type() throws ReflectiveOperationException {
    assertThat(aValueRow()).isNotEqualTo("not a value row");
  }

  @Test
  @DisplayName("a value row is equal to another carrying the same bytes and the same facts")
  void value_row_equals_an_equivalent_one() throws ReflectiveOperationException {
    Object first = aValueRow();
    Object second = aValueRow();

    assertThat(first).isEqualTo(second).hasSameHashCodeAs(second);
  }

  @Test
  @DisplayName("a value row is unequal to one whose digest differs")
  void value_row_is_unequal_when_the_digest_differs() throws ReflectiveOperationException {
    Object baseline = aValueRow();
    Object differs =
        newValueRow(
            "other".getBytes(StandardCharsets.UTF_8),
            "payload".getBytes(StandardCharsets.UTF_8),
            "label".getBytes(StandardCharsets.UTF_8),
            "Card.last4",
            "card",
            "r1");

    assertThat(baseline).isNotEqualTo(differs);
  }

  @Test
  @DisplayName("a value row is unequal to one whose payload differs")
  void value_row_is_unequal_when_the_payload_differs() throws ReflectiveOperationException {
    Object baseline = aValueRow();
    Object differs =
        newValueRow(
            "digest".getBytes(StandardCharsets.UTF_8),
            "other".getBytes(StandardCharsets.UTF_8),
            "label".getBytes(StandardCharsets.UTF_8),
            "Card.last4",
            "card",
            "r1");

    assertThat(baseline).isNotEqualTo(differs);
  }

  @Test
  @DisplayName("a value row is unequal to one whose label differs")
  void value_row_is_unequal_when_the_label_differs() throws ReflectiveOperationException {
    Object baseline = aValueRow();
    Object differs =
        newValueRow(
            "digest".getBytes(StandardCharsets.UTF_8),
            "payload".getBytes(StandardCharsets.UTF_8),
            "other".getBytes(StandardCharsets.UTF_8),
            "Card.last4",
            "card",
            "r1");

    assertThat(baseline).isNotEqualTo(differs);
  }

  @Test
  @DisplayName("a value row is unequal to one whose derivation differs")
  void value_row_is_unequal_when_the_derivation_differs() throws ReflectiveOperationException {
    Object baseline = aValueRow();
    Object differs =
        newValueRow(
            "digest".getBytes(StandardCharsets.UTF_8),
            "payload".getBytes(StandardCharsets.UTF_8),
            "label".getBytes(StandardCharsets.UTF_8),
            "Other.derivation",
            "card",
            "r1");

    assertThat(baseline).isNotEqualTo(differs);
  }

  @Test
  @DisplayName("a value row is unequal to one whose type differs")
  void value_row_is_unequal_when_the_type_differs() throws ReflectiveOperationException {
    Object baseline = aValueRow();
    Object differs =
        newValueRow(
            "digest".getBytes(StandardCharsets.UTF_8),
            "payload".getBytes(StandardCharsets.UTF_8),
            "label".getBytes(StandardCharsets.UTF_8),
            "Card.last4",
            "other-type",
            "r1");

    assertThat(baseline).isNotEqualTo(differs);
  }

  @Test
  @DisplayName("a value row is unequal to one whose root differs")
  void value_row_is_unequal_when_the_root_differs() throws ReflectiveOperationException {
    Object baseline = aValueRow();
    Object differs =
        newValueRow(
            "digest".getBytes(StandardCharsets.UTF_8),
            "payload".getBytes(StandardCharsets.UTF_8),
            "label".getBytes(StandardCharsets.UTF_8),
            "Card.last4",
            "card",
            "r2");

    assertThat(baseline).isNotEqualTo(differs);
  }

  /** Never the bytes: a payload is the value this library exists to keep out of a log line. */
  @Test
  @DisplayName("a value row prints its type, derivation and root, and never its bytes")
  void value_row_prints_without_its_bytes() throws ReflectiveOperationException {
    String printed = aValueRow().toString();

    assertThat(printed)
        .contains("card")
        .contains("Card.last4")
        .contains("r1")
        .doesNotContain("payload")
        .doesNotContain("label");
  }

  /**
   * A schema resource that is not in the jar is a configuration mistake, not a corrupt read, and
   * the migration path only ever asks for the one resource that always ships -- so this is reached
   * through the private method directly rather than by breaking the classpath for the whole test.
   */
  @Test
  @DisplayName("refuses to migrate against a schema resource that is not in the jar")
  void refuses_a_missing_schema_resource() throws ReflectiveOperationException {
    JdbcStorage storage =
        new JdbcStorageConfig()
            .dataSource(unusedDataSource())
            .codecs(new JacksonCodecFactory(JsonMapper.builder().build()))
            .storedPlainly()
            .withoutMigration()
            .storage(Axes.of(TENANT));

    Method read = JdbcStorage.class.getDeclaredMethod("read", String.class);
    read.setAccessible(true);

    assertThatThrownBy(() -> invoke(read, storage))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("is missing from the jar");
  }

  private void invoke(Method read, JdbcStorage storage) throws Throwable {
    try {
      read.invoke(storage, "no-such-schema-resource.sql");
    } catch (InvocationTargetException e) {
      throw e.getCause();
    }
  }

  /**
   * Never opened: {@code withoutMigration()} means nothing here ever calls {@code getConnection}.
   */
  private DataSource unusedDataSource() {
    return new DataSource() {
      @Override
      public java.sql.Connection getConnection() {
        throw new UnsupportedOperationException("not needed by this test");
      }

      @Override
      public java.sql.Connection getConnection(String username, String password) {
        throw new UnsupportedOperationException("not needed by this test");
      }

      @Override
      public java.io.PrintWriter getLogWriter() {
        throw new UnsupportedOperationException("not needed by this test");
      }

      @Override
      public void setLogWriter(java.io.PrintWriter out) {
        throw new UnsupportedOperationException("not needed by this test");
      }

      @Override
      public void setLoginTimeout(int seconds) {
        throw new UnsupportedOperationException("not needed by this test");
      }

      @Override
      public int getLoginTimeout() {
        throw new UnsupportedOperationException("not needed by this test");
      }

      @Override
      public java.util.logging.Logger getParentLogger() {
        throw new UnsupportedOperationException("not needed by this test");
      }

      @Override
      public <T> T unwrap(Class<T> iface) {
        throw new UnsupportedOperationException("not needed by this test");
      }

      @Override
      public boolean isWrapperFor(Class<?> iface) {
        throw new UnsupportedOperationException("not needed by this test");
      }
    };
  }
}
