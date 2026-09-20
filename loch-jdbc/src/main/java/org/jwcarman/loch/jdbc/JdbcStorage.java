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
import org.jwcarman.loch.HeldId;
import org.jwcarman.loch.Lineage;
import org.jwcarman.loch.Storage;
import org.jwcarman.loch.StoredValue;

/**
 * Storage in a database, with every payload encrypted.
 *
 * <p>A value is serialised, compressed if asked, then encrypted -- the only order that makes sense,
 * since ciphertext does not compress. The protection is a {@code Codec<byte[]>} appended to
 * whatever codec serialises the value, so this class contains no cryptography of its own -- {@code
 * EnvelopeCodec} mints a fresh data key per payload and wraps it with a key named by id, which is
 * what makes key rotation a matter of adding a key rather than rewriting a table.
 *
 * <p><b>The attribution is encrypted too.</b> A label can be as sensitive as the value: a tenant's
 * name or a project codeword sitting in the clear beside the ciphertext describes what the
 * ciphertext is to anyone who can read the table.
 *
 * <p>Derived values arrive with their parentage, and reachability is maintained as they are stored,
 * so erasing a value and everything made from it is one indexed query.
 */
public final class JdbcStorage<A> implements Storage<A> {

  private static final String INSERT_VALUE =
      """
      INSERT INTO loch_value (value_id, value_type, payload, attribution, derivation, held_at)
      VALUES (?, ?, ?, ?, ?, ?)
      ON CONFLICT (value_id) DO NOTHING
      """;
  private static final String SELECT_VALUE =
      "SELECT value_type, payload, attribution, derivation FROM loch_value WHERE value_id = ?";
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
  private final Codec<byte[]> compression;
  private final Codec<byte[]> protection;
  private final Codec<A> attributions;
  private final ClassLoader classLoader;
  private final Map<String, Codec<?>> byType = new ConcurrentHashMap<>();

  JdbcStorage(
      DataSource dataSource,
      CodecFactory codecs,
      Codec<byte[]> compression,
      Codec<byte[]> protection,
      Class<A> attributionType) {
    this.dataSource = dataSource;
    this.codecs = codecs;
    this.compression = compression;
    this.protection = protection;
    // The label is encrypted but not compressed: see JdbcLochConfig#compressedWith.
    this.attributions = codecs.create(attributionType).andThen(protection);
    this.classLoader = attributionType.getClassLoader();
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
  public void put(HeldId id, StoredValue<A> value) {
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

  private void insertValue(Connection connection, HeldId id, StoredValue<A> value)
      throws SQLException {
    try (PreparedStatement statement = connection.prepareStatement(INSERT_VALUE)) {
      statement.setString(1, id.value());
      statement.setString(2, value.type().getName());
      statement.setBytes(3, encode(value.type(), value.value()));
      statement.setBytes(4, attributions.encode(value.attribution()));
      statement.setString(5, value.lineage().derivation().orElse(null));
      statement.setTimestamp(6, Timestamp.from(Instant.now()));
      statement.executeUpdate();
    }
  }

  private void insertLineage(Connection connection, HeldId id, Lineage lineage)
      throws SQLException {
    try (PreparedStatement self = connection.prepareStatement(INSERT_SELF_CLOSURE)) {
      self.setString(1, id.value());
      self.setString(2, id.value());
      self.executeUpdate();
    }
    List<HeldId> parents = lineage.parents();
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
  public Optional<StoredValue<A>> get(HeldId id) {
    try (Connection connection = dataSource.getConnection();
        PreparedStatement statement = connection.prepareStatement(SELECT_VALUE)) {
      statement.setString(1, id.value());
      try (ResultSet rows = statement.executeQuery()) {
        if (!rows.next()) {
          return Optional.empty();
        }
        Class<?> type = typeNamed(rows.getString("value_type"));
        Object value = decode(type, rows.getBytes("payload"));
        A attribution = attributions.decode(rows.getBytes("attribution"));
        String derivation = rows.getString("derivation");
        Lineage lineage =
            derivation == null
                ? Lineage.held()
                : Lineage.derivedFrom(parentsOf(connection, id), derivation);
        return Optional.of(new StoredValue<>(value, type, attribution, lineage));
      }
    } catch (SQLException e) {
      throw new IllegalStateException("could not read " + id, e);
    }
  }

  private List<HeldId> parentsOf(Connection connection, HeldId id) throws SQLException {
    List<HeldId> parents = new ArrayList<>();
    try (PreparedStatement statement = connection.prepareStatement(SELECT_PARENTS)) {
      statement.setString(1, id.value());
      try (ResultSet rows = statement.executeQuery()) {
        while (rows.next()) {
          parents.add(new HeldId(rows.getString("parent_id")));
        }
      }
    }
    return parents;
  }

  @Override
  public boolean contains(HeldId id) {
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
  public int erase(HeldId root) {
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
  private byte[] encode(Class<?> type, Object value) {
    return ((Codec<Object>) codecFor(type)).encode(value);
  }

  private Object decode(Class<?> type, byte[] bytes) {
    return codecFor(type).decode(bytes);
  }

  /**
   * Serialise, then squeeze, then seal.
   *
   * <p>The order is the only one that makes sense: ciphertext does not compress, so compressing
   * afterwards would cost the same and save nothing.
   */
  private Codec<?> codecFor(Class<?> type) {
    return byType.computeIfAbsent(
        type.getName(),
        name -> {
          Codec<?> codec = codecs.create(type);
          if (compression != null) {
            codec = codec.andThen(compression);
          }
          return codec.andThen(protection);
        });
  }

  /**
   * Resolves a stored type name.
   *
   * <p>These tables are trusted storage: only a loch writes them, and anyone who can write a row
   * here can already read every value in the database, so this is not the weakest link. It is still
   * why a loch's tables should not be writable by the application's ordinary database role.
   */
  private Class<?> typeNamed(String name) {
    try {
      return Class.forName(name, false, classLoader);
    } catch (ClassNotFoundException e) {
      throw new IllegalStateException(
          "a value was stored as " + name + ", which is not on this classpath", e);
    }
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
