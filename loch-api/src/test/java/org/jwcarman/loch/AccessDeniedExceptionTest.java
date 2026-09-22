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

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("An access-denied exception")
class AccessDeniedExceptionTest {

  @Test
  @DisplayName("reports the reason a caller can log, and a message combining reason and detail")
  void reports_the_reason_it_was_constructed_with() {
    AccessDeniedException exception =
        new AccessDeniedException("ABOVE_CEILING", "clearance too low");

    assertThat(exception.reason()).isEqualTo("ABOVE_CEILING");
    assertThat(exception.getMessage()).isEqualTo("ABOVE_CEILING: clearance too low");
  }

  @Test
  @DisplayName("takes its reason from a gate's own Reason enum by name")
  void takes_its_reason_from_a_gates_reason_enum() {
    AccessDeniedException exception =
        new AccessDeniedException(Revealed.Reason.WRONG_TYPE, "not a card");

    assertThat(exception.reason()).isEqualTo("WRONG_TYPE");
    assertThat(exception.getMessage()).isEqualTo("WRONG_TYPE: not a card");
  }
}
