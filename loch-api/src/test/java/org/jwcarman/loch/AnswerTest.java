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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

@DisplayName("An answer")
class AnswerTest {

  @Nested
  @DisplayName("that ran and said yes")
  class ThatRanAndSaidYes {

    private final Answer answer = new Answer.Answered(true);

    @Test
    @DisplayName("is true")
    void is_true() {
      assertThat(answer.isTrue()).isTrue();
    }

    @Test
    @DisplayName("is not false")
    void is_not_false() {
      assertThat(answer.isFalse()).isFalse();
    }

    @Test
    @DisplayName("ran")
    void ran() {
      assertThat(answer.ran()).isTrue();
    }

    @Test
    @DisplayName("hands back its value rather than throwing")
    void hands_back_its_value() {
      assertThat(answer.orThrow()).isTrue();
    }
  }

  @Nested
  @DisplayName("that ran and said no")
  class ThatRanAndSaidNo {

    private final Answer answer = new Answer.Answered(false);

    @Test
    @DisplayName("is not true")
    void is_not_true() {
      assertThat(answer.isTrue()).isFalse();
    }

    @Test
    @DisplayName("is false")
    void is_false() {
      assertThat(answer.isFalse()).isTrue();
    }

    @Test
    @DisplayName("ran")
    void ran() {
      assertThat(answer.ran()).isTrue();
    }
  }

  /**
   * A refusal is neither a yes nor a no -- collapsing it into either would be mistaking "I would
   * not look" for an answer.
   */
  @Nested
  @DisplayName("that was refused")
  class ThatWasRefused {

    private final Answer answer =
        new Answer.Refused(Answer.Reason.ABOVE_CEILING, "clearance too low");

    @Test
    @DisplayName("is not true")
    void is_not_true() {
      assertThat(answer.isTrue()).isFalse();
    }

    @Test
    @DisplayName("is not false")
    void is_not_false() {
      assertThat(answer.isFalse()).isFalse();
    }

    @Test
    @DisplayName("did not run")
    void did_not_run() {
      assertThat(answer.ran()).isFalse();
    }

    @Test
    @DisplayName("throws an exception naming the reason and detail rather than answering")
    void throws_naming_the_reason_and_detail() {
      assertThatThrownBy(answer::orThrow)
          .isInstanceOf(AccessDeniedException.class)
          .hasMessageContaining("ABOVE_CEILING")
          .hasMessageContaining("clearance too low");
    }
  }
}
