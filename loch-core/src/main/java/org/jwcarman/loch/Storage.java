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

import java.util.Optional;
import org.jwcarman.codec.spi.TypeRef;

/**
 * Where a loch keeps things.
 *
 * <p><b>Storage only.</b> No policy lives here: the gate, the lattice, the registries and the audit
 * are decided once in {@code DefaultLoch} and shared by every implementation. A second copy of a
 * security decision is a second chance to get it wrong, and the two would drift.
 *
 * <p>An implementation must be safe to use from several threads.
 *
 * @param <A> the application's attribution type
 */
public interface Storage<A> {

  /** Keeps a value. Ids are unique, and a deterministic derivation may store the same one twice. */
  void put(HeldId id, StoredValue<A> value);

  /** The label, the lineage and what it was stored as -- without decoding the value. */
  Optional<StoredMetadata<A>> metadata(HeldId id);

  /**
   * The value, decoded as the caller says it is.
   *
   * <p>Only ever called once {@link #metadata} has confirmed the stored type name matches, so the
   * type here is a verified fact rather than a claim being trusted.
   */
  <T> Optional<T> value(HeldId id, TypeRef<T> type);

  boolean contains(HeldId id);

  /**
   * Removes a value and everything derived from it, however deeply.
   *
   * <p>"Erase this customer" is a reachability question, which is why lineage is kept. A derived
   * value is made of its parents, so leaving descendants behind after erasing a root leaves the
   * data that was asked to be gone.
   *
   * @return how many values were removed, the root included
   */
  int erase(HeldId root);
}
