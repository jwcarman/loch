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
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * A manifest is read, not acted on, so its own behaviour is small: an entry knows what it touches,
 * and the whole report can be narrowed to one type.
 */
@DisplayName("A manifest")
class ManifestTest {

  private static final Manifest.Entry SOURCE =
      new Manifest.Entry("card-intake", "accepts a card", false, List.of(), "card");
  private static final Manifest.Entry DERIVATION =
      new Manifest.Entry("card.last4", "card -> last4", true, List.of("card"), "last4");
  private static final Manifest.Entry DESTINATION =
      new Manifest.Entry("vendor-llm", "accepts up to {}", false, List.of("last4"), null);
  private static final Manifest.Entry QUESTION =
      new Manifest.Entry("card.startsWith", "asks about card", false, List.of("card"), null);

  @Nested
  @DisplayName("an entry")
  class AnEntry {

    @Test
    @DisplayName("touches the type it writes")
    void touches_the_type_it_writes() {
      assertThat(SOURCE.touches("card")).isTrue();
    }

    @Test
    @DisplayName("touches a type it reads")
    void touches_a_type_it_reads() {
      assertThat(DERIVATION.touches("card")).isTrue();
    }

    @Test
    @DisplayName("does not touch a type it neither reads nor writes")
    void does_not_touch_an_unrelated_type() {
      assertThat(SOURCE.touches("invoice")).isFalse();
      assertThat(DERIVATION.touches("invoice")).isFalse();
    }
  }

  @Nested
  @DisplayName("narrowed to one type")
  class NarrowedToOneType {

    private final Manifest manifest =
        new Manifest(
            "{}",
            List.of(SOURCE),
            List.of(DESTINATION),
            List.of(DERIVATION),
            List.of(QUESTION),
            List.of(
                new Manifest.Finding(
                    "no-writer", "some-door", "reads 'card', which nothing produces"),
                new Manifest.Finding(
                    "no-reader", "unrelated", "writes 'invoice', which nothing reads")),
            AccessContext.empty());

    @Test
    @DisplayName("keeps only the sources, destinations, derivations and questions that touch it")
    void keeps_only_what_touches_it() {
      Manifest about = manifest.about("card");

      assertThat(about.sources()).containsExactly(SOURCE);
      assertThat(about.derivations()).containsExactly(DERIVATION);
      assertThat(about.questions()).containsExactly(QUESTION);
    }

    @Test
    @DisplayName("keeps a destination that reads the type even though it never writes one")
    void keeps_a_destination_that_reads_the_type() {
      Manifest about = manifest.about("last4");

      assertThat(about.destinations()).containsExactly(DESTINATION);
      assertThat(about.sources()).isEmpty();
    }

    @Test
    @DisplayName("keeps only the findings that mention it")
    void keeps_only_the_findings_that_mention_it() {
      Manifest about = manifest.about("card");

      assertThat(about.findings()).extracting(Manifest.Finding::about).containsExactly("some-door");
    }

    @Test
    @DisplayName("drops everything when nothing touches the type asked about")
    void drops_everything_for_an_unrelated_type() {
      Manifest about = manifest.about("nothing-declares-this");

      assertThat(about.sources()).isEmpty();
      assertThat(about.derivations()).isEmpty();
      assertThat(about.findings()).isEmpty();
    }
  }

  @Test
  @DisplayName("says nothing may be revealed anywhere when it has no destinations")
  void says_nothing_may_be_revealed_with_no_destinations() {
    Manifest manifest =
        new Manifest(
            "{}",
            List.of(SOURCE),
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            AccessContext.empty());

    assertThat(manifest.toString()).contains("nothing may be dereferenced anywhere");
  }
}
