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

import java.util.Objects;
import javax.sql.DataSource;
import org.jwcarman.codec.spi.CodecFactory;
import org.jwcarman.loch.lattice.Axes;

/**
 * What a database-backed store needs that has nothing to do with policy.
 *
 * <p>Deliberately not a kind of {@link org.jwcarman.loch.Charter}. An application declares what it
 * allows -- the labels, the doors, who may reach them -- without knowing or caring where the values
 * end up, and the code declaring portals should compile against the generic thing. This is the
 * other half: where the tables are, how bytes are serialised, and how they are sealed. Both are
 * asked for the {@link JdbcStorage} a charter is sealed to.
 *
 * @param <A> the application's label type, which is stored encrypted like any other value
 */
public final class JdbcStorageConfig {

  /** The root of a graph nobody has rooted: tamper-evident, and forgeable by whoever can write. */
  private static final byte[] ROOTED_IN_THE_OPEN =
      "loch".getBytes(java.nio.charset.StandardCharsets.UTF_8);

  private DataSource dataSource;
  private CodecFactory codecs;
  private String rootId = "open";
  private java.util.function.Function<String, byte[]> roots = id -> ROOTED_IN_THE_OPEN;
  private StorageCodec storageCodec;
  private boolean migrate = true;

  /** Where the tables are. */
  public JdbcStorageConfig dataSource(DataSource dataSource) {
    this.dataSource = Objects.requireNonNull(dataSource, "a durable store needs a data source");
    return this;
  }

  /** How values become bytes. */
  public JdbcStorageConfig codecs(CodecFactory codecs) {
    this.codecs = Objects.requireNonNull(codecs, "a durable store needs codecs");
    return this;
  }

  /** What happens to those bytes before they are written: compression, encryption, both. */
  public JdbcStorageConfig storedThrough(StorageCodec storageCodec) {
    this.storageCodec = Objects.requireNonNull(storageCodec, "a storage codec must not be null");
    return this;
  }

  /**
   * Writes bytes as they are.
   *
   * <p>Said out loud rather than fallen into. Everything this keeps is something somebody decided
   * was worth keeping behind a door, so storing it in the clear is a decision.
   */
  public JdbcStorageConfig storedPlainly() {
    this.storageCodec =
        StorageCodec.of(
            new StorageCodec() {
              @Override
              public byte[] encode(byte[] bytes) {
                return bytes;
              }

              @Override
              public byte[] decode(byte[] bytes) {
                return bytes;
              }
            });
    return this;
  }

  /** Leaves the tables alone, for somewhere that manages its own schema. */
  public JdbcStorageConfig withoutMigration() {
    this.migrate = false;
    return this;
  }

  DataSource dataSourceOrFail() {
    return require(dataSource, "a durable store needs a data source: call dataSource(...)");
  }

  CodecFactory codecsOrFail() {
    return require(
        codecs, "a durable store needs codecs: give it a CodecFactory that can serialise values");
  }

  StorageCodec storageCodecOrFail() {
    return require(
        storageCodec,
        "a durable store needs to say what happens to bytes on the way to disk: call"
            + " storedThrough(...) with your compression and encryption, or storedPlainly() if you"
            + " really mean to write them as they are");
  }

  boolean migrates() {
    return migrate;
  }

  private static <T> T require(T value, String said) {
    if (value == null) {
      throw new IllegalStateException(said);
    }
    return value;
  }

  /**
   * The storage a charter with these axes is sealed to.
   *
   * <p>The only way to build one, so that what happens to the bytes on the way to disk stays a
   * decision somebody made rather than a default they inherited.
   */
  public JdbcStorage storage(Axes axes) {
    JdbcStorage storage =
        JdbcStorage.of(
            dataSourceOrFail(), codecsOrFail(), storageCodecOrFail(), axes, rootId, roots);
    if (migrates()) {
      storage.migrate();
    }
    return storage;
  }

  /**
   * What a fresh value hashes from, having no parents of its own.
   *
   * <p>Every value carries a digest over its own bytes and its parents', so editing one breaks
   * everything derived from it. Where that stops being merely expensive is here. Left alone, the
   * root is a published constant and somebody with write access can recompute a graph after editing
   * it -- an edit is still visible to anyone holding an earlier copy of a digest, but it can be
   * covered up. Given a secret this database does not hold, no node can be forged at all.
   *
   * <p>A lookup rather than a value, so the secret can come from wherever secrets come from and
   * need never be written down beside the thing it protects.
   *
   * <p>Named, because a root that cannot be rotated is one nobody will rotate. Each value and each
   * line records which root it was written under, and verifying asks for that one -- so a new root
   * takes effect for what comes next without invalidating everything already stored. The same shape
   * the payload codec uses for its keys, and the id is signed as well, so two stores sharing a
   * secret still produce different digests.
   */
  public JdbcStorageConfig rootedIn(String id, java.util.function.Function<String, byte[]> roots) {
    this.rootId = Objects.requireNonNull(id, "a root needs a name");
    this.roots = Objects.requireNonNull(roots, "a root must not be null");
    return this;
  }

  /** The same, for an application that has only ever had one root. */
  public JdbcStorageConfig rootedIn(String id, byte[] secret) {
    byte[] only = secret.clone();
    return rootedIn(id, asked -> id.equals(asked) ? only : null);
  }
}
