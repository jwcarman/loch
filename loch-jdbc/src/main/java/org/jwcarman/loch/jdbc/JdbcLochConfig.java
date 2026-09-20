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
import org.jwcarman.codec.jackson.JacksonCodecFactory;
import org.jwcarman.codec.spi.Codec;
import org.jwcarman.codec.spi.CodecFactory;
import org.jwcarman.codec.transform.compress.GzipCodec;
import org.jwcarman.loch.Auditor;
import org.jwcarman.loch.Check;
import org.jwcarman.loch.Derivation;
import org.jwcarman.loch.Destination;
import org.jwcarman.loch.DestinationId;
import org.jwcarman.loch.LochConfig;
import org.jwcarman.loch.lattice.Lattice;
import tools.jackson.databind.ObjectMapper;

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
  private Codec<byte[]> compression;
  private Codec<byte[]> protection;
  private boolean migrate = true;

  /** Where the tables are. */
  public JdbcLochConfig<A> dataSource(DataSource dataSource) {
    this.dataSource = Objects.requireNonNull(dataSource, "a durable loch needs a data source");
    return this;
  }

  /** How values become bytes. Any {@link CodecFactory}; {@link #jackson} is the usual one. */
  public JdbcLochConfig<A> codecs(CodecFactory codecs) {
    this.codecs = Objects.requireNonNull(codecs, "a durable loch needs codecs");
    return this;
  }

  /** Serialises with Jackson, which is what most applications want. */
  public JdbcLochConfig<A> jackson(ObjectMapper mapper) {
    return codecs(new JacksonCodecFactory(mapper));
  }

  /**
   * Squeezes the serialised value before it is encrypted.
   *
   * <p><b>Compression comes first, because ciphertext does not compress.</b> Reversing the two
   * would cost the same CPU and save nothing.
   *
   * <p>The label is deliberately <i>not</i> compressed. Labels are small and highly structured, so
   * there is little to win, and compressing before encrypting makes the ciphertext length a
   * function of the plaintext -- the shape of attack CRIME and BREACH exploit. For a stored value
   * that is a remote concern; for a short, structured, guessable label it is less remote, and the
   * saving did not justify it.
   */
  public JdbcLochConfig<A> compressedWith(Codec<byte[]> compression) {
    this.compression = Objects.requireNonNull(compression, "compression must not be null");
    return this;
  }

  /** Gzip, the ordinary choice. */
  public JdbcLochConfig<A> gzipped() {
    return compressedWith(new GzipCodec());
  }

  /**
   * What protects those bytes at rest -- an {@code EnvelopeCodec}, ordinarily.
   *
   * <p>Required, or say {@link #unprotected()}. There is no default, for the same reason there is
   * no default auditor: a store that silently keeps plaintext still looks like a vault.
   */
  public JdbcLochConfig<A> protectedBy(Codec<byte[]> protection) {
    this.protection = Objects.requireNonNull(protection, "protection must not be null");
    return this;
  }

  /** Stores plaintext, on purpose and in writing. For a throwaway database, never for real data. */
  public JdbcLochConfig<A> unprotected() {
    this.protection =
        new Codec<>() {
          @Override
          public byte[] encode(byte[] value) {
            return value;
          }

          @Override
          public byte[] decode(byte[] value) {
            return value;
          }
        };
    return this;
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
  public JdbcLochConfig<A> check(Check<A, ?, ?> check) {
    super.check(check);
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

  Codec<byte[]> protectionOrFail() {
    if (protection == null) {
      throw new IllegalStateException(
          "a durable loch needs protection: call protectedBy(...) with an EnvelopeCodec, or"
              + " unprotected() if this database holds nothing that matters");
    }
    return protection;
  }

  Codec<byte[]> compression() {
    return compression;
  }

  boolean migrates() {
    return migrate;
  }
}
