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

import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@link MemoryStorage} on its own, beneath the policy {@link Engine} layers over it -- the SPI
 * contract itself, rather than what a charter does with it.
 */
@DisplayName("MemoryStorage on its own")
class MemoryStorageTest {

  @Test
  @DisplayName("erasing a root nobody wrote removes nothing and writes no line")
  void erasing_a_root_nobody_wrote_removes_nothing() {
    MemoryStorage storage = new MemoryStorage();

    var removed =
        storage.erase(
            "sur_never-written",
            id ->
                new AuditRecord(
                    AuditRecord.Operation.ERASE,
                    id,
                    Optional.empty(),
                    AuditRecord.Outcome.ALLOWED,
                    Optional.empty(),
                    Optional.empty(),
                    Optional.empty(),
                    java.util.Map.of()));

    assertThat(removed).isEmpty();
    assertThat(storage.audit()).isEmpty();
  }
}
