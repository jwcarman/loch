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

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.jwcarman.loch.Charter;
import org.jwcarman.loch.Storage;
import org.jwcarman.loch.jdbc.JdbcStorage;
import org.jwcarman.loch.jdbc.StorageCodec;
import org.jwcarman.loch.lattice.Axes;
import org.jwcarman.loch.lattice.Axis;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseBuilder;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseType;

/**
 * What the JDBC module contributes, and what it deliberately does not.
 *
 * <p>Its whole job is somewhere to keep values and lines. It does not constitute a charter and it
 * does not bring one into force, because whichever module happens to be on the classpath should not
 * be the thing that decides when an application's authority stops growing.
 */
@DisplayName("The JDBC charter auto-configuration")
class JdbcCharterAutoConfigurationTest {

  private static final Axis<String> TENANT = Axis.matching("tenant");

  private final ApplicationContextRunner runner =
      new ApplicationContextRunner()
          .withConfiguration(
              AutoConfigurations.of(
                  CharterAutoConfiguration.class, JdbcCharterAutoConfiguration.class))
          // The schema is Postgres -- TIMESTAMPTZ is not H2 -- and it is exercised against a real
          // Postgres in loch-jdbc. What is under test here is which beans appear and in what order.
          .withPropertyValues("loch.migrate=false");

  @Configuration(proxyBeanMethods = false)
  static class AnApplication {

    @Bean
    Axes axes() {
      return Axes.of(TENANT);
    }

    @Bean
    javax.sql.DataSource dataSource() {
      return new EmbeddedDatabaseBuilder().setType(EmbeddedDatabaseType.H2).build();
    }

    @Bean
    StorageCodec storageCodec() {
      // Nothing to hide in a test; the point here is the wiring, not the bytes.
      return StorageCodec.of(
          new org.jwcarman.codec.spi.Codec<byte[]>() {
            @Override
            public byte[] encode(byte[] value) {
              return value;
            }

            @Override
            public byte[] decode(byte[] encoded) {
              return encoded;
            }
          });
    }
  }

  @Test
  @DisplayName("contributes storage, and the charter is sealed to it")
  void contributes_storage_and_the_charter_is_sealed() {
    runner
        .withUserConfiguration(AnApplication.class)
        .run(
            context -> {
              assertThat(context).hasNotFailed();
              assertThat(context).hasSingleBean(JdbcStorage.class);
              assertThat(context.getBean(Charter.class).sealed()).isTrue();
            });
  }

  /**
   * Bytes reaching disk untouched is a decision somebody makes, never one they inherit.
   *
   * <p>So without a {@link StorageCodec} this module contributes nothing -- and an application that
   * declared a charter with nowhere to keep anything fails at startup rather than serving requests
   * that all refuse.
   */
  @Test
  @DisplayName("contributes nothing when nobody said what happens to the bytes")
  void contributes_nothing_without_a_storage_codec() {
    runner
        .withUserConfiguration(NoCodec.class)
        .run(
            context ->
                assertThat(context)
                    .hasFailed()
                    .getFailure()
                    .hasMessageContaining("nothing supplies storage"));
  }

  @Configuration(proxyBeanMethods = false)
  static class NoCodec {

    @Bean
    Axes axes() {
      return Axes.of(TENANT);
    }

    @Bean
    javax.sql.DataSource dataSource() {
      return new EmbeddedDatabaseBuilder().setType(EmbeddedDatabaseType.H2).build();
    }
  }

  /** An application that brought its own storage keeps it, and this module stays out of the way. */
  @Test
  @DisplayName("leaves storage the application supplied alone")
  void leaves_application_storage_alone() {
    runner
        .withUserConfiguration(AnApplication.class)
        .withBean(Storage.class, org.jwcarman.loch.MemoryStorage::new)
        .run(
            context -> {
              assertThat(context).hasSingleBean(Storage.class);
              assertThat(context).doesNotHaveBean(JdbcStorage.class);
            });
  }
}
