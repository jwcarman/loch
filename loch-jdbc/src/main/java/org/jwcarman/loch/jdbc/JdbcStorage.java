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
import org.jwcarman.loch.lattice.Axes;
import org.jwcarman.loch.lattice.Label;

/**
 * Storage in a database, with every payload encrypted.
 *
 * <p>A value is serialised by whatever {@link CodecFactory} the application chose, then passed
 * through whatever {@link StorageCodec} it composed. This module contains no cryptography and no
 * compression of its own; it applies what it was handed, so this class contains no cryptography of
 * its own -- {@code EnvelopeCodec} constitutes a fresh data key per payload and wraps it with a key
 * named by id, which is what makes key rotation a matter of adding a key rather than rewriting a
 * table.
 *
 * <p><b>The label is encrypted too.</b> A label can be as sensitive as the value: a tenant's name
 * or a project codeword sitting in the clear beside the ciphertext describes what the ciphertext is
 * to anyone who can read the table.
 *
 * <p>Derived values arrive with their parentage, and reachability is maintained as they are stored,
 * so erasing a value and everything made from it is one indexed query.
 */
public final class JdbcStorage implements Storage {

  /**
   * Durable storage for a charter with these axes.
   *
   * <p>The axes are the charter's, passed rather than restated: a label is stored one axis at a
   * time and keyed by name, so reading one back needs to know which axes the charter declares.
   */
  static JdbcStorage of(
      javax.sql.DataSource dataSource, CodecFactory codecs, StorageCodec storageCodec, Axes axes) {
    return new JdbcStorage(dataSource, codecs, storageCodec, axes);
  }

  private static final String INSERT_AUDIT =
      """
      INSERT INTO loch_audit
        (at, operation, value_id, target, outcome, reason, detail, label, previous, digest, who)
      VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
      """;

  private static final String INSERT_VALUE =
      """
      INSERT INTO loch_value (value_id, value_type, payload, label, derivation, held_at)
      VALUES (?, ?, ?, ?, ?, ?)
      """;

  /** One name every appender waits on, so the trail has one order. */
  private static final long CHAIN_LOCK = 0x10C_A0D17L;

  private static final String SELECT_METADATA =
      "SELECT value_type, label, derivation FROM loch_value WHERE value_id = ?";
  private static final String SELECT_PAYLOAD = "SELECT payload FROM loch_value WHERE value_id = ?";
  private static final String SELECT_METADATA_MANY =
      "SELECT value_id, value_type, label, derivation FROM loch_value WHERE value_id = ANY (?)";
  private static final String SELECT_PAYLOAD_MANY =
      "SELECT value_id, payload FROM loch_value WHERE value_id = ANY (?)";
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
  private final Axes axes;
  private final Map<String, Codec<?>> byType = new ConcurrentHashMap<>();

  private JdbcStorage(
      DataSource dataSource, CodecFactory codecs, StorageCodec storageCodec, Axes axes) {
    this.dataSource = dataSource;
    this.codecs = codecs;
    this.storageCodec = storageCodec;
    this.axes = axes;
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
      boolean autoCommit = connection.getAutoCommit();
      connection.setAutoCommit(false);
      try {
        insertAudit(connection, entry);
        connection.commit();
      } catch (SQLException | RuntimeException e) {
        connection.rollback();
        throw e;
      } finally {
        connection.setAutoCommit(autoCommit);
      }
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
    // Locked for the rest of this transaction: the digest of this line depends on the one before
    // it, so two appends cannot be in flight at once. An audit whose order can be argued with is
    // not much of an audit, and the cost of that is written down in the schema.
    byte[] previous = lockChainHead(connection);
    byte[] detail = protected_(entry.detail());
    byte[] label = protected_(entry.label());
    // Truncated once, and the same value is both stored and hashed. TIMESTAMPTZ keeps microseconds
    // and Instant.now() offers nanoseconds, so hashing what was in hand rather than what reached
    // the column made every line fail its own check the moment it was read back.
    Instant at = entry.at().truncatedTo(java.time.temporal.ChronoUnit.MICROS);
    byte[] digest = digestOf(previous, at, entry, detail, label);
    try (PreparedStatement statement = connection.prepareStatement(INSERT_AUDIT)) {
      statement.setTimestamp(1, Timestamp.from(at));
      statement.setString(2, entry.operation().name());
      statement.setString(3, entry.value());
      statement.setString(4, entry.target().orElse(null));
      statement.setString(5, entry.outcome().name());
      statement.setString(6, entry.reason().orElse(null));
      statement.setBytes(7, detail);
      statement.setBytes(8, label);
      statement.setBytes(9, previous);
      statement.setBytes(10, digest);
      statement.setString(11, entry.context().toString());
      statement.executeUpdate();
    }
  }

