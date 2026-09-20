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

import java.util.function.Consumer;
import org.jwcarman.loch.DefaultLoch;
import org.jwcarman.loch.Loch;

/**
 * A loch that keeps its values in a database, encrypted.
 *
 * <pre>{@code
 * Loch<Billing> loch = JdbcLoch.create(Billing.class, c -> c
 *     .dataSource(dataSource)
 *     .jackson(objectMapper)
 *     .gzipped()
 *     .protectedBy(EnvelopeCodec.builder(keys).build())
 *     .lattice(BILLING)
 *     .auditor(auditSink)
 *     .destination(...));
 * }</pre>
 *
 * <p>It makes exactly the same decisions an in-memory loch does, because both run the same gate.
 * What changes is that values survive a restart, are encrypted at rest, and can be erased along
 * with everything ever derived from them.
 */
public final class JdbcLoch {

  private JdbcLoch() {}

  /**
   * Builds one.
   *
   * @param attributionType the application's label record, which has to be serialised like any
   *     other value because labels are stored encrypted too
   */
  public static <A> Loch<A> create(
      Class<A> attributionType, Consumer<JdbcLochConfig<A>> customizer) {
    JdbcLochConfig<A> config = new JdbcLochConfig<>();
    customizer.accept(config);
    JdbcStorage<A> storage =
        new JdbcStorage<>(
            config.dataSourceOrFail(),
            config.codecsOrFail(),
            config.compression(),
            config.protectionOrFail(),
            attributionType);
    if (config.migrates()) {
      storage.migrate();
    }
    return new DefaultLoch<>(config, storage);
  }
}
