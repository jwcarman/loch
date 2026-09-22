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

/**
 * {@link SurrogateName} carries no logic of its own -- it is read by {@link SurrogateType#of(
 * Class)}, so its promise is that a value stored under an explicit name survives at runtime and
 * wins over the default kebab-casing. {@link SurrogateTypeTest} covers the winning; this covers the
 * surviving.
 */
@DisplayName("@SurrogateName")
class SurrogateNameTest {

  @SurrogateName("billing.invoice/v1")
  private record Annotated() {}

  private record Unannotated() {}

  @Test
  @DisplayName("is readable at runtime, carrying the value it was given")
  void is_readable_at_runtime() {
    SurrogateName name = Annotated.class.getAnnotation(SurrogateName.class);

    assertThat(name.value()).isEqualTo("billing.invoice/v1");
  }

  @Test
  @DisplayName("is absent from a type that never declared it")
  void is_absent_from_an_undeclared_type() {
    assertThat(Unannotated.class.getAnnotation(SurrogateName.class)).isNull();
  }
}
