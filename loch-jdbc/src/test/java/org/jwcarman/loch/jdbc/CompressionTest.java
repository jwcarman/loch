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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.jwcarman.codec.Codec;
import org.jwcarman.codec.transform.compress.GzipCodec;

/** No database needed: {@link Compression} is a plain byte transform. */
@DisplayName("Compression that cannot make things worse")
class CompressionTest {

  private final Codec<byte[]> codec = Compression.whenItHelps(new GzipCodec());

  @Test
  @DisplayName("round-trips a value that compresses well, and marks it as compressed")
  void round_trips_a_compressible_value() {
    byte[] original = "a ".repeat(500).getBytes(StandardCharsets.UTF_8);

    byte[] encoded = codec.encode(original);

    assertThat(codec.decode(encoded)).isEqualTo(original);
    assertThat(encoded.length).isLessThan(original.length);
  }

  @Test
  @DisplayName("round-trips a value gzip would only make bigger, stored as it was instead")
  void round_trips_an_incompressible_value() {
    byte[] original = {1, 2, 3, 4};

    byte[] encoded = codec.encode(original);

    assertThat(codec.decode(encoded)).isEqualTo(original);
    // One marker byte and nothing else: gzip's own framing would have cost more than four
    // bytes ever save, so the marker says "stored" and the body is exactly what came in.
    assertThat(encoded.length).isEqualTo(original.length + 1);
  }

  @Test
  @DisplayName("refuses to decode an empty payload, which no encoder here ever produces")
  void refuses_an_empty_payload() {
    assertThatThrownBy(() -> codec.decode(new byte[0]))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("cannot be empty");
  }

  @Test
  @DisplayName("refuses a marker byte no encoder here ever wrote")
  void refuses_an_unknown_marker() {
    byte[] foreign = {9, 1, 2, 3};

    assertThatThrownBy(() -> codec.decode(foreign))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("unknown compression marker");
  }
}
