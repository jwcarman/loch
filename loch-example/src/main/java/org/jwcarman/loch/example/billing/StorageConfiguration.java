/*
 * Copyright © ${year} James Carman
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
package org.jwcarman.loch.example.billing;

import java.util.Map;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import org.jwcarman.codec.crypto.EnvelopeCodec;
import org.jwcarman.codec.crypto.JceDataKeyProvider;
import org.jwcarman.codec.transform.compress.GzipCodec;
import org.jwcarman.loch.jdbc.Compression;
import org.jwcarman.loch.jdbc.StorageCodec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * How values are recorded and protected on their way to disk.
 *
 * <p>Separate from {@link CharterConfiguration} for a reason worth knowing about: that class
 * declares capabilities in its constructor, so it has to <i>consume</i> these two beans. A
 * configuration class cannot declare a bean and take it as a constructor parameter -- Spring
 * reports a circular reference and the context does not start. Declaring in a constructor means the
 * things declared depends on live somewhere else.
 */
@Configuration
public class StorageConfiguration {

  private static final Logger log = LoggerFactory.getLogger(StorageConfiguration.class);

  @Bean
  public StorageCodec storageCodec(@Value("${billing.key}") String key) {
    SecretKey kek = new SecretKeySpec(java.util.Base64.getDecoder().decode(key), "AES");
    return StorageCodec.of(
        Compression.whenItHelps(new GzipCodec())
            .andThen(
                EnvelopeCodec.builder(new JceDataKeyProvider("k1", Map.of("k1", kek))).build()));
  }
}
