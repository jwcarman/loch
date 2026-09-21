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
import org.jwcarman.loch.HandleId;
import org.jwcarman.loch.Lineage;
import org.jwcarman.loch.Storage;
import org.jwcarman.loch.StoredMetadata;
import org.jwcarman.loch.StoredValue;

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
public final class JdbcStorage<A> implements Storage<A> {

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
  private final Codec<A> labels;
  private final Map<String, Codec<?>> byType = new ConcurrentHashMap<>();

  JdbcStorage(
      DataSource dataSource, CodecFactory codecs, StorageCodec storageCodec, Class<A> labelType) {
    this.dataSource = dataSource;
    this.codecs = codecs;
    this.storageCodec = storageCodec;
    this.labels = codecs.create(labelType).andThen(storageCodec);
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
      throw new IllegalStateException("could not create the loch schema", e);
    }
  }

  @Override
  public void put(HandleId id, StoredValue<A> value) {
    try (Connection connection = dataSource.getConnection()) {
      boolean autoCommit = connection.getAutoCommit();
      connection.setAutoCommit(false);
      try {
        insertValue(connection, id, value);
        insertLineage(connection, id, value.lineage());
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

  private void insertValue(Connection connection, HandleId id, StoredValue<A> value)
      throws SQLException {
    try (PreparedStatement statement = connection.prepareStatement(INSERT_VALUE)) {
      statement.setString(1, id.value());
      statement.setString(2, value.type().getType().getTypeName());
      statement.setBytes(3, encode(value.type(), value.value()));
      statement.setBytes(4, labels.encode(value.label()));
      statement.setString(5, value.lineage().derivation().orElse(null));
      statement.setTimestamp(6, Timestamp.from(Instant.now()));
      statement.executeUpdate();
    }
  }

  private void insertLineage(Connection connection, HandleId id, Lineage lineage)
      throws SQLException {
    try (PreparedStatement self = connection.prepareStatement(INSERT_SELF_CLOSURE)) {
      self.setString(1, id.value());
      self.setString(2, id.value());
      self.executeUpdate();
    }
    List<HandleId> parents = lineage.parents();
    for (int i = 0; i < parents.size(); i++) {
      try (PreparedStatement parent = connection.prepareStatement(INSERT_PARENT)) {
        parent.setString(1, id.value());
        parent.setString(2, parents.get(i).value());
        parent.setInt(3, i);
        parent.executeUpdate();
      }
      try (PreparedStatement closure = connection.prepareStatement(INSERT_CLOSURE)) {
        closure.setString(1, id.value());
        closure.setString(2, parents.get(i).value());
        closure.executeUpdate();
      }
    }
  }

  @Override
  public Optional<StoredMetadata<A>> metadata(HandleId id) {
    try (Connection connection = dataSource.getConnection();
        PreparedStatement statement = connection.prepareStatement(SELECT_METADATA)) {
      statement.setString(1, id.value());
      try (ResultSet rows = statement.executeQuery()) {
        if (!rows.next()) {
          return Optional.empty();
        }
        A label = labels.decode(rows.getBytes("label"));
        String derivation = rows.getString("derivation");
        Lineage lineage =
            derivation == null
                ? Lineage.held()
                : Lineage.derivedFrom(parentsOf(connection, id), derivation);
        return Optional.of(new StoredMetadata<>(rows.getString("value_type"), label, lineage));
      }
    } catch (SQLException e) {
      throw new IllegalStateException("could not read " + id, e);
    }
  }

  @Override
  public <T> Optional<T> value(HandleId id, TypeRef<T> type) {
    try (Connection connection = dataSource.getConnection();
        PreparedStatement statement = connection.prepareStatement(SELECT_PAYLOAD)) {
      statement.setString(1, id.value());
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

  private List<HandleId> parentsOf(Connection connection, HandleId id) throws SQLException {
    List<HandleId> parents = new ArrayList<>();
    try (PreparedStatement statement = connection.prepareStatement(SELECT_PARENTS)) {
      statement.setString(1, id.value());
      try (ResultSet rows = statement.executeQuery()) {
        while (rows.next()) {
          parents.add(new HandleId(rows.getString("parent_id")));
        }
      }
    }
    return parents;
  }

  @Override
  public boolean contains(HandleId id) {
    try (Connection connection = dataSource.getConnection();
        PreparedStatement statement =
            connection.prepareStatement("SELECT 1 FROM loch_value WHERE value_id = ?")) {
      statement.setString(1, id.value());
      try (ResultSet rows = statement.executeQuery()) {
        return rows.next();
      }
    } catch (SQLException e) {
      throw new IllegalStateException("could not look for " + id, e);
    }
  }

  @Override
  public int erase(HandleId root) {
    try (Connection connection = dataSource.getConnection()) {
      boolean autoCommit = connection.getAutoCommit();
      connection.setAutoCommit(false);
      try {
        int removed;
        try (PreparedStatement statement = connection.prepareStatement(DELETE_REACHABLE)) {
          statement.setString(1, root.value());
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
