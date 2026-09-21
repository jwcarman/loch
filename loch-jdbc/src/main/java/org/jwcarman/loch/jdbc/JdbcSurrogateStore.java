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

import org.jwcarman.loch.DefaultSurrogateStore;
import org.jwcarman.loch.SurrogateStore;
import org.jwcarman.loch.SurrogateStoreConfig;

/**
 * A store that keeps its values in a database, encrypted.
 *
 * <pre>{@code
 * SurrogateStore<Billing> store = JdbcSurrogateStore.create(Billing.class, c -> c
 *     .dataSource(dataSource)
 *     .codecs(new JacksonCodecFactory(mapper))
 *     .storedThrough(StorageCodec.of(
 *         Compression.whenItHelps(new GzipCodec())
 *             .andThen(EnvelopeCodec.builder(keys).build())))
 *     .lattice(BILLING)
 *     .auditor(auditSink)
 *     .destination(...));
 * }</pre>
 *
 * <p>It makes exactly the same decisions an in-memory store does, because both run the same gate.
 * What changes is that values survive a restart, are encrypted at rest, and can be erased along
 * with everything ever derived from them.
 */
public final class JdbcSurrogateStore {

  private JdbcSurrogateStore() {}

  /**
   * Builds one from what the application allows and where it is being kept.
   *
   * <p>Two halves, deliberately. {@code config} is policy -- the labels, the doors, who may reach
   * them -- and the code declaring portals compiles against it without knowing where anything ends
   * up. {@code jdbc} is the other half: the tables, the serialisation, the sealing.
   *
   * <p>This call is also the moment the access space is fixed. Every capability declared on the
   * configuration before now is attached; anything declared afterwards reaches nothing.
   */
  public static SurrogateStore create(SurrogateStoreConfig config, JdbcSurrogateStoreConfig jdbc) {
    JdbcStorage storage =
        new JdbcStorage(
            jdbc.dataSourceOrFail(),
            jdbc.codecsOrFail(),
            jdbc.storageCodecOrFail(),
            config.declaredAxes());
    if (jdbc.migrates()) {
      storage.migrate();
    }
    return new DefaultSurrogateStore(config, storage);
  }
}
