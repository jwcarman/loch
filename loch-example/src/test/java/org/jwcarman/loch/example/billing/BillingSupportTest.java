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
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/** The support desk, driven the way a client drives it. */
@Testcontainers
@AutoConfigureTestRestTemplate
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@DisplayName("The billing support desk")
class BillingSupportTest {

  @Container
  static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17-alpine");

  @DynamicPropertySource
  static void datasource(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
    registry.add("spring.datasource.username", POSTGRES::getUsername);
    registry.add("spring.datasource.password", POSTGRES::getPassword);
  }

  @Autowired private TestRestTemplate http;
  @LocalServerPort private int port;

  private <T> ResponseEntity<T> as(
      String tenant, String role, HttpMethod method, String path, Object body, Class<T> type) {
    HttpHeaders headers = new HttpHeaders();
    headers.set("X-Tenant", tenant);
    headers.set("X-Role", role);
    headers.set("X-User", role + "@support.example");
    return http.exchange(
        "http://localhost:" + port + path, method, new HttpEntity<>(body, headers), type);
  }

  private String raiseAcmeDispute() {
    var raised =
        as(
            "acme",
            "agent",
            HttpMethod.POST,
            "/disputes",
            new DisputeController.Raise("dana@acme.example", "I was charged twice for INV-4471"),
            DisputeController.Reference.class);
    assertThat(raised.getStatusCode()).isEqualTo(HttpStatus.OK);
    return raised.getBody().id();
  }

  @Test
  @DisplayName("takes a customer's message and gives back a reference, not the message")
  void takes_a_message_and_gives_back_a_reference() {
    String reference = raiseAcmeDispute();

    assertThat(reference).startsWith("loch_").doesNotContain("dana@acme.example");
  }

  @Test
  @DisplayName("answers a question about the message without the message leaving")
  void answers_a_question_without_the_message_leaving() {
    String mail = raiseAcmeDispute();

    var yes =
        as(
            "acme",
            "agent",
            HttpMethod.GET,
            "/disputes/" + mail + "/mentions?text=INV-4471",
            null,
            Boolean.class);
    var no =
        as(
            "acme",
            "agent",
            HttpMethod.GET,
            "/disputes/" + mail + "/mentions?text=refund",
            null,
            Boolean.class);

    assertThat(yes.getBody()).isTrue();
    assertThat(no.getBody()).isFalse();
  }

  @Test
  @DisplayName("confirms the invoice against the mailbox it was raised from")
  void confirms_the_invoice_against_the_mailbox() {
    String mail = raiseAcmeDispute();

    var confirmed =
        as(
            "acme",
            "agent",
            HttpMethod.POST,
            "/disputes/" + mail + "/confirm",
            null,
            DisputeController.Reference.class);

    assertThat(confirmed.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(confirmed.getBody().id()).startsWith("loch_");
  }

  /** An invoice that exists, and belongs to somebody else. */
  @Test
  @DisplayName("refuses to confirm another customer's invoice")
  void refuses_to_confirm_another_customers_invoice() {
    var raised =
        as(
            "acme",
            "agent",
            HttpMethod.POST,
            "/disputes",
            new DisputeController.Raise("dana@acme.example", "please look at INV-9999"),
            DisputeController.Reference.class);

    var confirmed =
        as(
            "acme",
            "agent",
            HttpMethod.POST,
            "/disputes/" + raised.getBody().id() + "/confirm",
            null,
            String.class);

    // A refusal, not a crash. This asserted INTERNAL_SERVER_ERROR while there were two exception
    // types and the controller caught one of them.
    assertThat(confirmed.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
  }

  @Test
  @DisplayName("shows an approver the last four digits, and shows an agent nothing")
  void shows_an_approver_the_last_four() {
    String invoice = confirmedInvoice();

    var approver =
        as(
            "acme",
            "approver",
            HttpMethod.GET,
            "/disputes/" + invoice + "/card",
            null,
            Domain.Last4.class);
    var agent =
        as("acme", "agent", HttpMethod.GET, "/disputes/" + invoice + "/card", null, String.class);

    assertThat(approver.getBody().digits()).isEqualTo("4821");
    assertThat(agent.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
  }

  /** The card token has exactly one place it may go, and it is not a support screen. */
  @Test
  @DisplayName("the support screen cannot see an invoice holding a live card token")
  void the_support_screen_cannot_see_a_card_token() {
    String invoice = confirmedInvoice();

    var screen = as("acme", "agent", HttpMethod.GET, "/disputes/" + invoice, null, String.class);

    assertThat(screen.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    assertThat(screen.getBody()).doesNotContain("tok_live_9911554821");
  }

  @Test
  @DisplayName("but the payment processor may, which is how a refund happens")
  void the_payment_processor_may() {
    String invoice = confirmedInvoice();

    var refund =
        as(
            "acme",
            "approver",
            HttpMethod.POST,
            "/disputes/" + invoice + "/refund",
            null,
            String.class);

    assertThat(refund.getBody()).contains("refunded 412.00").contains("ending 4821");
  }

  /** The same reference, asked for by the wrong customer. */
  @Test
  @DisplayName("another tenant holding the reference gets nothing from it")
  void another_tenant_gets_nothing() {
    String invoice = confirmedInvoice();

    var theirs =
        as(
            "globex",
            "approver",
            HttpMethod.POST,
            "/disputes/" + invoice + "/refund",
            null,
            String.class);

    assertThat(theirs.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
  }

  @Test
  @DisplayName("and an unauthenticated caller gets nothing either")
  void an_unauthenticated_caller_gets_nothing() {
    String invoice = confirmedInvoice();

    var anonymous =
        http.postForEntity(
            "http://localhost:" + port + "/disputes/" + invoice + "/refund", null, String.class);

    assertThat(anonymous.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
  }

  private String confirmedInvoice() {
    String mail = raiseAcmeDispute();
    return as(
            "acme",
            "agent",
            HttpMethod.POST,
            "/disputes/" + mail + "/confirm",
            null,
            DisputeController.Reference.class)
        .getBody()
        .id();
  }
}
