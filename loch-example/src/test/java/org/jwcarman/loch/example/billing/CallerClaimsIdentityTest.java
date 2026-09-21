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
import org.jwcarman.loch.Charter;
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

  @Autowired org.springframework.context.ApplicationContext context;

  /**
   * The gate is only worth anything if identity comes from somewhere a caller does not control.
   *
   * <p>This test used to fabricate a value at acme's label and assert the store refused it. It
   * cannot be written any more: nothing on a charter takes a label, so there is no way to say what
   * a value should be labelled except by holding the source that decides. What is left worth
   * asserting is that the door really is gone, because it is the sort of thing that gets added back
   * for a test fixture and never removed.
   */
  @Test
  @DisplayName("cannot create a value through the charter, because nothing there creates values")
  void cannot_create_a_value_through_the_charter() {
    assertThat(Charter.class.getMethods())
        .isNotEmpty()
        .noneSatisfy(
            method ->
                assertThat(method.getReturnType()).isEqualTo(org.jwcarman.loch.Surrogate.class));
  }

  /**
   * Spring's container is a lookup-by-type service, so publishing a portal as a bean would hand one
   * to any class willing to name the type in its constructor. That is obtaining authority by naming
   * it, which is the thing this design removed. Portals are private fields of the services entitled
   * to them, and nothing can ask the context for one.
   */
  /**
   * The configuration, unlike everything else, <b>is</b> published -- and that is the one
   * deliberate concession in the arrangement.
   *
   * <p>Declaring a portal means holding the configuration, so it has to be reachable by whatever
   * declares one. It is root authority: anything holding it can declare a portal at any label and
   * any ceiling. What that buys is that the application never orchestrates the lifecycle, and what
   * it costs is that "who can grant authority" is a grep for this type rather than one file.
   *
   * <p>The narrowing that survives is the one that matters: holding the mint lets you declare a
   * door, and holding a door lets you use it. Nothing lets you do both by accident.
   */
  @Test
  @DisplayName("can obtain the configuration, because declaring a portal is what it is for")
  void can_obtain_the_configuration() {
    assertThat(context.getBeanNamesForType(org.jwcarman.loch.Charter.class))
        .containsExactly("billingCharter");
  }

  @Test
  @DisplayName("cannot obtain a portal from the application context")
  void cannot_obtain_a_portal_from_the_context() {
    assertThat(context.getBeanNamesForType(org.jwcarman.loch.Conceal.class)).isEmpty();
    assertThat(context.getBeanNamesForType(org.jwcarman.loch.Reveal.class)).isEmpty();
    assertThat(context.getBeanNamesForType(org.jwcarman.loch.Derivation.class)).isEmpty();
    assertThat(context.getBeanNamesForType(org.jwcarman.loch.Query.class)).isEmpty();
  }
}