  /** The digest of the last line written, held until this transaction ends. */
  private static byte[] lockChainHead(Connection connection) throws SQLException {
    // Held until this transaction ends. An advisory lock rather than a row, because the first
    // append has no row to lock and inventing one only to lock it is how the same fact ends up
    // stored twice.
    try (PreparedStatement lock = connection.prepareStatement("SELECT pg_advisory_xact_lock(?)")) {
      lock.setLong(1, CHAIN_LOCK);
      lock.execute();
    }
    try (PreparedStatement statement =
            connection.prepareStatement(
                "SELECT digest FROM loch_audit ORDER BY entry_id DESC LIMIT 1");
        ResultSet rows = statement.executeQuery()) {
      return rows.next() ? rows.getBytes("digest") : null;
    }
  }

  /**
   * What this line hashes to, given the one before it.
   *
   * <p>Over what is actually stored, ciphertext included, so verifying the chain needs no key:
   * whoever can read the table can check it, and cannot quietly edit it. Every field is length-
   * prefixed, so no two different trails can encode to the same bytes by running one value into the
   * next.
   */
  private static byte[] digestOf(
      byte[] previous, Instant at, AuditRecord entry, byte[] detail, byte[] label) {
    return digestOf(
        previous,
        at,
        entry.operation().name(),
        entry.value(),
        entry.target().orElse(null),
        entry.outcome().name(),
        entry.reason().orElse(null),
        detail,
        label,
        entry.context().toString());
  }

  /** The same, from a row read back, which is how the chain is checked without a key. */
  private static byte[] digestOf(
      byte[] previous,
      Instant at,
      String operation,
      String value,
      String target,
      String outcome,
      String reason,
      byte[] detail,
      byte[] label,
      String who) {
    try {
      java.security.MessageDigest sha = java.security.MessageDigest.getInstance("SHA-256");
      feed(sha, previous);
      feed(sha, at.toString().getBytes(UTF_8));
      feed(sha, operation.getBytes(UTF_8));
      feed(sha, value.getBytes(UTF_8));
      feed(sha, target == null ? null : target.getBytes(UTF_8));
      feed(sha, outcome.getBytes(UTF_8));
      feed(sha, reason == null ? null : reason.getBytes(UTF_8));
      feed(sha, detail);
      feed(sha, label);
      feed(sha, who.getBytes(UTF_8));
      return sha.digest();
    } catch (java.security.NoSuchAlgorithmException e) {
      throw new IllegalStateException("this JVM has no SHA-256, which every JVM is required to", e);
    }
  }

  private static void feed(java.security.MessageDigest sha, byte[] field) {
    int length = field == null ? -1 : field.length;
    sha.update(
        new byte[] {
          (byte) (length >>> 24), (byte) (length >>> 16), (byte) (length >>> 8), (byte) length
        });
    if (field != null) {
      sha.update(field);
    }
  }

