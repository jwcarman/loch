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

/**
 * What a database-backed store needs that has nothing to do with policy.
 *
 * <p>Deliberately not a kind of {@link org.jwcarman.loch.SurrogateStoreConfig}. An application
 * declares what it allows -- the labels, the doors, who may reach them -- without knowing or caring
 * where the values end up, and the code declaring portals should compile against the generic thing.
 * This is the other half: where the tables are, how bytes are serialised, and how they are sealed.
 * Both are handed to {@link JdbcSurrogateStore#create} and it builds itself.
 *
 * @param <A> the application's label type, which is stored encrypted like any other value
 */
public final class JdbcSurrogateStoreConfig {

  private DataSource dataSource;
  private CodecFactory codecs;
  private StorageCodec storageCodec;
  private boolean migrate = true;

  /** Where the tables are. */
  public JdbcSurrogateStoreConfig dataSource(DataSource dataSource) {
    this.dataSource = Objects.requireNonNull(dataSource, "a durable store needs a data source");
    return this;
  }

  /** How values become bytes. */
  public JdbcSurrogateStoreConfig codecs(CodecFactory codecs) {
    this.codecs = Objects.requireNonNull(codecs, "a durable store needs codecs");
    return this;
  }

  /** What happens to those bytes before they are written: compression, encryption, both. */
  public JdbcSurrogateStoreConfig storedThrough(StorageCodec storageCodec) {
    this.storageCodec = Objects.requireNonNull(storageCodec, "a storage codec must not be null");
    return this;
  }

  /**
   * Writes bytes as they are.
   *
   * <p>Said out loud rather than fallen into. Everything this keeps is something somebody decided
   * was worth keeping behind a door, so storing it in the clear is a decision.
   */
  public JdbcSurrogateStoreConfig storedPlainly() {
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
  public JdbcSurrogateStoreConfig withoutMigration() {
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
}
