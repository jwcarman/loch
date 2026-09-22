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
 * Where a store keeps things.
 *
 * <p><b>Storage only.</b> No policy lives here: the gate, the axes, the registries and the audit
 * are decided once in {@code Engine} and shared by every implementation. A second copy of a
 * security decision is a second chance to get it wrong, and the two would drift.
 *
 * <p>An implementation must be safe to use from several threads.
 */
public interface Storage {

  /**
   * Keeps a value, and the record that it was kept, as one indivisible act.
   *
   * <p>Together on purpose. Written separately, a failure between them leaves either a value
   * nothing accounts for or an account of a value that does not exist, and the second is worse: it
   * is evidence of something that never happened.
   */
  void put(String id, StoredValue value, AuditRecord record);

  /**
   * Keeps a record of something that stored no value: a read, a question, anything refused.
   *
   * <p>Most of the trail is this. A refusal produces no value at all, and refusals are what an
   * auditor came to look at.
   */
  void record(AuditRecord record);

  /** The label, the lineage and what it was stored as -- without decoding the value. */
  Optional<StoredMetadata> metadata(String id);

  /**
   * The value, decoded as the caller says it is.
   *
   * <p>Only ever called once {@link #metadata} has confirmed the stored type name matches, so the
   * type here is a verified fact rather than a claim being trusted.
   */
  <T> Optional<T> value(String id, TypeRef<T> type);

  /**
   * Several labels at once, for an operation reading several values.
   *
   * <p>A fold over ten parents was ten round trips before this, then ten more for the values. The
   * default keeps that behaviour so an implementation need not care; a durable one should.
   *
   * <p>Ids it is not holding are simply absent from the result, which is what lets one missing
   * parent be reported without a second lookup to find out which.
   */
  default java.util.Map<String, StoredMetadata> metadata(java.util.List<String> ids) {
    java.util.Map<String, StoredMetadata> found = new java.util.LinkedHashMap<>();
    for (String id : ids) {
      metadata(id).ifPresent(entry -> found.put(id, entry));
    }
    return found;
  }

  /**
   * Several values at once, each read as the type its caller expects.
   *
   * <p>Separate from {@link #metadata(java.util.List)} on purpose, and called after it. A label is
   * checked before a payload is decrypted, so a read that is going to be refused never decrypts
   * anything -- merging the two would be one fewer round trip and one more place plaintext exists.
   */
  default java.util.Map<String, Object> values(java.util.Map<String, TypeRef<?>> wanted) {
    java.util.Map<String, Object> found = new java.util.LinkedHashMap<>();
    wanted.forEach((id, type) -> value(id, type).ifPresent(value -> found.put(id, value)));
    return found;
  }

  boolean contains(String id);

  /**
   * Removes a value and everything derived from it, however deeply.
   *
   * <p>"Erase this customer" is a reachability question, which is why lineage is kept. A derived
   * value is made of its parents, so leaving descendants behind after erasing a root leaves the
   * data that was asked to be gone.
   *
   * @return how many values were removed, the root included
   */
  int erase(String root);
}
