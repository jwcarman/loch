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

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.jwcarman.loch.lattice.Axis;
import org.jwcarman.loch.lattice.Ceiling;
import org.jwcarman.loch.lattice.Constraint;
import org.jwcarman.loch.lattice.Label;

/**
 * What a charter refuses while it is being written, what it hands back about itself, and what its
 * portals say when printed -- none of it a decision, all of it worth being right.
 */
@DisplayName("A charter's own bookkeeping")
class DefaultCharterInternalsTest {

  private static final SurrogateType<String> STRING_TYPE = SurrogateType.of(String.class);
  private static final SurrogateType<Integer> INT_TYPE = SurrogateType.of(Integer.class);
  private static final Axis<String> TENANT = Axis.matching("tenant");

  @Nested
  @DisplayName("sealing")
  class Sealing {

    @Test
    @DisplayName("refuses to be sealed twice")
    void refuses_to_be_sealed_twice() {
      DefaultCharter charter = new DefaultCharter(TENANT);
      charter.seal(new MemoryStorage());

      assertThatThrownBy(() -> charter.seal(new MemoryStorage()))
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("already sealed");
    }

    /**
     * The one true race: two threads both observe {@code Configuring} before either publishes, both
     * build a configuration, and only one write can win the compare-and-set. The loser must say so
     * rather than silently discard its engine.
     */
    @Test
    @DisplayName("refuses when two threads race to seal it at once")
    void refuses_when_two_threads_race_to_seal_it() throws InterruptedException {
      DefaultCharter charter = new DefaultCharter(TENANT);
      CountDownLatch ready = new CountDownLatch(2);
      CountDownLatch go = new CountDownLatch(1);
      AtomicInteger failures = new AtomicInteger();
      AtomicInteger successes = new AtomicInteger();
      Runnable sealAttempt =
          () -> {
            ready.countDown();
            awaitUninterruptibly(go);
            try {
              charter.seal(new MemoryStorage());
              successes.incrementAndGet();
            } catch (IllegalStateException e) {
              failures.incrementAndGet();
            }
          };
      Thread first = new Thread(sealAttempt);
      Thread second = new Thread(sealAttempt);
      first.start();
      second.start();
      ready.await();
      go.countDown();
      first.join();
      second.join();

      assertThat(successes.get()).isEqualTo(1);
      assertThat(failures.get()).isEqualTo(1);
      assertThat(charter.sealed()).isTrue();
    }

    private void awaitUninterruptibly(CountDownLatch latch) {
      boolean interrupted = false;
      try {
        while (true) {
          try {
            latch.await();
            return;
          } catch (InterruptedException e) {
            interrupted = true;
          }
        }
      } finally {
        if (interrupted) {
          Thread.currentThread().interrupt();
        }
      }
    }
  }

  @Nested
  @DisplayName("declaring")
  class Declaring {

    @Test
    @DisplayName("refuses two sources registered as the same name")
    void refuses_two_sources_with_the_same_name() {
      DefaultCharter charter = new DefaultCharter(TENANT);
      charter.source("mail", STRING_TYPE, Label.of(TENANT, "acme"));

      assertThatThrownBy(() -> charter.source("mail", STRING_TYPE, Label.of(TENANT, "acme")))
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("mail");
    }

    @Test
    @DisplayName("refuses a derivation that never said what it may read")
    void refuses_a_derivation_that_never_said_what_it_may_read() {
      DefaultCharter charter = new DefaultCharter(TENANT);

      assertThatThrownBy(
              () ->
                  charter.derivation(
                      "upper", STRING_TYPE, STRING_TYPE, String::toUpperCase, d -> {}))
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("needs a ceiling");
    }

    @Test
    @DisplayName("refuses a question that never said what it may read")
    void refuses_a_question_that_never_said_what_it_may_read() {
      DefaultCharter charter = new DefaultCharter(TENANT);

      assertThatThrownBy(
              () ->
                  charter.query(
                      "mentions", STRING_TYPE, String.class, (v, q, ctx) -> true, d -> {}))
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("needs a ceiling");
    }
  }

