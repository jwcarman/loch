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
import org.jwcarman.codec.spi.Codec;
import org.jwcarman.codec.spi.CodecFactory;
import org.jwcarman.loch.AccessContext;
import org.jwcarman.loch.Auditor;
import org.jwcarman.loch.Derivation;
import org.jwcarman.loch.Destination;
import org.jwcarman.loch.DestinationId;
import org.jwcarman.loch.Fold;
import org.jwcarman.loch.LochConfig;
import org.jwcarman.loch.Question;
import org.jwcarman.loch.lattice.Lattice;

/**
 * How a durable loch is built: everything a loch needs, plus where it keeps things and how it
 * protects them.
 *
 * <p>The fluent methods it inherits are re-declared so they answer with this type and a single
 * chain can mix both kinds of setting.
 */
public final class JdbcLochConfig<A> extends LochConfig<A> {

  private DataSource dataSource;
  private CodecFactory codecs;
  private StorageCodec storageCodec;
  private boolean migrate = true;

  /** Where the tables are. */
  public JdbcLochConfig<A> dataSource(DataSource dataSource) {
    this.dataSource = Objects.requireNonNull(dataSource, "a durable loch needs a data source");
    return this;
  }

  /** How values become bytes. Any {@link CodecFactory}: Jackson, fory, protobuf, your own. */
  public JdbcLochConfig<A> codecs(CodecFactory codecs) {
    this.codecs = Objects.requireNonNull(codecs, "a durable loch needs codecs");
    return this;
  }

  /**
   * What happens to those bytes on the way to the table: compression, encryption, both.
   *
   * <p>Required, or say {@link #storedPlainly()}. <b>This is where encryption goes</b>, and there
   * is no default for the same reason there is no default auditor: a store that silently keeps
   * plaintext still looks like a vault.
   *
   * <pre>{@code
   * .storedThrough(StorageCodec.of(
   *     Compression.whenItHelps(new GzipCodec())
   *         .andThen(EnvelopeCodec.builder(keys).build())))
   * }</pre>
   */
  public JdbcLochConfig<A> storedThrough(StorageCodec storageCodec) {
    this.storageCodec = Objects.requireNonNull(storageCodec, "a storage codec must not be null");
    return this;
  }

  /** Stores bytes exactly as serialised. For a throwaway database, never for real data. */
  public JdbcLochConfig<A> storedPlainly() {
    return storedThrough(
        StorageCodec.of(
            new Codec<byte[]>() {
              @Override
              public byte[] encode(byte[] value) {
                return value;
              }

              @Override
              public byte[] decode(byte[] value) {
                return value;
              }
            }));
  }

  /** Leaves the schema alone; something else owns it. */
  public JdbcLochConfig<A> withoutMigration() {
    this.migrate = false;
    return this;
  }

  @Override
  public JdbcLochConfig<A> lattice(Lattice<A> lattice) {
    super.lattice(lattice);
    return this;
  }

  @Override
  public JdbcLochConfig<A> auditor(Auditor auditor) {
    super.auditor(auditor);
    return this;
  }

  @Override
  public JdbcLochConfig<A> withoutAudit() {
    super.withoutAudit();
    return this;
  }

  @Override
  public JdbcLochConfig<A> explainRefusals() {
    super.explainRefusals();
    return this;
  }

  @Override
  public JdbcLochConfig<A> destination(Destination<A> destination) {
    super.destination(destination);
    return this;
  }

  @Override
  public JdbcLochConfig<A> destination(DestinationId id, A ceiling) {
    super.destination(id, ceiling);
    return this;
  }

  @Override
  public JdbcLochConfig<A> derivation(Derivation<A, ?, ?> derivation) {
    super.derivation(derivation);
    return this;
  }

  @Override
  public JdbcLochConfig<A> question(Question<A, ?, ?> question) {
    super.question(question);
    return this;
  }

  @Override
  public JdbcLochConfig<A> fold(Fold<A, ?, ?> fold) {
    super.fold(fold);
    return this;
  }

  /** Re-declared because this config does not inherit the fluent return type. */
  @Override
  public JdbcLochConfig<A> askingWhoIsAsking(java.util.function.Supplier<AccessContext> ambient) {
    super.askingWhoIsAsking(ambient);
    return this;
  }

  @Override
  public JdbcLochConfig<A> callerMayContribute(String... keys) {
    super.callerMayContribute(keys);
    return this;
  }

  DataSource dataSourceOrFail() {
    if (dataSource == null) {
      throw new IllegalStateException("a durable loch needs a data source");
    }
    return dataSource;
  }

  CodecFactory codecsOrFail() {
    if (codecs == null) {
      throw new IllegalStateException(
          "a durable loch needs codecs: give it a CodecFactory that can serialise your values");
    }
    return codecs;
  }

  StorageCodec storageCodecOrFail() {
    if (storageCodec == null) {
      throw new IllegalStateException(
          "a durable loch needs a storage codec: call storedThrough(...) with your compression and"
              + " encryption, or storedPlainly() if this database holds nothing that matters");
    }
    return storageCodec;
  }

  boolean migrates() {
    return migrate;
  }
}
