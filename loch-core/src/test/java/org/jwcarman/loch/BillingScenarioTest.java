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
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.jwcarman.loch.lattice.Exact;
import org.jwcarman.loch.lattice.Lattice;
import org.jwcarman.loch.lattice.Lattices;

/**
 * A multi-tenant SaaS billing system, written the way an application would write it.
 *
 * <p>This is the design's own acceptance test. If something here is awkward to write, the library
 * is wrong and this is where we find out.
 */
@DisplayName("A billing system using Loch")
class BillingScenarioTest {

  // ---------------------------------------------------------------- the application's labels

  /** Least constrained first, always. The last constant is the one that may go fewest places. */
  enum Integrity {
    ENDORSED,
    UNENDORSED
  }

  enum Tlp {
    CLEAR,
    GREEN,
    AMBER,
    RED
  }

  enum DataClass {
    NONE,
    PII,
    CARDHOLDER
  }

  /** Everything this application cares about, in one place. */
  record Billing(Exact<String> tenant, Integrity integrity, Tlp tlp, DataClass dataClass) {

    static final Lattice<Exact<String>> TENANT = Lattices.exact();
    static final Lattice<Integrity> INTEGRITY =
        Lattices.ladder(Integrity.ENDORSED, Integrity.UNENDORSED);
    static final Lattice<Tlp> TLP = Lattices.ladder(Tlp.CLEAR, Tlp.GREEN, Tlp.AMBER, Tlp.RED);
    static final Lattice<DataClass> DATA_CLASS =
        Lattices.ladder(DataClass.NONE, DataClass.PII, DataClass.CARDHOLDER);

    /** The componentwise join. Twelve lines, and the TCK proves it. */
    static final Lattice<Billing> LATTICE =
        new Lattice<>() {
          @Override
          public Billing join(Billing left, Billing right) {
            return new Billing(
                TENANT.join(left.tenant(), right.tenant()),
                INTEGRITY.join(left.integrity(), right.integrity()),
                TLP.join(left.tlp(), right.tlp()),
                DATA_CLASS.join(left.dataClass(), right.dataClass()));
          }

          @Override
          public Billing bottom() {
            return new Billing(
                TENANT.bottom(), INTEGRITY.bottom(), TLP.bottom(), DATA_CLASS.bottom());
          }
        };

    /** What a tenant's ordinary business data looks like. */
    static Billing of(String tenant, Integrity integrity, Tlp tlp, DataClass dataClass) {
      return new Billing(Exact.of(tenant), integrity, tlp, dataClass);
    }

    /**
     * A ceiling for an access made on behalf of one tenant.
     *
     * <p>The tenant comes from the access, never from the destination. There is no such ceiling as
     * "any tenant but not a mixture": that set is not of the form {x : x ⊑ c} for any c, so the
     * lattice cannot express it. Naming the tenant of the access is what makes foreign data and
     * mixed data both fall below the bar. With no tenant named, the ceiling is ⊥ and only
     * unattributed values pass -- which is the fail-closed answer.
     */
    static Billing ceilingFor(
        AccessContext ctx, Integrity integrity, Tlp tlp, DataClass dataClass) {
      Exact<String> tenant = ctx.get("tenant").<Exact<String>>map(Exact::of).orElseGet(Exact::none);
      return new Billing(tenant, integrity, tlp, dataClass);
    }
  }

  // ---------------------------------------------------------------- where values may go

  static final DestinationId VENDOR_LLM = DestinationId.of("vendor-llm");
  static final DestinationId QUARANTINED_LLM = DestinationId.of("quarantined-llm");
  static final DestinationId PAYMENT_PROCESSOR = DestinationId.of("payment-processor");
  static final DestinationId APPROVAL_CARD = DestinationId.of("approval-card");

  /** Every destination reads the tenant from the access, so none of them is a fixed ceiling. */
  private static Destination<Billing> tenantScoped(
      DestinationId id, Integrity integrity, Tlp tlp, DataClass dataClass) {
    return Destinations.varying(id, ctx -> Billing.ceilingFor(ctx, integrity, tlp, dataClass));
  }

