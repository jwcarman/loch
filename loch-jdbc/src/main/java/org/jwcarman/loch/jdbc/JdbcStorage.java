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

import static java.nio.charset.StandardCharsets.UTF_8;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import javax.sql.DataSource;
import org.jwcarman.codec.spi.Codec;
import org.jwcarman.codec.spi.CodecFactory;
import org.jwcarman.codec.spi.TypeRef;
import org.jwcarman.loch.AuditRecord;
import org.jwcarman.loch.Lineage;
import org.jwcarman.loch.Storage;
import org.jwcarman.loch.StoredMetadata;
import org.jwcarman.loch.StoredValue;
import org.jwcarman.loch.lattice.Axis;
import org.jwcarman.loch.lattice.Label;

/**
 * Storage in a database, with every payload encrypted.
 *
 * <p>A value is serialised by whatever {@link CodecFactory} the application chose, then passed
 * through whatever {@link StorageCodec} it composed. This module contains no cryptography and no
 * compression of its own; it applies what it was handed, so this class contains no cryptography of
 * its own -- {@code EnvelopeCodec} mints a fresh data key per payload and wraps it with a key named
 * by id, which is what makes key rotation a matter of adding a key rather than rewriting a table.
 *
 * <p><b>The label is encrypted too.</b> A label can be as sensitive as the value: a tenant's name
 * or a project codeword sitting in the clear beside the ciphertext describes what the ciphertext is
 * to anyone who can read the table.
 *
 * <p>Derived values arrive with their parentage, and reachability is maintained as they are stored,
 * so erasing a value and everything made from it is one indexed query.
 */
public final class JdbcStorage implements Storage {

  private static final String INSERT_AUDIT =
      """
      INSERT INTO loch_audit (at, operation, value_id, target, outcome, reason, label, who)
      VALUES (?, ?, ?, ?, ?, ?, ?, ?)
      """;

  private static final String INSERT_VALUE =
      """
      INSERT INTO loch_value (value_id, value_type, payload, label, derivation, held_at)
      VALUES (?, ?, ?, ?, ?, ?)
      """;
  private static final String SELECT_METADATA =
      "SELECT value_type, label, derivation FROM loch_value WHERE value_id = ?";
  private static final String SELECT_PAYLOAD = "SELECT payload FROM loch_value WHERE value_id = ?";
  private static final String SELECT_PARENTS =
      "SELECT parent_id FROM loch_lineage WHERE child_id = ? ORDER BY position";
  private static final String INSERT_PARENT =
      """
      INSERT INTO loch_lineage (child_id, parent_id, position) VALUES (?, ?, ?)
      ON CONFLICT (child_id, parent_id) DO NOTHING
      """;
  private static final String INSERT_SELF_CLOSURE =
      """
      INSERT INTO loch_lineage_closure (ancestor_id, descendant_id, depth) VALUES (?, ?, 0)
      ON CONFLICT (ancestor_id, descendant_id) DO NOTHING
      """;
  private static final String INSERT_CLOSURE =
      """
      INSERT INTO loch_lineage_closure (ancestor_id, descendant_id, depth)
      SELECT c.ancestor_id, ?, c.depth + 1 FROM loch_lineage_closure c WHERE c.descendant_id = ?
      ON CONFLICT (ancestor_id, descendant_id) DO NOTHING
      """;
  private static final String DELETE_REACHABLE =
      """
      DELETE FROM loch_value WHERE value_id IN
        (SELECT descendant_id FROM loch_lineage_closure WHERE ancestor_id = ?)
      """;
  private static final String DELETE_CLOSURE_OF_GONE =
      """
      DELETE FROM loch_lineage_closure
       WHERE descendant_id NOT IN (SELECT value_id FROM loch_value)
          OR ancestor_id NOT IN (SELECT value_id FROM loch_value)
      """;

  private final DataSource dataSource;
  private final CodecFactory codecs;
  private final StorageCodec storageCodec;
  private final Codec<java.util.Map<String, String>> labels;
  private final java.util.List<Axis<?>> axes;
  private final Map<String, Codec<?>> byType = new ConcurrentHashMap<>();