  /** Label-shaped, so it goes to disk the way a label does and never in the clear. */
  private byte[] protected_(java.util.Optional<String> value) {
    return value.map(text -> storageCodec.encode(text.getBytes(UTF_8))).orElse(null);
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
  public java.util.Map<String, StoredMetadata> metadata(java.util.List<String> ids) {
    if (ids.isEmpty()) {
      return java.util.Map.of();
    }
    try (Connection connection = dataSource.getConnection();
        PreparedStatement statement = connection.prepareStatement(SELECT_METADATA_MANY)) {
      statement.setArray(1, connection.createArrayOf("text", ids.toArray()));
      java.util.Map<String, StoredMetadata> found = new java.util.LinkedHashMap<>();
      try (ResultSet rows = statement.executeQuery()) {
        while (rows.next()) {
          String id = rows.getString("value_id");
          Label label = Label.decode(labels.decode(rows.getBytes("label")), axes);
          String derivation = rows.getString("derivation");
          Lineage lineage =
              derivation == null
                  ? Lineage.held()
                  : Lineage.derivedFrom(parentsOf(connection, id), derivation);
          found.put(id, new StoredMetadata(rows.getString("value_type"), label, lineage));
        }
      }
      return found;
    } catch (SQLException e) {
      throw new IllegalStateException("could not read " + ids, e);
    }
  }

  @Override
  public java.util.Map<String, Object> values(java.util.Map<String, TypeRef<?>> wanted) {
    if (wanted.isEmpty()) {
      return java.util.Map.of();
    }
    try (Connection connection = dataSource.getConnection();
        PreparedStatement statement = connection.prepareStatement(SELECT_PAYLOAD_MANY)) {
      statement.setArray(1, connection.createArrayOf("text", wanted.keySet().toArray()));
      java.util.Map<String, Object> found = new java.util.LinkedHashMap<>();
      try (ResultSet rows = statement.executeQuery()) {
        while (rows.next()) {
          String id = rows.getString("value_id");
          found.put(id, codecFor(wanted.get(id)).decode(rows.getBytes("payload")));
        }
      }
      return found;
    } catch (SQLException e) {
      throw new IllegalStateException("could not read " + wanted.keySet(), e);
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

  /**
   * Reads the whole trail back and checks it is the one that was written.
   *
   * <p>Every line carries the digest of the line before it and its own, so a modified row, a
   * deleted row, a reordered row and an inserted row all break the chain. Rewriting it without
   * detection would mean recomputing every digest after the change, which is exactly the work this
   * makes necessary.
   *
   * <p>Needs no key. The digests cover what is stored, ciphertext included, so whoever can read the
   * table can check it -- and an auditor who cannot decrypt a label can still prove nobody edited
   * the trail.
   *
   * @return the id of the first line that does not agree with the chain, or empty when the whole
   *     trail is intact
   */
  public java.util.Optional<Long> firstBrokenEntry() {
    try (Connection connection = dataSource.getConnection();
        PreparedStatement statement =
            connection.prepareStatement(
                """
                SELECT entry_id, at, operation, value_id, target, outcome, reason, detail, label,
                       previous, digest, who
                FROM loch_audit ORDER BY entry_id
                """);
        ResultSet rows = statement.executeQuery()) {
      byte[] expected = null;
      while (rows.next()) {
        byte[] previous = rows.getBytes("previous");
        if (!java.util.Arrays.equals(previous, expected)) {
          return java.util.Optional.of(rows.getLong("entry_id"));
        }
        byte[] digest =
            digestOf(
                previous,
                rows.getTimestamp("at").toInstant(),
                rows.getString("operation"),
                rows.getString("value_id"),
                rows.getString("target"),
                rows.getString("outcome"),
                rows.getString("reason"),
                rows.getBytes("detail"),
                rows.getBytes("label"),
                rows.getString("who"));
        if (!java.util.Arrays.equals(digest, rows.getBytes("digest"))) {
          return java.util.Optional.of(rows.getLong("entry_id"));
        }
        expected = digest;
      }
      return java.util.Optional.empty();
    } catch (SQLException e) {
      throw new IllegalStateException("could not read the trail back", e);
    }
  }
}