  private final Loch<Billing> loch =
      MemoryLoch.create(
          c ->
              c.lattice(Billing.LATTICE)
                  // A vendor's model: nothing personal, nothing unendorsed.
                  .destination(
                      tenantScoped(VENDOR_LLM, Integrity.ENDORSED, Tlp.CLEAR, DataClass.NONE))
                  // Ours, on our own hardware. Reads untrusted mail; holds no secrets.
                  .destination(
                      tenantScoped(QUARANTINED_LLM, Integrity.UNENDORSED, Tlp.AMBER, DataClass.PII))
                  // The only place cardholder data may go, anywhere in the system.
                  .destination(
                      tenantScoped(
                          PAYMENT_PROCESSOR, Integrity.ENDORSED, Tlp.RED, DataClass.CARDHOLDER))
                  // A person. What they may see depends on who they are.
                  .destination(
                      Destinations.varying(
                          APPROVAL_CARD,
                          ctx ->
                              Billing.ceilingFor(
                                  ctx,
                                  Integrity.ENDORSED,
                                  Tlp.AMBER,
                                  ctx.has("clearance", "finance")
                                      ? DataClass.PII
                                      : DataClass.NONE))));

  /** Every access in this system is made on behalf of a tenant. */
  private static AccessContext acme() {
    return AccessContext.of("tenant", "acme");
  }

  private static AccessContext acme(String key, String value) {
    return AccessContext.of(java.util.Map.of("tenant", "acme", key, value));
  }

  // ---------------------------------------------------------------- the scenario

  private Held<String> customerEmail() {
    return loch.hold(
        "I was charged twice for invoice INV-4471. My SSN is 123-45-6789 if that helps.",
        Billing.of("acme", Integrity.UNENDORSED, Tlp.AMBER, DataClass.PII));
  }

  @Nested
  @DisplayName("the customer's email")
  class TheEmail {

    @Test
    @DisplayName("never reaches a vendor's model")
    void never_reaches_a_vendors_model() {
      Held<String> email = customerEmail();

      Dereferenced<String> attempt = loch.dereference(email, VENDOR_LLM, acme());

      assertThat(attempt.allowed()).isFalse();
      assertThat(attempt)
          .isInstanceOfSatisfying(
              Dereferenced.Denied.class,
              denied -> assertThat(denied.reason()).isEqualTo(Dereferenced.Reason.ABOVE_CEILING));
    }

    @Test
    @DisplayName("does reach the quarantined model, which is what it is for")
    void does_reach_the_quarantined_model() {
      Held<String> email = customerEmail();

      assertThat(loch.dereference(email, QUARANTINED_LLM, acme()).granted())
          .hasValueSatisfying(text -> assertThat(text).contains("INV-4471"));
    }

    @Test
    @DisplayName("is still a handle everywhere else, and says nothing when printed")
    void is_still_a_handle_everywhere_else() {
      Held<String> email = customerEmail();

      assertThat(email.toString()).doesNotContain("123-45-6789").contains("loch_");
    }
  }

  @Nested
  @DisplayName("the card token")
  class TheCardToken {

    private Held<String> token() {
      return loch.hold(
          "tok_1P9xyz", Billing.of("acme", Integrity.ENDORSED, Tlp.RED, DataClass.CARDHOLDER));
    }

    @Test
    @DisplayName("reaches the payment processor")
    void reaches_the_payment_processor() {
      assertThat(loch.dereference(token(), PAYMENT_PROCESSOR, acme()).granted())
          .contains("tok_1P9xyz");
    }

    /** Not by policy anyone wrote. By arithmetic: every model sits below CARDHOLDER. */
    @Test
    @DisplayName("cannot reach any model, and cannot reach a person")
    void cannot_reach_any_model_or_person() {
      Held<String> token = token();

      assertThat(loch.dereference(token, VENDOR_LLM, acme()).allowed()).isFalse();
      assertThat(loch.dereference(token, QUARANTINED_LLM, acme()).allowed()).isFalse();
      assertThat(loch.dereference(token, APPROVAL_CARD, acme("clearance", "finance")).allowed())
          .isFalse();
    }
  }

  @Nested
  @DisplayName("the approval card")
  class TheApprovalCard {

    private Held<String> last4() {
      return loch.hold("4821", Billing.of("acme", Integrity.ENDORSED, Tlp.AMBER, DataClass.PII));
    }

    @Test
    @DisplayName("shows a finance approver the last four")
    void shows_a_finance_approver_the_last_four() {
      assertThat(loch.dereference(last4(), APPROVAL_CARD, acme("clearance", "finance")).granted())
          .contains("4821");
    }

