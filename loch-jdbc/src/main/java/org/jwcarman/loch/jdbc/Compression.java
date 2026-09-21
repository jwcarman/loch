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

import java.util.Arrays;
import org.jwcarman.codec.spi.Codec;

/**
 * Compression that cannot make things worse.
 *
 * <p><b>Measured, not assumed.</b> Most of what a store holds is small -- a card number, an account
 * record, an email address -- and a compressor's framing costs more than a short payload saves:
 *
 * <pre>
 * {"number":"4111111111114821","holder":"J CARMAN"}   49 bytes -> gzip 57   BIGGER
 * {"v":"4821"}                                        12 bytes -> gzip 32   BIGGER
 * an email body                                      851 bytes -> gzip 79   smaller
 * </pre>
 *
 * <p>So compressing everything is wrong, and compressing nothing leaves the large values on the
 * table. This wraps any compressor, keeps its result only when it actually shrank, and marks which
 * it did with a leading byte. The worst case is one byte rather than a threefold expansion.
 *
 * <pre>{@code
 * Compression.whenItHelps(new GzipCodec())   // or ZstdCodec, or Lz4Codec
 * }</pre>
 *
 * <p>The marker byte and the stored length together say roughly how compressible a value was, which
 * is the shape of thing CRIME and BREACH exploit. It is a remote concern for data at rest with a
 * distinct key per value and no oracle to query -- and it is why labels are not compressed at all.
 */
public final class Compression {

  private static final byte STORED = 0;
  private static final byte COMPRESSED = 1;

  private Compression() {}

  /** Applies a compressor only when the result is smaller. */
  public static Codec<byte[]> whenItHelps(Codec<byte[]> compressor) {
    return new Codec<>() {
      @Override
      public byte[] encode(byte[] value) {
        byte[] squeezed = compressor.encode(value);
        boolean worth = squeezed.length < value.length;
        byte[] chosen = worth ? squeezed : value;
        byte[] framed = new byte[chosen.length + 1];
        framed[0] = worth ? COMPRESSED : STORED;
        System.arraycopy(chosen, 0, framed, 1, chosen.length);
        return framed;
      }

      @Override
      public byte[] decode(byte[] value) {
        if (value.length == 0) {
          throw new IllegalArgumentException("a compressed payload cannot be empty");
        }
        byte[] body = Arrays.copyOfRange(value, 1, value.length);
        return switch (value[0]) {
          case STORED -> body;
          case COMPRESSED -> compressor.decode(body);
          default ->
              throw new IllegalArgumentException(
                  "unknown compression marker "
                      + value[0]
                      + "; this payload was not written by"
                      + " this codec");
        };
      }
    };
  }
}
