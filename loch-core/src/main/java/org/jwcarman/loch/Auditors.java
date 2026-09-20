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

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/** Ready-made {@link Auditor}s. */
public final class Auditors {

  private Auditors() {}

  /**
   * Keeps everything in a list.
   *
   * <p>For tests and for looking at what a policy actually did before deploying it. It grows
   * without limit, so it is not the thing to leave running.
   */
  public static Recording recording() {
    return new Recording();
  }

  /**
   * Throws it all away.
   *
   * <p>Exists so that discarding the audit trail is something an application <b>says</b>, in code a
   * reviewer can find, rather than something it gets by not configuring anything.
   */
  public static Auditor discarding() {
    return record -> {};
  }

  /** An auditor that remembers, for tests. */
  public static final class Recording implements Auditor {

    private final List<AuditRecord> records = new CopyOnWriteArrayList<>();

    @Override
    public void record(AuditRecord record) {
      records.add(record);
    }

    public List<AuditRecord> records() {
      return List.copyOf(records);
    }

    public List<AuditRecord> of(AuditRecord.Operation operation) {
      List<AuditRecord> matching = new ArrayList<>();
      for (AuditRecord record : records) {
        if (record.operation() == operation) {
          matching.add(record);
        }
      }
      return List.copyOf(matching);
    }

    public void clear() {
      records.clear();
    }
  }
}
