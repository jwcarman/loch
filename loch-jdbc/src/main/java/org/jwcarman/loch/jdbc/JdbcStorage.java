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
      javax.sql.DataSource dataSource,
      CodecFactory codecs,
      StorageCodec storageCodec,
      Axes axes,
      String rootId,
      java.util.function.Function<String, byte[]> roots) {
    return new JdbcStorage(dataSource, codecs, storageCodec, axes, rootId, roots);
  }

  private static final String INSERT_AUDIT =
      """
      INSERT INTO loch_audit
        (at, operation, value_id, target, outcome, reason, detail, label, previous, digest,
         root_id, who)
      VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
      """;

  private static final String INSERT_VALUE =
      """
      INSERT INTO loch_value
        (value_id, value_type, payload, label, derivation, concealed_at, digest, root_id)
      VALUES (?, ?, ?, ?, ?, ?, ?, ?)
      """;

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
  private final String rootId;
  private final java.util.function.Function<String, byte[]> roots;
  private final Map<String, Codec<?>> byType = new ConcurrentHashMap<>();

  private JdbcStorage(
      DataSource dataSource,
      CodecFactory codecs,
      StorageCodec storageCodec,
      Axes axes,
      String rootId,
      java.util.function.Function<String, byte[]> roots) {
    this.dataSource = dataSource;
    this.codecs = codecs;
    this.storageCodec = storageCodec;
    this.axes = axes;
    this.rootId = rootId;
    this.roots = roots;
    // One axis at a time, keyed by name. A record would have gone to disk positionally, and then
    // declaring a fourth axis would make every row already written undecodable.
    this.labels =
        codecs
            .create(TypeRef.mapOf(TypeRef.of(String.class), TypeRef.of(String.class)))
            .andThen(storageCodec);
  }

  /** Creates the tables if they are not there. */
  public void migrate() {
    // Comments first: the schema explains itself at length, and a semicolon in a sentence used to
    // cut a CREATE TABLE in half.
    String sql =
        read("schema-postgresql.sql")
            .lines()
            .map(line -> line.strip().startsWith("--") ? "" : line)
            .collect(java.util.stream.Collectors.joining("\n"));
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
    // Ordered, because a line has a predecessor rather than parents. An advisory lock rather than
    // a row: the first append has nothing to lock, and inventing a row only to lock it is how the
    // same fact ends up stored twice.
    byte[] previous = lockTrailHead(connection);
    byte[] detail = protected_(entry.detail());
    byte[] label = protected_(entry.label());
    // Truncated once, and the same value is stored and signed. TIMESTAMPTZ keeps microseconds and
    // Instant.now() offers nanoseconds, so signing what was in hand rather than what reached the
    // column made every line fail its own check the moment it was read back.
    Instant at = entry.at().truncatedTo(java.time.temporal.ChronoUnit.MICROS);
    byte[] digest = lineDigest(rootId, previous, at, entry, detail, label);
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
      statement.setString(11, rootId);
      statement.setString(12, entry.context().toString());
      statement.executeUpdate();
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
    byte[] payload = encode(value.type().type(), value.value());
    byte[] label = labels.encode(value.label().encode());
    // Signed, and the same value reaches the column. When a value was taken in is a fact somebody
    // would want to change, so leaving it out of the digest left it free to change.
    Instant concealedAt = clock().instant().truncatedTo(java.time.temporal.ChronoUnit.MICROS);
    // From the parents, which are immutable and already written, so nothing here is locked and two
    // derivations never wait on each other. A fresh value has none and starts its own graph.
    byte[] digest =
        digestOf(
            rootId,
            id,
            value.type().name(),
            payload,
            label,
            concealedAt,
            parentDigests(connection, value));
    try (PreparedStatement statement = connection.prepareStatement(INSERT_VALUE)) {
      statement.setString(1, id);
      statement.setString(2, value.type().name());
      statement.setBytes(3, payload);
      statement.setBytes(4, label);
      statement.setString(5, value.lineage().derivation().orElse(null));
      statement.setTimestamp(6, Timestamp.from(concealedAt));
      statement.setBytes(7, digest);
      statement.setString(8, rootId);
      statement.executeUpdate();
    }
  }

  /** What this value was made from, in the order it was made from them. */
  private List<byte[]> parentDigests(Connection connection, StoredValue value) throws SQLException {
    List<String> parents = value.lineage().parents();
    List<byte[]> digests = new java.util.ArrayList<>();
    for (String parent : parents) {
      try (PreparedStatement statement =
          connection.prepareStatement("SELECT digest FROM loch_value WHERE value_id = ?")) {
        statement.setString(1, parent);
        try (ResultSet rows = statement.executeQuery()) {
          if (!rows.next()) {
            throw new IllegalStateException(
                "cannot derive from " + parent + ", which this store is not holding");
          }
          digests.add(rows.getBytes("digest"));
        }
      }
    }
    return digests;
  }

  /**
   * What a value hashes to: its own bytes, and whatever it was derived from.
   *
   * <p>Position matters, so a derivation over the same parents in a different order is a different
   * value. Every field is length-prefixed, so no two different graphs encode to the same bytes by
   * running one value into the next.
   *
   * <p>Keyed by the root. Rooted in a constant this is tamper-evident: an edit is visible, but
   * somebody with write access can recompute the graph below it. Rooted in a secret the database
   * does not hold, no node can be forged at all.
   */
  private byte[] digestOf(
      String under,
      String id,
      String type,
      byte[] payload,
      byte[] label,
      Instant concealedAt,
      List<byte[]> parents) {
    javax.crypto.Mac mac = keyed(under);
    for (byte[] parent : parents) {
      feed(mac, parent);
    }
    feed(mac, id.getBytes(UTF_8));
    feed(mac, type.getBytes(UTF_8));
    feed(mac, payload);
    feed(mac, label);
    feed(mac, concealedAt.toString().getBytes(UTF_8));
    return mac.doFinal();
  }

  /**
   * Keyed by a root, which is why none of this can be recomputed by whoever can write.
   *
   * <p>Named, so rotating a root does not invalidate what was written under the last one. The id is
   * signed too, so two stores sharing a secret still produce different digests.
   */
  private javax.crypto.Mac keyed(String id) {
    byte[] secret = roots.apply(id);
    if (secret == null) {
      throw new IllegalStateException(
          "nothing supplies the root '" + id + "', which some of this was written under");
    }
    try {
      javax.crypto.Mac mac = javax.crypto.Mac.getInstance("HmacSHA256");
      mac.init(new javax.crypto.spec.SecretKeySpec(secret, "HmacSHA256"));
      feed(mac, id.getBytes(UTF_8));
      return mac;
    } catch (java.security.NoSuchAlgorithmException | java.security.InvalidKeyException e) {
      throw new IllegalStateException("this JVM cannot compute HMAC-SHA256", e);
    }
  }

  private static void feed(javax.crypto.Mac mac, byte[] field) {
    int length = field == null ? -1 : field.length;
    mac.update(
        new byte[] {
          (byte) (length >>> 24), (byte) (length >>> 16), (byte) (length >>> 8), (byte) length
        });
    if (field != null) {
      mac.update(field);
    }
  }

  /** One name every appender waits on, so the trail has one order. */
  private static final long TRAIL_LOCK = 0x10C_A0D17L;

  private static byte[] lockTrailHead(Connection connection) throws SQLException {
    try (PreparedStatement lock = connection.prepareStatement("SELECT pg_advisory_xact_lock(?)")) {
      lock.setLong(1, TRAIL_LOCK);
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
   * What one line of the trail signs: the line before it, and everything stored about this one.
   *
   * <p>Over what actually reaches the columns, ciphertext included, so verifying reads the table as
   * it is. Keyed, so nobody can recompute the tail after removing something from the middle.
   */
  private byte[] lineDigest(
      String under, byte[] previous, Instant at, AuditRecord entry, byte[] detail, byte[] label) {
    return lineDigest(
        under,
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

  private byte[] lineDigest(
      String under,
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
    javax.crypto.Mac mac = keyed(under);
    feed(mac, previous);
    feed(mac, at.toString().getBytes(UTF_8));
    feed(mac, operation.getBytes(UTF_8));
    feed(mac, value.getBytes(UTF_8));
    feed(mac, target == null ? null : target.getBytes(UTF_8));
    feed(mac, outcome.getBytes(UTF_8));
    feed(mac, reason == null ? null : reason.getBytes(UTF_8));
    feed(mac, detail);
    feed(mac, label);
    feed(mac, who.getBytes(UTF_8));
    return mac.doFinal();
  }

  /**
   * The digest of the last line written, for publishing somewhere this database cannot reach.
   *
   * <p>The one thing verification cannot do on its own is notice lines cut from the end: what
   * remains is a valid trail that simply stopped earlier, and no structure over data an attacker
   * controls can say otherwise. An anchor is the answer -- write this down elsewhere, periodically,
   * and compare. It is one digest, so a log line or a printout will do.
   *
   * @return the head, or empty when nothing has been recorded yet
   */
  public byte[] head() {
    try (Connection connection = dataSource.getConnection();
        PreparedStatement statement =
            connection.prepareStatement(
                "SELECT digest FROM loch_audit ORDER BY entry_id DESC LIMIT 1");
        ResultSet rows = statement.executeQuery()) {
      return rows.next() ? rows.getBytes("digest") : new byte[0];
    } catch (SQLException e) {
      throw new IllegalStateException("could not read the head of the trail", e);
    }
  }

  /**
   * Where the trail stops agreeing with itself.
   *
   * <p>A line that was edited fails its own digest. A line that was removed leaves the next one
   * naming a predecessor that is not there. Neither can be papered over without the key, which is
   * the difference between noticing a careless edit and noticing a deliberate one.
   *
   * @return the id of the first line that does not agree, or empty when the trail is intact
   */
  public java.util.Optional<Long> firstBrokenEntry() {
    try (Connection connection = dataSource.getConnection();
        PreparedStatement statement =
            connection.prepareStatement(
                """
                SELECT entry_id, at, operation, value_id, target, outcome, reason, detail, label,
                       previous, digest, root_id, who
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
            lineDigest(
                rows.getString("root_id"),
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

  /**
   * Every value whose digest no longer agrees with its own bytes and its ancestry.
   *
   * <p>An edited value appears here; so does every value derived from it, because their digests
   * were computed from what it used to be. A deleted value appears as its children failing to find
   * what they were made from.
   */
  public List<String> brokenValues() {
    List<String> broken = new java.util.ArrayList<>();
    try (Connection connection = dataSource.getConnection();
        PreparedStatement statement =
            connection.prepareStatement(
                "SELECT value_id, value_type, payload, label, digest, root_id, concealed_at"
                    + " FROM loch_value ORDER BY concealed_at, value_id");
        ResultSet rows = statement.executeQuery()) {
      java.util.Map<String, byte[]> seen = new java.util.LinkedHashMap<>();
      java.util.List<String[]> pending = new java.util.ArrayList<>();
      java.util.Map<String, byte[]> stored = new java.util.LinkedHashMap<>();
      java.util.Map<String, byte[]> payloads = new java.util.LinkedHashMap<>();
      java.util.Map<String, byte[]> labelsById = new java.util.LinkedHashMap<>();
      java.util.Map<String, String> types = new java.util.LinkedHashMap<>();
      java.util.Map<String, String> rootIds = new java.util.LinkedHashMap<>();
      java.util.Map<String, Instant> concealedAt = new java.util.LinkedHashMap<>();
      while (rows.next()) {
        String id = rows.getString("value_id");
        pending.add(new String[] {id});
        stored.put(id, rows.getBytes("digest"));
        payloads.put(id, rows.getBytes("payload"));
        labelsById.put(id, rows.getBytes("label"));
        types.put(id, rows.getString("value_type"));
        rootIds.put(id, rows.getString("root_id"));
        concealedAt.put(id, rows.getTimestamp("concealed_at").toInstant());
      }
      for (String[] row : pending) {
        String id = row[0];
        List<byte[]> parents = new java.util.ArrayList<>();
        boolean reachable = true;
        for (String parent : parentsOf(connection, id)) {
          byte[] digest = seen.get(parent);
          if (digest == null) {
            reachable = false;
            break;
          }
          parents.add(digest);
        }
        byte[] computed =
            reachable
                ? digestOf(
                    rootIds.get(id),
                    id,
                    types.get(id),
                    payloads.get(id),
                    labelsById.get(id),
                    concealedAt.get(id),
                    parents)
                : null;
        if (computed == null || !java.util.Arrays.equals(computed, stored.get(id))) {
          broken.add(id);
        } else {
          seen.put(id, computed);
        }
      }
      return broken;
    } catch (SQLException e) {
      throw new IllegalStateException("could not read the values back", e);
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
}