  JdbcStorage(
      DataSource dataSource,
      CodecFactory codecs,
      StorageCodec storageCodec,
      java.util.List<Axis<?>> axes) {
    this.dataSource = dataSource;
    this.codecs = codecs;
    this.storageCodec = storageCodec;
    this.axes = java.util.List.copyOf(axes);
    // One axis at a time, keyed by name. A record would have gone to disk positionally, and then
    // declaring a fourth axis would make every row already written undecodable.
    this.labels =
        codecs
            .create(TypeRef.mapOf(TypeRef.of(String.class), TypeRef.of(String.class)))
            .andThen(storageCodec);
  }

  /** Creates the tables if they are not there. */
  public void migrate() {
    String sql = read("schema-postgresql.sql");
    try (Connection connection = dataSource.getConnection();
        Statement statement = connection.createStatement()) {
      for (String each : sql.split(";")) {
        if (!each.isBlank()) {
          statement.execute(each);
        }
      }
    } catch (SQLException e) {
      throw new IllegalStateException("could not create the store schema", e);
    }
  }

  @Override
  public void put(String id, StoredValue value, AuditRecord record) {
    try (Connection connection = dataSource.getConnection()) {
      boolean autoCommit = connection.getAutoCommit();
      connection.setAutoCommit(false);
      try {
        insertValue(connection, id, value);
        insertLineage(connection, id, value.lineage());
        insertAudit(connection, record);
        connection.commit();
      } catch (SQLException | RuntimeException e) {
        connection.rollback();
        throw e;
      } finally {
        connection.setAutoCommit(autoCommit);
      }
    } catch (SQLException e) {
      throw new IllegalStateException("could not store " + id, e);
    }
  }

  @Override
  public void record(AuditRecord entry) {
    try (Connection connection = dataSource.getConnection()) {
      insertAudit(connection, entry);
    } catch (SQLException e) {
      throw new IllegalStateException("could not record " + entry.operation(), e);
    }
  }

  /**
   * Writes one line of the trail.
   *
   * <p>The label is encrypted and everything else is not, for the reason set out in the schema: a
   * trail nobody can query is a tape backup, and a label names a tenant.
   */
  private void insertAudit(Connection connection, AuditRecord entry) throws SQLException {
    try (PreparedStatement statement = connection.prepareStatement(INSERT_AUDIT)) {
      statement.setTimestamp(1, Timestamp.from(entry.at()));
      statement.setString(2, entry.operation().name());
      statement.setString(3, entry.value());
      statement.setString(4, entry.target().orElse(null));
      statement.setString(5, entry.outcome().name());
      statement.setString(6, entry.reason().orElse(null));
      statement.setBytes(
          7, entry.label().map(label -> storageCodec.encode(label.getBytes(UTF_8))).orElse(null));
      statement.setString(8, entry.context().toString());
      statement.executeUpdate();
    }
  }

  private void insertValue(Connection connection, String id, StoredValue value)
      throws SQLException {
    try (PreparedStatement statement = connection.prepareStatement(INSERT_VALUE)) {
      statement.setString(1, id);
      statement.setString(2, value.type().name());
      statement.setBytes(3, encode(value.type().type(), value.value()));
      statement.setBytes(4, labels.encode(value.label().encode()));
      statement.setString(5, value.lineage().derivation().orElse(null));
      statement.setTimestamp(6, Timestamp.from(Instant.now()));
      statement.executeUpdate();
    }
  }

  private void insertLineage(Connection connection, String id, Lineage lineage)
      throws SQLException {
    try (PreparedStatement self = connection.prepareStatement(INSERT_SELF_CLOSURE)) {
      self.setString(1, id);
      self.setString(2, id);
      self.executeUpdate();
    }
    List<String> parents = lineage.parents();
    for (int i = 0; i < parents.size(); i++) {
      try (PreparedStatement parent = connection.prepareStatement(INSERT_PARENT)) {
        parent.setString(1, id);
        parent.setString(2, parents.get(i));
        parent.setInt(3, i);
        parent.executeUpdate();
      }
      try (PreparedStatement closure = connection.prepareStatement(INSERT_CLOSURE)) {
        closure.setString(1, id);
        closure.setString(2, parents.get(i));
        closure.executeUpdate();
      }
    }
  }

