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

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.jwcarman.loch.lattice.Exact;
import org.jwcarman.loch.lattice.Lattices;

/**
 * A derivation says how many values it reads, and the engine believes it before it reads any.
 *
 * <p>Handing a one-at-a-time derivation three handles is a mistake in the calling code, not a
 * decision about whether the access is allowed. The two must not arrive looking alike: a refusal is
 * something the caller is expected to handle, and quietly turning a bug into one buries it.
 */
@DisplayName("Arity")
class ArityTest {

  private static final DerivationId ONE_AT_A_TIME = DerivationId.of("one");
  private static final DerivationId A_FOLD = DerivationId.of("fold");

  private final AtomicInteger unaryRuns = new AtomicInteger();

  private final LochConfig<Exact<String>> config = new LochConfig<>();

  private final Derivation<String, String> oneAtATime =
      config
          .lattice(Lattices.<String>exact())
          .withoutAudit()
          .derivation(
              ONE_AT_A_TIME,
              String.class,
              String.class,
              value -> {
                unaryRuns.incrementAndGet();
                return value.toUpperCase();
              })
          .acceptingAnything()
          .mint();

  private final Fold<String, String> aFold =
      config
          .fold(A_FOLD, String.class, String.class, values -> String.join("+", values))
          .acceptingAnything()
          .mint();

  private final Loch<Exact<String>> loch = MemoryLoch.create(config);

  private final Handle<String> first = loch.hold("a", String.class, Exact.of("acme"));
  private final Handle<String> second = loch.hold("b", String.class, Exact.of("acme"));

  /**
   * Arity is a compile-time fact now, so the old runtime mismatch cannot be written. What remains
   * worth asserting is that a fold is happy with any number, and that none is a refusal rather than
   * a bug: nothing having arrived is data, not a mistake in the caller.
   */
  @Test
  @DisplayName("of one is fine for a fold, which is what folding over one value means")
  void one_is_fine_for_a_fold() {
    assertThat(aFold.fold(List.of(first)).succeeded()).isTrue();
  }

  @Test
  @DisplayName("of several is what a fold is for")
  void several_is_what_a_fold_is_for() {
    assertThat(aFold.fold(List.of(first, second)).succeeded()).isTrue();
  }

  @Test
  @DisplayName("of none is a refusal, not a bug: an empty list is data, not a mistake")
  void none_is_a_refusal() {
    assertThat(aFold.fold(List.of()).made()).isEmpty();
  }
}