    @Test
    @DisplayName("shows anyone else a handle")
    void shows_anyone_else_a_handle() {
      assertThat(loch.dereference(last4(), APPROVAL_CARD, acme("clearance", "support")).allowed())
          .isFalse();
    }

    @Test
    @DisplayName("and with nobody named at all, shows nothing")
    void with_nobody_named_shows_nothing() {
      assertThat(loch.dereference(last4(), APPROVAL_CARD, acme()).allowed()).isFalse();
    }
  }

  @Nested
  @DisplayName("tenants")
  class Tenants {

    /** The claim the whole design rests on: a mixed value is unusable, not merely discouraged. */
    @Test
    @DisplayName("a value carrying two tenants can go nowhere at all")
    void a_value_carrying_two_tenants_can_go_nowhere() {
      Billing acme = Billing.of("acme", Integrity.ENDORSED, Tlp.CLEAR, DataClass.NONE);
      Billing globex = Billing.of("globex", Integrity.ENDORSED, Tlp.CLEAR, DataClass.NONE);

      Billing mixed = Billing.LATTICE.join(acme, globex);

      assertThat(mixed.tenant().conflicted()).isTrue();
      Held<String> held = loch.hold("a report covering both customers", mixed);
      assertThat(loch.dereference(held, VENDOR_LLM, acme()).allowed()).isFalse();
      assertThat(loch.dereference(held, PAYMENT_PROCESSOR, acme()).allowed()).isFalse();
      assertThat(loch.dereference(held, QUARANTINED_LLM, acme()).allowed()).isFalse();
    }

    @Test
    @DisplayName("another tenant's data is refused, even though it is perfectly ordinary")
    void another_tenants_data_is_refused() {
      Held<String> globex =
          loch.hold(
              "globex's entirely unremarkable note",
              Billing.of("globex", Integrity.ENDORSED, Tlp.CLEAR, DataClass.NONE));

      assertThat(loch.dereference(globex, VENDOR_LLM, acme()).allowed()).isFalse();
    }

    @Test
    @DisplayName("one tenant's ordinary data is fine")
    void one_tenants_ordinary_data_is_fine() {
      Held<String> held =
          loch.hold(
              "nothing secret", Billing.of("acme", Integrity.ENDORSED, Tlp.CLEAR, DataClass.NONE));

      assertThat(loch.dereference(held, VENDOR_LLM, acme()).allowed()).isTrue();
    }
  }

  @Nested
  @DisplayName("the gate itself")
  class TheGate {

    @Test
    @DisplayName("refuses an id nobody minted, rather than computing anything")
    void refuses_an_id_nobody_minted() {
      Held<String> invented = new Held<>(new HeldId("loch_whatever-i-like"), String.class);

      assertThat(loch.dereference(invented, QUARANTINED_LLM, acme()))
          .isInstanceOfSatisfying(
              Dereferenced.Denied.class,
              denied -> assertThat(denied.reason()).isEqualTo(Dereferenced.Reason.NO_SUCH_VALUE));
    }

    @Test
    @DisplayName("refuses a destination nobody registered")
    void refuses_a_destination_nobody_registered() {
      assertThat(loch.dereference(customerEmail(), DestinationId.of("my-own-endpoint"), acme()))
          .isInstanceOfSatisfying(
              Dereferenced.Denied.class,
              denied ->
                  assertThat(denied.reason()).isEqualTo(Dereferenced.Reason.NO_SUCH_DESTINATION));
    }

    @Test
    @DisplayName("refuses a handle whose claimed type is not what was stored")
    void refuses_a_handle_whose_type_is_wrong() {
      Held<String> email = customerEmail();
      Held<Integer> lying = new Held<>(email.id(), Integer.class);

      assertThat(loch.dereference(lying, QUARANTINED_LLM, acme()))
          .isInstanceOfSatisfying(
              Dereferenced.Denied.class,
              denied -> assertThat(denied.reason()).isEqualTo(Dereferenced.Reason.WRONG_TYPE));
    }

    @Test
    @DisplayName("tells you the label and the ceiling when it refuses, without leaking the value")
    void explains_a_refusal_without_leaking() {
      Dereferenced<String> denied = loch.dereference(customerEmail(), VENDOR_LLM, acme());

      String detail = ((Dereferenced.Denied<String>) denied).detail();
      assertThat(detail).contains("vendor-llm").doesNotContain("123-45-6789");
    }
  }
}
