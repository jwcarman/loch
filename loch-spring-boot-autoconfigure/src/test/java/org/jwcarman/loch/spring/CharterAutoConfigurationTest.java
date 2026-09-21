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
package org.jwcarman.loch.spring;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.jwcarman.loch.Charter;
import org.jwcarman.loch.Conceal;
import org.jwcarman.loch.DefaultCharter;
import org.jwcarman.loch.MemoryStorage;
import org.jwcarman.loch.Storage;
import org.jwcarman.loch.Surrogate;
import org.jwcarman.loch.SurrogateType;
import org.jwcarman.loch.lattice.Axes;
import org.jwcarman.loch.lattice.Axis;
import org.jwcarman.loch.lattice.Label;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Wiring, which is the part that fails quietly.
 *
 * <p>Every other test in this repository drives the library directly. None of them can see a bean
 * that silently never matched, and twice that was the actual defect: a condition on a type Spring
 * did not know the charter by, and a sealer that depended on storage contributed by a later
 * auto-configuration. Both compiled, started, and refused every portal at request time.
 *
 * <p>These run the container without one, which is what makes them worth having.
 */
@DisplayName("The charter auto-configuration")
class CharterAutoConfigurationTest {

  private enum Clearance {
    OPEN,
    CLOSED
  }

  private static final Axis<String> TENANT = Axis.matching("tenant");
  private static final Axis<Clearance> CLEARANCE =
      Axis.ladder("clearance", Clearance.OPEN, Clearance.CLOSED);
  private static final SurrogateType<String> NOTE = SurrogateType.of("note", String.class);

  private final ApplicationContextRunner runner =
      new ApplicationContextRunner()
          .withConfiguration(AutoConfigurations.of(CharterAutoConfiguration.class));

  /** What an application contributes: its vocabulary, and somewhere to keep things. */
  @Configuration(proxyBeanMethods = false)
  static class AnApplication {

    @Bean
    Axes axes() {
      return Axes.of(TENANT, CLEARANCE);
    }

    @Bean
    Storage storage() {
      return new MemoryStorage();
    }

    @Bean
    Conceal<String> notes(Charter charter) {
      return charter.source(
          "notes", NOTE, ctx -> Label.of(TENANT, "acme").with(CLEARANCE, Clearance.OPEN));
    }
  }

  @Test
  @DisplayName("constitutes a charter from the axes an application declared")
  void constitutes_a_charter_from_the_axes() {
    runner
        .withUserConfiguration(AnApplication.class)
        .run(
            context -> {
              assertThat(context).hasSingleBean(Charter.class);
              assertThat(context.getBean(Charter.class).axes())
                  .isEqualTo(Axes.of(TENANT, CLEARANCE));
            });
  }

  /**
   * The failure that started this file.
   *
   * <p>Sealing happens in a {@code SmartInitializingSingleton}, so a portal that works is the only
   * evidence it ran at all. When the sealer silently never matched, every portal refused at request
   * time -- and nothing in the wiring complained.
   */
  @Test
  @DisplayName("seals it, so a portal declared against it actually works")
  void seals_it_so_portals_work() {
    runner
        .withUserConfiguration(AnApplication.class)
        .run(
            context -> {
              assertThat(context).hasNotFailed();
              assertThat(context.getBean(Charter.class).sealed()).isTrue();

              Surrogate<String> held = context.getBean(Conceal.class).conceal("a note");

              assertThat(context.getBean(Charter.class).holds(held)).isTrue();
            });
  }

  /** An application with no vocabulary gets no charter, rather than a guessed one. */
  @Test
  @DisplayName("declares nothing when the application never said what it asks about values")
  void declares_nothing_without_axes() {
    runner.run(context -> assertThat(context).doesNotHaveBean(Charter.class));
  }

  /**
   * Storage arrives from whichever module is on the classpath, and that runs afterwards.
   *
   * <p>A direct dependency here was evaluated before the bean existed, so the sealer never matched.
   * This is the regression test for that, and it passes only because resolution is deferred to the
   * moment of sealing.
   */
  @Test
  @DisplayName("seals against storage contributed by a later auto-configuration")
  void seals_against_storage_contributed_later() {
    new ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(CharterAutoConfiguration.class, LateStorage.class))
        .withUserConfiguration(NoStorage.class)
        .run(
            context -> {
              assertThat(context).hasNotFailed();
              assertThat(context).hasSingleBean(Storage.class);
              assertThat(context.getBean(Charter.class).sealed()).isTrue();
            });
  }

  /**
   * Storage the way a storage module really supplies it: from an auto-configuration ordered after
   * the one that constitutes the charter, which is precisely when a direct dependency is evaluated
   * too early and the sealer silently never matches.
   */
  @AutoConfiguration(after = CharterAutoConfiguration.class)
  static class LateStorage {

    @Bean
    Storage storage() {
      return new MemoryStorage();
    }
  }

  /** A charter with nowhere to keep anything is a bug worth a sentence, not a quiet no-op. */
  @Test
  @DisplayName("says so when an application declares a charter and supplies no storage")
  void says_so_when_there_is_no_storage() {
    new ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(CharterAutoConfiguration.class))
        .withUserConfiguration(NoStorage.class)
        .run(
            context ->
                assertThat(context)
                    .hasFailed()
                    .getFailure()
                    .hasMessageContaining("nothing supplies storage"));
  }

  @Configuration(proxyBeanMethods = false)
  static class NoStorage {

    @Bean
    Axes axes() {
      return Axes.of(TENANT);
    }
  }

  /**
   * The published bean cannot bring a charter into force, and cannot destroy through one.
   *
   * <p>Not a check that refuses: {@link Charter} has no {@code seal} and no {@code erase} to call.
   * What the container hands out is narrower than what the starter kept.
   */
  @Test
  @DisplayName("publishes a charter that can neither seal nor erase")
  void publishes_a_charter_that_cannot_seal() {
    assertThat(Charter.class.getMethods())
        .isNotEmpty()
        .noneSatisfy(method -> assertThat(method.getName()).isEqualTo("seal"))
        .noneSatisfy(method -> assertThat(method.getName()).isEqualTo("erase"));
  }

  /** And an application that brought its own charter keeps it. */
  @Test
  @DisplayName("leaves a charter the application declared itself alone")
  void leaves_an_application_charter_alone() {
    runner
        .withUserConfiguration(OwnCharter.class)
        .run(
            context -> {
              assertThat(context).hasSingleBean(Charter.class);
              assertThat(context.getBean(Charter.class).axes()).isEqualTo(Axes.of(TENANT));
            });
  }

  @Configuration(proxyBeanMethods = false)
  static class OwnCharter {

    @Bean
    Axes axes() {
      return Axes.of(TENANT, CLEARANCE);
    }

    @Bean
    Charter charter() {
      return new DefaultCharter(TENANT);
    }

    @Bean
    Storage storage() {
      return new MemoryStorage();
    }
  }

  /** Declaring after the context has finished is the forged-portal case, through the container. */
  @Test
  @DisplayName("refuses anything declared after the context has been built")
  void refuses_anything_declared_afterwards() {
    runner
        .withUserConfiguration(AnApplication.class)
        .run(
            context -> {
              Charter charter = context.getBean(Charter.class);

              assertThatThrownBy(
                      () ->
                          charter.source(
                              "forged",
                              NOTE,
                              ctx -> Label.of(TENANT, "globex").with(CLEARANCE, Clearance.OPEN)))
                  .isInstanceOf(IllegalStateException.class)
                  .hasMessageContaining("has been sealed");
            });
  }
}
