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

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.jwcarman.codec.Codec;
import org.jwcarman.codec.CodecFactory;
import org.jwcarman.codec.TypeRef;
import org.jwcarman.codec.jackson.JacksonCodecFactory;
import tools.jackson.databind.json.JsonMapper;

/** No database needed: {@link StorageCodec#after(CodecFactory)} composes two other seams. */
@DisplayName("What happens to every byte a store keeps")
class StorageCodecTest {

  /** Reverses its bytes, so which side ran first is visible in the result rather than assumed. */
  private static final class ReversingCodec implements Codec<byte[]> {
    @Override
    public byte[] encode(byte[] value) {
      return reversed(value);
    }

    @Override
    public byte[] decode(byte[] value) {
      return reversed(value);
    }

    private static byte[] reversed(byte[] value) {
      byte[] out = new byte[value.length];
      for (int i = 0; i < value.length; i++) {
        out[i] = value[value.length - 1 - i];
      }
      return out;
    }
  }

  /**
   * Every codec the factory makes, with the storage codec applied after it: the base serialises,
   * and only then does whatever the store keeps happen to the bytes.
   */
  @Test
  @DisplayName(
      "applies after whatever the base factory already does, and undoes it on the way back")
  void applies_after_the_base_factory() {
    StorageCodec storage = StorageCodec.of(new ReversingCodec());
    CodecFactory base = new JacksonCodecFactory(JsonMapper.builder().build());

    byte[] fromBaseAlone = base.create(TypeRef.of(String.class)).encode("hi");
    byte[] expected = ReversingCodec.reversed(fromBaseAlone);

    Codec<String> composed = storage.after(base).create(TypeRef.of(String.class));
    byte[] encoded = composed.encode("hi");

    // Reversed relative to what the base alone would have written, which is only true if the
    // storage codec ran AFTER serialisation rather than before it.
    assertThat(encoded).isEqualTo(expected);
    assertThat(composed.decode(encoded)).isEqualTo("hi");
  }
}
