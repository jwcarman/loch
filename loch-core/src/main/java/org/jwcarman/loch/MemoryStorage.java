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

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.jwcarman.codec.spi.TypeRef;

/**
 * Storage in a map.
 *
 * <p>For tests, single-process tools, and proving a policy before a database is involved. It does
 * not encrypt and does not survive a restart.
 *
 * <p><b>It stores references, not copies.</b> A durable store serialises on the way in and hands
 * back a fresh object every time; this one does not, so a caller that mutates a value after holding
 * it changes what was stored under a label chosen for what it used to be. Hold immutable values and
 * the difference never shows.
 */
public final class MemoryStorage<A> implements Storage<A> {

  private final Map<HeldId, StoredValue<A>> values = new ConcurrentHashMap<>();
  private final Map<String, HeldId> byDedupeKey = new ConcurrentHashMap<>();

  @Override
  public void put(HeldId id, StoredValue<A> value, Optional<String> dedupeKey) {
    values.put(id, value);
    dedupeKey.ifPresent(key -> byDedupeKey.putIfAbsent(key, id));
  }

  @Override
  public Optional<HeldId> findByDedupeKey(String dedupeKey) {
    return Optional.ofNullable(byDedupeKey.get(dedupeKey));
  }

  @Override
  public Optional<StoredMetadata<A>> metadata(HeldId id) {
    return Optional.ofNullable(values.get(id))
        .map(
            stored ->
                new StoredMetadata<>(
                    stored.type().getType().getTypeName(), stored.attribution(), stored.lineage()));
  }

  @Override
  public <T> Optional<T> value(HeldId id, TypeRef<T> type) {
    return Optional.ofNullable(values.get(id)).map(stored -> type.rawClass().cast(stored.value()));
  }

  @Override
  public boolean contains(HeldId id) {
    return values.containsKey(id);
  }

  @Override
  public int erase(HeldId root) {
    Set<HeldId> doomed = new HashSet<>();
    Deque<HeldId> pending = new ArrayDeque<>();
    pending.add(root);
    while (!pending.isEmpty()) {
      HeldId next = pending.removeFirst();
      if (!doomed.add(next)) {
        continue;
      }
      List<HeldId> children = new ArrayList<>();
      values.forEach(
          (id, stored) -> {
            if (stored.lineage().parents().contains(next)) {
              children.add(id);
            }
          });
      pending.addAll(children);
    }
    int removed = 0;
    for (HeldId id : doomed) {
      if (values.remove(id) != null) {
        removed++;
      }
    }
    byDedupeKey.values().removeIf(doomed::contains);
    return removed;
  }
}