  @Override
  public Optional<StoredMetadata> metadata(String id) {
    try (Connection connection = dataSource.getConnection();
        PreparedStatement statement = connection.prepareStatement(SELECT_METADATA)) {
      statement.setString(1, id);
      try (ResultSet rows = statement.executeQuery()) {
        if (!rows.next()) {
          return Optional.empty();
        }
        Label label = Label.decode(labels.decode(rows.getBytes("label")), axes);
        String derivation = rows.getString("derivation");
        Lineage lineage =
            derivation == null
                ? Lineage.held()
                : Lineage.derivedFrom(parentsOf(connection, id), derivation);
        return Optional.of(new StoredMetadata(rows.getString("value_type"), label, lineage));
      }
    } catch (SQLException e) {
      throw new IllegalStateException("could not read " + id, e);
    }
  }

  @Override
  public <T> Optional<T> value(String id, TypeRef<T> type) {
    try (Connection connection = dataSource.getConnection();
        PreparedStatement statement = connection.prepareStatement(SELECT_PAYLOAD)) {
      statement.setString(1, id);
      try (ResultSet rows = statement.executeQuery()) {
        if (!rows.next()) {
          return Optional.empty();
        }
        return Optional.of(codecFor(type).decode(rows.getBytes("payload")));
      }
    } catch (SQLException e) {
      throw new IllegalStateException("could not read " + id, e);
    }
  }

  private List<String> parentsOf(Connection connection, String id) throws SQLException {
    List<String> parents = new ArrayList<>();
    try (PreparedStatement statement = connection.prepareStatement(SELECT_PARENTS)) {
      statement.setString(1, id);
      try (ResultSet rows = statement.executeQuery()) {
        while (rows.next()) {
          parents.add(new String(rows.getString("parent_id")));
        }
      }
    }
    return parents;
  }

  @Override
  public boolean contains(String id) {
    try (Connection connection = dataSource.getConnection();
        PreparedStatement statement =
            connection.prepareStatement("SELECT 1 FROM loch_value WHERE value_id = ?")) {
      statement.setString(1, id);
      try (ResultSet rows = statement.executeQuery()) {
        return rows.next();
      }
    } catch (SQLException e) {
      throw new IllegalStateException("could not look for " + id, e);
    }
  }

  @Override
  public int erase(String root) {
    try (Connection connection = dataSource.getConnection()) {
      boolean autoCommit = connection.getAutoCommit();
      connection.setAutoCommit(false);
      try {
        int removed;
        try (PreparedStatement statement = connection.prepareStatement(DELETE_REACHABLE)) {
          statement.setString(1, root);
          removed = statement.executeUpdate();
        }
        try (Statement tidy = connection.createStatement()) {
          tidy.executeUpdate(DELETE_CLOSURE_OF_GONE);
        }
        connection.commit();
        return removed;
      } catch (SQLException | RuntimeException e) {
        connection.rollback();
        throw e;
      } finally {
        connection.setAutoCommit(autoCommit);
      }
    } catch (SQLException e) {
      throw new IllegalStateException("could not erase " + root, e);
    }
  }

  @SuppressWarnings("unchecked")
  private byte[] encode(TypeRef<?> type, Object value) {
    return ((Codec<Object>) codecFor(type)).encode(value);
  }

  /** Serialise, then whatever the application said happens to bytes on the way to the table. */
  @SuppressWarnings("unchecked")
  private <T> Codec<T> codecFor(TypeRef<T> type) {
    return (Codec<T>)
        byType.computeIfAbsent(
            type.getType().getTypeName(), name -> codecs.create(type).andThen(storageCodec));
  }

  private String read(String resource) {
    try (InputStream stream = JdbcStorage.class.getResourceAsStream(resource)) {
      if (stream == null) {
        throw new IllegalStateException(resource + " is missing from the jar");
      }
      return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw new IllegalStateException("could not read " + resource, e);
    }
  }
}
