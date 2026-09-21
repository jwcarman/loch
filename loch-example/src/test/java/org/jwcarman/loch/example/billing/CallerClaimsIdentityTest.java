/*
 * Copyright © ${year} James Carman
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
package org.jwcarman.loch.example.billing;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.jwcarman.loch.Loch;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

@Testcontainers
@SpringBootTest
@DisplayName("Claiming to be somebody")
class CallerClaimsIdentityTest {

  @Container static final PostgreSQLContainer PG = new PostgreSQLContainer("postgres:17-alpine");

  @DynamicPropertySource
  static void ds(DynamicPropertyRegistry r) {
    r.add("spring.datasource.url", PG::getJdbcUrl);
    r.add("spring.datasource.username", PG::getUsername);
    r.add("spring.datasource.password", PG::getPassword);
  }

  @Autowired Loch<BillingLabels> loch;

  /**
   * The gate is only worth anything if identity comes from somewhere a caller does not control.
   *
   * <p>This service establishes it from the request. Code holding the loch directly, with no
   * request in scope, has no identity -- and saying it is acme does not make it so.
   *
   * <p>It cannot even create the value any more, which is a stronger statement than the one this
   * test was originally written to make. Writing at a label is its own question, and with nobody
   * acting there is no label this code may write at.
   */
  @Test
  @DisplayName("cannot even create the value, let alone read one")
  void cannot_even_create_the_value() {
    BillingLabels asAcme =
        BillingLabels.of(
            "acme", BillingLabels.Integrity.ENDORSED, BillingLabels.Sensitivity.CARDHOLDER);

    assertThat(
            org.assertj.core.api.Assertions.catchThrowable(
                () -> loch.hold("tok_live_secret", String.class, asAcme)))
        .isInstanceOf(org.jwcarman.loch.AccessDeniedException.class);
  }
}
