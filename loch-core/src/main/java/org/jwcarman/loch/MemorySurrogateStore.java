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
package org.jwcarman.loch;

import java.util.function.Consumer;

/**
 * A store that keeps everything in memory.
 *
 * <p>For tests, for single-process tools, and for working out whether a policy is right before a
 * database is involved. It does not encrypt and does not survive a restart, so it is not the thing
 * to put in front of real cardholder data -- but it enforces the gate exactly as a durable store
 * does, because both use the same one.
 *
 * @see MemoryStorage for the mutable-value hazard this implementation has and a durable one does
 *     not
 */
public final class MemorySurrogateStore {

  private MemorySurrogateStore() {}

  /** Builds one. The customizer is where the axes, destinations and portals are declared. */
  public static <D> SurrogateStore create(Consumer<SurrogateStoreConfig<D>> customizer) {
    SurrogateStoreConfig<D> config = new SurrogateStoreConfig<>();
    customizer.accept(config);
    return create(config);
  }

  /**
   * Builds one from a config that has already been filled in.
   *
   * <p>The form to use when capabilities are being minted, because a lambda cannot assign to a
   * local: configure, mint into plain final variables, then build.
   *
   * <pre>{@code
   * SurrogateStoreConfig<Billing, BillingValue> c = new SurrogateStoreConfig<>();
   * c.axes(TENANT, INTEGRITY, SENSITIVITY);
   * SurrogateSource<Mail> mail = c.source(CUSTOMER_MAIL, Mail.class, ctx -> ...);
   * SurrogateStore<Billing> store = MemorySurrogateStore.create(c);
   * }</pre>
   */
  public static <D> SurrogateStore create(SurrogateStoreConfig<D> config) {
    return new DefaultSurrogateStore(config, new MemoryStorage());
  }
}
