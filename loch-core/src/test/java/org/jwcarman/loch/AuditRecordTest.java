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

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** One line of the record, readable on its own rather than only through a store's audit sink. */
@DisplayName("An audit record")
class AuditRecordTest {

  private AuditRecord allowed(Map<String, String> context) {
    return new AuditRecord(
        AuditRecord.Operation.REVEAL,
        "sur_1",
        Optional.of("vendor-llm"),
        AuditRecord.Outcome.ALLOWED,
        Optional.empty(),
        Optional.empty(),
        Optional.empty(),
        context);
  }

  @Test
  @DisplayName("says who asked, when the caller contributed something")
  void says_who_asked_when_the_caller_contributed_something() {
    AuditRecord entry = allowed(Map.of("tenant", "acme"));

    assertThat(entry.toString()).contains("by {tenant=acme}");
  }

  @Test
  @DisplayName("says nothing extra when nobody in particular was asking")
  void says_nothing_extra_when_nobody_was_asking() {
    AuditRecord entry = allowed(Map.of());

    assertThat(entry.toString()).doesNotContain("by {");
  }
}