  @Nested
  @DisplayName("a portal, printed")
  class APortalPrinted {

    private final DefaultCharter charter = new DefaultCharter(TENANT);

    private final Conceal<String> source =
        charter.source("mail", STRING_TYPE, Label.of(TENANT, "acme"));

    private final Reveal<String> outbox =
        charter
            .destination("outbox", Ceiling.of(TENANT, Constraint.any()), STRING_TYPE)
            .reading(STRING_TYPE);

    {
      charter.seal(new MemoryStorage());
    }

    @Test
    @DisplayName("a source names itself")
    void a_source_names_itself() {
      assertThat(source.toString()).contains("mail");
    }

    @Test
    @DisplayName("a reader says what it reads and names its door")
    void a_reader_says_what_it_reads_and_names_its_door() {
      assertThat(outbox.type()).isEqualTo(STRING_TYPE);
      assertThat(outbox.toString()).contains("outbox").contains("string");
    }
  }

  @Nested
  @DisplayName("what it reports about itself")
  class WhatItReportsAboutItself {

    private final DefaultCharter charter = new DefaultCharter(TENANT);

    private final Conceal<String> source =
        charter.source("mail", STRING_TYPE, Label.of(TENANT, "acme"));

    private final Derivation<String, String> upper =
        charter.derivation(
            "upper",
            STRING_TYPE,
            STRING_TYPE,
            String::toUpperCase,
            d -> d.accepting(Ceiling.of(TENANT, Constraint.any())));

    private final Query<String, String> mentions =
        charter.query(
            "mentions",
            STRING_TYPE,
            String.class,
            (v, q, ctx) -> v.contains(q),
            d -> d.accepting(Ceiling.of(TENANT, Constraint.any())));

    {
      charter.seal(new MemoryStorage());
    }

    @Test
    @DisplayName("the axes it was constituted with")
    void the_axes_it_was_constituted_with() {
      assertThat(charter.axes().named("tenant")).isPresent();
    }

    @Test
    @DisplayName("the types it was told it may keep")
    void the_types_it_was_told_it_may_keep() {
      assertThat(charter.types()).extracting(SurrogateType::name).contains("string");
    }

    @Test
    @DisplayName("the sources it was declared with")
    void the_sources_it_was_declared_with() {
      assertThat(charter.sources()).containsExactly("mail");
    }

    @Test
    @DisplayName("the derivations it was declared with")
    void the_derivations_it_was_declared_with() {
      assertThat(charter.derivations()).extracting(spec -> spec.name()).containsExactly("upper");
    }

    @Test
    @DisplayName("the questions it was declared with")
    void the_questions_it_was_declared_with() {
      assertThat(charter.queries()).extracting(spec -> spec.name()).containsExactly("mentions");
    }

    @Test
    @DisplayName("a question names itself")
    void a_question_names_itself() {
      assertThat(mentions.toString()).contains("mentions");
    }

    @Test
    @DisplayName("where it was told the access happening right now comes from")
    void where_it_was_told_the_access_comes_from() {
      assertThat(charter.currentAccess()).isNotNull();
    }

    @Test
    @DisplayName("who it was told may erase, before it is ever told anything")
    void who_it_was_told_may_erase_before_it_is_ever_told_anything() {
      assertThat(charter.mayErase().test(Label.nothing(), AccessContext.empty())).isFalse();
    }

    /** An application that never calls {@code mayErase(...)} keeps a store that cannot. */
    @Test
    @DisplayName("refuses every erasure until told otherwise")
    void refuses_every_erasure_until_told_otherwise() {
      Surrogate<String> held = source.conceal("hello");

      assertThatThrownBy(() -> charter.erase(held)).isInstanceOf(AccessDeniedException.class);
      assertThat(charter.holds(held)).isTrue();
    }
  }

  @Nested
  @DisplayName("the manifest's findings")
  class ManifestFindings {

