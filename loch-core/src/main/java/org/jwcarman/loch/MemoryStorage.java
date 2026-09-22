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
public final class MemoryStorage implements Storage {

  private final java.util.List<AuditRecord> audit =
      java.util.Collections.synchronizedList(new java.util.ArrayList<>());

  /** Everything recorded so far, oldest first. The in-memory equivalent of the audit table. */
  public java.util.List<AuditRecord> audit() {
    return java.util.List.copyOf(audit);
  }

  /** Just the lines for one kind of operation, oldest first. */
  public java.util.List<AuditRecord> audit(AuditRecord.Operation operation) {
    return audit().stream().filter(entry -> entry.operation() == operation).toList();
  }

  /**
   * Forgets the trail so far.
   *
   * <p>For tests that want to watch one operation without the setup that preceded it. A durable
   * store has no equivalent, and deliberately: the trail is the thing that must not be erasable.
   */
  public void clearAudit() {
    audit.clear();
  }

  @Override
  public void record(AuditRecord entry) {
    audit.add(entry);
  }

  private final Map<String, StoredValue> values = new ConcurrentHashMap<>();

  @Override
  public void put(String id, StoredValue value, AuditRecord record) {
    record(record);
    values.put(id, value);
  }

  @Override
  public Optional<StoredMetadata> metadata(String id) {
    return Optional.ofNullable(values.get(id))
        .map(stored -> new StoredMetadata(stored.type().name(), stored.label(), stored.lineage()));
  }

  @Override
  public <T> Optional<T> value(String id, TypeRef<T> type) {
    return Optional.ofNullable(values.get(id)).map(stored -> type.rawClass().cast(stored.value()));
  }

  /** Everything currently held, for tests that need to prove something was not stored. */
  public java.util.Set<String> everything() {
    return java.util.Set.copyOf(values.keySet());
  }

  @Override
  public boolean contains(String id) {
    return values.containsKey(id);
  }

  @Override
  public List<String> erase(String root) {
    Set<String> doomed = new HashSet<>();
    Deque<String> pending = new ArrayDeque<>();
    pending.add(root);
    while (!pending.isEmpty()) {
      String next = pending.removeFirst();
      if (!doomed.add(next)) {
        continue;
      }
      List<String> children = new ArrayList<>();
      values.forEach(
          (id, stored) -> {
            if (stored.lineage().parents().contains(next)) {
              children.add(id);
            }
          });
      pending.addAll(children);
    }
    List<String> removed = new ArrayList<>();
    for (String id : doomed) {
      if (values.remove(id) != null) {
        removed.add(id);
      }
    }
    return removed;
  }
}