    /**
     * Distinct from a ceiling that threw: {@code Destinations.varying} refuses to hand back a null
     * ceiling, so this can only be reached with a {@link DestinationSpec} that answers with nothing
     * directly, the way an application might if it modelled "not applicable" as null rather than as
     * a refusal.
     */
    @Test
    @DisplayName("a ceiling that answers with nothing says so, not that it could not decide")
    void a_ceiling_that_answers_with_nothing_says_so() {
      DefaultCharter charter = new DefaultCharter(TENANT);
      DestinationSpec saysNothing =
          new DestinationSpec() {
            @Override
            public String name() {
              return "outbox";
            }

            @Override
            public Ceiling ceiling(AccessContext context) {
              return null;
            }
          };
      charter.destination(saysNothing);

      assertThat(charter.manifest().toString()).contains("said nothing for this access");
    }

    @Test
    @DisplayName("nothing may be revealed anywhere when no destination is declared")
    void nothing_may_be_revealed_with_no_destination_declared() {
      DefaultCharter charter = new DefaultCharter(TENANT);
      charter.source("mail", STRING_TYPE, Label.of(TENANT, "acme"));

      assertThat(charter.manifest().findings("no-destinations")).isNotEmpty();
    }

    /**
     * The input type has to differ from the output: a derivation always produces its own output
     * type, so reusing it as the input would make the derivation its own writer.
     */
    @Test
    @DisplayName("a derivation that reads a type nothing produces")
    void a_derivation_that_reads_a_type_nothing_produces() {
      DefaultCharter charter = new DefaultCharter(TENANT);
      charter.derivation(
          "length",
          STRING_TYPE,
          INT_TYPE,
          String::length,
          d -> d.accepting(Ceiling.of(TENANT, Constraint.any())));

      assertThat(charter.manifest().findings("no-writer"))
          .anySatisfy(finding -> assertThat(finding.about()).isEqualTo("length"));
    }

    @Test
    @DisplayName("a derivation that makes a type nothing reads")
    void a_derivation_that_makes_a_type_nothing_reads() {
      DefaultCharter charter = new DefaultCharter(TENANT);
      charter.source("mail", STRING_TYPE, Label.of(TENANT, "acme"));
      charter.derivation(
          "upper",
          STRING_TYPE,
          INT_TYPE,
          s -> s.length(),
          d -> d.accepting(Ceiling.of(TENANT, Constraint.any())));

      assertThat(charter.manifest().findings("no-reader"))
          .anySatisfy(finding -> assertThat(finding.about()).isEqualTo("upper"));
    }

    @Test
    @DisplayName("a question that asks about a type nothing produces")
    void a_question_that_asks_about_a_type_nothing_produces() {
      DefaultCharter charter = new DefaultCharter(TENANT);
      charter.query(
          "mentions",
          STRING_TYPE,
          String.class,
          (v, q, ctx) -> true,
          d -> d.accepting(Ceiling.of(TENANT, Constraint.any())));

      assertThat(charter.manifest().findings("no-writer"))
          .anySatisfy(finding -> assertThat(finding.about()).isEqualTo("mentions"));
    }

    /**
     * A derivation cycle that reaches no destination: the reachability walk must not loop forever
     * revisiting the types it already ruled out.
     */
    @Test
    @DisplayName("does not loop forever walking a cycle of derivations that reaches nowhere")
    void does_not_loop_forever_walking_a_cycle() {
      DefaultCharter charter = new DefaultCharter(TENANT);
      SurrogateType<Wrapped> wrappedType = SurrogateType.of(Wrapped.class);
      charter.source("mail", STRING_TYPE, Label.of(TENANT, "acme"));
      charter.derivation(
          "wrap",
          STRING_TYPE,
          wrappedType,
          Wrapped::new,
          d -> d.accepting(Ceiling.of(TENANT, Constraint.any())));
      charter.derivation(
          "unwrap",
          wrappedType,
          STRING_TYPE,
          Wrapped::value,
          d -> d.accepting(Ceiling.of(TENANT, Constraint.any())));

      assertThat(charter.manifest().findings("no-reader"))
          .anySatisfy(finding -> assertThat(finding.about()).isEqualTo("mail"));
    }

    record Wrapped(String value) {}
  }
}
