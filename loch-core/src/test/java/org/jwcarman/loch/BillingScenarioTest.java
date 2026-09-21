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

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.jwcarman.codec.spi.TypeRef;
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

    /** Moves one part of the label and leaves the rest still: what a relabel should look like. */
    Billing withDataClass(DataClass dataClass) {
      return new Billing(tenant(), integrity(), tlp(), dataClass);
    }

    Billing withTlp(Tlp tlp) {
      return new Billing(tenant(), integrity(), tlp, dataClass());
    }

    Billing withIntegrity(Integrity integrity) {
      return new Billing(tenant(), integrity, tlp(), dataClass());
    }

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

  private final Auditors.Recording audit = Auditors.recording();

  /**
   * Standing in for the edge: a request, a message header, a session.
   *
   * <p>The tests set this rather than passing identity into calls, because a caller cannot be
   * allowed to say who it is. That is not a testing detail; it is the property that makes any of
   * the rest of this mean anything.
   */
  private final java.util.concurrent.atomic.AtomicReference<AccessContext> edge =
      new java.util.concurrent.atomic.AtomicReference<>(AccessContext.empty());

  private final Loch<Billing> loch =
      MemoryLoch.create(
          c ->
              c.lattice(Billing.LATTICE)
                  .auditor(audit)
                  .askingWhoIsAsking(edge::get)
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
                                      : DataClass.NONE)))
                  // A projection. Cannot weaken anything, so it needs no ceremony.
                  .derivation(
                      Derivations.<Billing, DisputeClaim, InvoiceNumber>of(
                              CLAIMED_INVOICE,
                              DisputeClaim.class,
                              InvoiceNumber.class,
                              claim -> new InvoiceNumber(claim.invoiceNumber()))
                          .build())
                  // Truncating a card IS a declassification, and PCI auditors ask about it.
                  .derivation(
                      Derivations.<Billing, String, Last4>of(
                              CARD_LAST4,
                              String.class,
                              Last4.class,
                              token -> new Last4(token.substring(token.length() - 4)))
                          .accepting(
                              ctx ->
                                  Billing.ceilingFor(
                                      ctx, Integrity.ENDORSED, Tlp.RED, DataClass.CARDHOLDER))
                          // Both dimensions, deliberately: four digits are neither cardholder
                          // data nor RED any more, and saying so is the reviewed act.
                          .lowering(
                              joined -> joined.withDataClass(DataClass.PII).withTlp(Tlp.AMBER))
                          .availableTo(ctx -> ctx.has("tool", "prepare_approval"))
                          .build())
                  // The same truncation, lowering only one dimension. Still cannot be shown.
                  .derivation(
                      Derivations.<Billing, String, Last4>of(
                              CARD_LAST4_PARTIAL,
                              String.class,
                              Last4.class,
                              token -> new Last4(token.substring(token.length() - 4)))
                          .lowering(joined -> joined.withDataClass(DataClass.PII))
                          .build())
                  // Declares itself an endorsement without checking anything. Loch refuses it.
                  .derivation(
                      Derivations.<Billing, DisputeClaim, InvoiceNumber>of(
                              WISHFUL,
                              DisputeClaim.class,
                              InvoiceNumber.class,
                              claim -> new InvoiceNumber(claim.invoiceNumber()))
                          .lowering(joined -> joined.withIntegrity(Integrity.UNENDORSED))
                          .build())
                  // Several values in, one out. Every parent's label lands on the result.
                  .derivation(
                      Derivations.<Billing, String, Report>fromAll(
                              SUMMARISE,
                              String.class,
                              Report.class,
                              notes -> new Report(String.join(" / ", notes)))
                          .build())
                  // Reads the value, then says no. The refusal has to be recorded because the
                  // function already saw the plaintext.
                  .derivation(
                      Derivations.<Billing, DisputeClaim, InvoiceNumber>checking(
                              DECLINES,
                              DisputeClaim.class,
                              InvoiceNumber.class,
                              (claim, ctx) -> java.util.Optional.empty())
                          .build())
                  // A fold that lowers is as privileged as a derivation that lowers.
                  .derivation(
                      Derivations.<Billing, String, Report>fromAll(
                              SUMMARISE_FOR_RELEASE,
                              String.class,
                              Report.class,
                              notes -> new Report("redacted summary of " + notes.size()))
                          .lowering(joined -> joined.withDataClass(DataClass.NONE))
                          .build())
                  // The whole account never leaves the loch to answer one question about it.
                  .question(
                      Question.<Billing, Account, String>of(
                              OWNED_BY,
                              Account.class,
                              (account, sender) -> account.email().equalsIgnoreCase(sender))
                          .build()));

  record DisputeClaim(String invoiceNumber, String reason) {}

  record InvoiceNumber(String value) {}

  record Last4(String digits) {}

  static final DerivationId<DisputeClaim, InvoiceNumber> CLAIMED_INVOICE =
      DerivationId.of("DisputeClaim.invoiceNumber");
  static final DerivationId<String, Last4> CARD_LAST4 = DerivationId.of("Card.last4");
  static final DerivationId<String, Last4> CARD_LAST4_PARTIAL =
      DerivationId.of("Card.last4.dataClassOnly");

  record Account(String number, String email) {}

  record Report(String text) {}

  static final DerivationId<String, Report> SUMMARISE = DerivationId.of("notes.summarise");
  static final DerivationId<String, Report> SUMMARISE_FOR_RELEASE =
      DerivationId.of("notes.summarise.forRelease");

  static final QuestionId<Account, String> OWNED_BY = QuestionId.of("Account.ownedBy");

  static final DerivationId<DisputeClaim, InvoiceNumber> DECLINES =
      DerivationId.of("DisputeClaim.alwaysDeclines");

  static final DerivationId<DisputeClaim, InvoiceNumber> WISHFUL =
      DerivationId.of("DisputeClaim.invoiceNumber.trustMe");

  /** Every access in this system is made on behalf of a tenant, established at the edge. */
  private AccessContext acme() {
    edge.set(AccessContext.of("tenant", "acme"));
    return AccessContext.empty();
  }

  private AccessContext acme(String key, String value) {
    edge.set(AccessContext.of(java.util.Map.of("tenant", "acme", key, value)));
    return AccessContext.empty();
  }

  // ---------------------------------------------------------------- the scenario

  private Handle<String> customerEmail() {
    return loch.hold(
        "I was charged twice for invoice INV-4471. My SSN is 123-45-6789 if that helps.",
        String.class,
        Billing.of("acme", Integrity.UNENDORSED, Tlp.AMBER, DataClass.PII));
  }

  @Nested
  @DisplayName("the customer's email")
  class TheEmail {

    @Test
    @DisplayName("never reaches a vendor's model")
    void never_reaches_a_vendors_model() {
      Handle<String> email = customerEmail();

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
      Handle<String> email = customerEmail();

      assertThat(loch.dereference(email, QUARANTINED_LLM, acme()).granted())
          .hasValueSatisfying(text -> assertThat(text).contains("INV-4471"));
    }

    @Test
    @DisplayName("is still a handle everywhere else, and says nothing when printed")
    void is_still_a_handle_everywhere_else() {
      Handle<String> email = customerEmail();

      assertThat(email.toString()).doesNotContain("123-45-6789").contains("loch_");
    }
  }

  @Nested
  @DisplayName("the card token")
  class TheCardToken {

    private Handle<String> token() {
      return loch.hold(
          "tok_1P9xyz",
          String.class,
          Billing.of("acme", Integrity.ENDORSED, Tlp.RED, DataClass.CARDHOLDER));
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
      Handle<String> token = token();

      assertThat(loch.dereference(token, VENDOR_LLM, acme()).allowed()).isFalse();
      assertThat(loch.dereference(token, QUARANTINED_LLM, acme()).allowed()).isFalse();
      assertThat(loch.dereference(token, APPROVAL_CARD, acme("clearance", "finance")).allowed())
          .isFalse();
    }
  }

  @Nested
  @DisplayName("the approval card")
  class TheApprovalCard {

    private Handle<String> last4() {
      return loch.hold(
          "4821", String.class, Billing.of("acme", Integrity.ENDORSED, Tlp.AMBER, DataClass.PII));
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

    /**
     * The claim the whole design rests on, produced the way an application would produce it.
     *
     * <p>Nobody labels anything as conflicted. Two tenants' notes are folded into one report,
     * because somebody wrote a perfectly reasonable summariser and passed it perfectly reasonable
     * inputs. The result is unusable everywhere, and no rule had to be remembered for that to
     * happen.
     */
    @Test
    @DisplayName("folding two tenants' data makes a report that can go nowhere at all")
    void folding_two_tenants_data_makes_a_report_that_can_go_nowhere() {
      Handle<String> acmeNote =
          loch.hold(
              "acme disputes INV-1",
              String.class,
              Billing.of("acme", Integrity.ENDORSED, Tlp.CLEAR, DataClass.NONE));
      Handle<String> globexNote =
          loch.hold(
              "globex disputes INV-2",
              String.class,
              Billing.of("globex", Integrity.ENDORSED, Tlp.CLEAR, DataClass.NONE));

      Handle<Report> report =
          loch.deriveAll(List.of(acmeNote, globexNote), SUMMARISE, acme()).orThrow();

      assertThat(loch.label(report).tenant().conflicted()).isTrue();
      assertThat(loch.dereference(report, VENDOR_LLM, acme()).allowed()).isFalse();
      assertThat(loch.dereference(report, PAYMENT_PROCESSOR, acme()).allowed()).isFalse();
      assertThat(loch.dereference(report, QUARANTINED_LLM, acme()).allowed()).isFalse();
      assertThat(
              loch.dereference(report, VENDOR_LLM, AccessContext.of("tenant", "globex")).allowed())
          .isFalse();
      // It exists, and it remembers where it came from.
      assertThat(loch.lineage(report).parents()).containsExactly(acmeNote.id(), globexNote.id());
    }

    @Test
    @DisplayName("folding one tenant's own notes is perfectly usable")
    void folding_one_tenants_notes_is_usable() {
      Handle<String> first =
          loch.hold(
              "first note",
              String.class,
              Billing.of("acme", Integrity.ENDORSED, Tlp.CLEAR, DataClass.NONE));
      Handle<String> second =
          loch.hold(
              "second note",
              String.class,
              Billing.of("acme", Integrity.ENDORSED, Tlp.CLEAR, DataClass.NONE));

      Handle<Report> report = loch.deriveAll(List.of(first, second), SUMMARISE, acme()).orThrow();

      assertThat(loch.dereference(report, VENDOR_LLM, acme()).granted())
          .contains(new Report("first note / second note"));
    }

    /** The fold takes the most constrained of everything it read, not the first thing it read. */
    @Test
    @DisplayName("one restricted parent constrains the whole result")
    void one_restricted_parent_constrains_the_whole_result() {
      Handle<String> ordinary =
          loch.hold(
              "nothing special",
              String.class,
              Billing.of("acme", Integrity.ENDORSED, Tlp.CLEAR, DataClass.NONE));
      Handle<String> personal =
          loch.hold(
              "and their home address",
              String.class,
              Billing.of("acme", Integrity.ENDORSED, Tlp.AMBER, DataClass.PII));

      Handle<Report> report =
          loch.deriveAll(List.of(ordinary, personal), SUMMARISE, acme()).orThrow();

      assertThat(loch.label(report).dataClass()).isEqualTo(DataClass.PII);
      assertThat(loch.dereference(report, VENDOR_LLM, acme()).allowed()).isFalse();
      assertThat(loch.dereference(report, QUARANTINED_LLM, acme()).allowed()).isTrue();
    }

    @Test
    @DisplayName("a fold with nothing to fold is refused")
    void a_fold_with_nothing_to_fold_is_refused() {
      assertThat(loch.<String, Report>deriveAll(List.of(), SUMMARISE, acme()))
          .isInstanceOfSatisfying(
              Derived.Refused.class,
              refused -> assertThat(refused.reason()).isEqualTo(Derived.Reason.NO_PARENTS));
    }

    @Test
    @DisplayName("another tenant's data is refused, even though it is perfectly ordinary")
    void another_tenants_data_is_refused() {
      Handle<String> globex =
          loch.hold(
              "globex's entirely unremarkable note",
              String.class,
              Billing.of("globex", Integrity.ENDORSED, Tlp.CLEAR, DataClass.NONE));

      assertThat(loch.dereference(globex, VENDOR_LLM, acme()).allowed()).isFalse();
    }

    @Test
    @DisplayName("one tenant's ordinary data is fine")
    void one_tenants_ordinary_data_is_fine() {
      Handle<String> held =
          loch.hold(
              "nothing secret",
              String.class,
              Billing.of("acme", Integrity.ENDORSED, Tlp.CLEAR, DataClass.NONE));

      assertThat(loch.dereference(held, VENDOR_LLM, acme()).allowed()).isTrue();
    }
  }

  @Nested
  @DisplayName("the gate itself")
  class TheGate {

    @Test
    @DisplayName("refuses an id nobody minted, rather than computing anything")
    void refuses_an_id_nobody_minted() {
      Handle<String> invented =
          new Handle<>(new HandleId("loch_whatever-i-like"), TypeRef.of(String.class));

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
      Handle<String> email = customerEmail();
      Handle<Integer> lying = new Handle<>(email.id(), TypeRef.of(Integer.class));

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

  @Nested
  @DisplayName("deriving")
  class Deriving {

    private Handle<DisputeClaim> claim() {
      return loch.hold(
          new DisputeClaim("INV-4471", "charged twice"),
          DisputeClaim.class,
          Billing.of("acme", Integrity.UNENDORSED, Tlp.AMBER, DataClass.PII));
    }

    @Test
    @DisplayName("a projection inherits its parent's labels exactly")
    void a_projection_inherits_its_parents_labels() {
      Handle<InvoiceNumber> number = loch.derive(claim(), CLAIMED_INVOICE).orThrow();

      assertThat(loch.label(number))
          .isEqualTo(Billing.of("acme", Integrity.UNENDORSED, Tlp.AMBER, DataClass.PII));
      assertThat(loch.dereference(number, QUARANTINED_LLM, acme()).granted())
          .contains(new InvoiceNumber("INV-4471"));
      assertThat(loch.dereference(number, VENDOR_LLM, acme()).allowed()).isFalse();
    }

    /** An invoice number a customer typed is a question, not an answer. */
    @Test
    @DisplayName("extracting a field does not make it trustworthy")
    void extracting_a_field_does_not_make_it_trustworthy() {
      Handle<InvoiceNumber> number = loch.derive(claim(), CLAIMED_INVOICE).orThrow();

      assertThat(loch.label(number).integrity()).isEqualTo(Integrity.UNENDORSED);
    }

    @Test
    @DisplayName("records what it came from, so erasure has something to follow")
    void records_what_it_came_from() {
      Handle<DisputeClaim> parent = claim();

      Handle<InvoiceNumber> number = loch.derive(parent, CLAIMED_INVOICE).orThrow();

      assertThat(loch.lineage(number).parents()).containsExactly(parent.id());
      assertThat(loch.lineage(number).derivation()).contains(CLAIMED_INVOICE.value());
      assertThat(loch.lineage(parent).asserted()).isTrue();
    }

    /**
     * Every derivation makes a new value, and that is the whole rule.
     *
     * <p>There used to be deduplication here: a derivation that declared itself deterministic was
     * keyed on its parents and reused. It went, because the function sees the access context and
     * the key did not, so a second caller was handed the first caller's answer without the function
     * ever running -- across tenants. Saving a row was not worth a rule with an exception in it.
     */
    @Test
    @DisplayName("deriving twice makes two values, and each caller gets its own answer")
    void deriving_twice_makes_two_values() {
      Handle<DisputeClaim> parent = claim();

      Handle<InvoiceNumber> once = loch.derive(parent, CLAIMED_INVOICE).orThrow();
      Handle<InvoiceNumber> twice = loch.derive(parent, CLAIMED_INVOICE).orThrow();

      assertThat(once.id()).isNotEqualTo(twice.id());
      assertThat(loch.lineage(once).parents()).containsExactly(parent.id());
      assertThat(loch.lineage(twice).parents()).containsExactly(parent.id());
    }

    @Test
    @DisplayName("two different parents give two different handles")
    void two_different_parents_give_two_different_handles() {
      assertThat(loch.derive(claim(), CLAIMED_INVOICE).orThrow().id())
          .isNotEqualTo(loch.derive(claim(), CLAIMED_INVOICE).orThrow().id());
    }

    @Test
    @DisplayName("an unregistered name is refused rather than run")
    void an_unregistered_name_is_refused() {
      DerivationId<DisputeClaim, InvoiceNumber> invented = DerivationId.of("whatever-i-like");

      assertThat(loch.derive(claim(), invented))
          .isInstanceOfSatisfying(
              Derived.Refused.class,
              refused -> assertThat(refused.reason()).isEqualTo(Derived.Reason.NO_SUCH_DERIVATION));
    }
  }

  @Nested
  @DisplayName("weakening a label")
  class Weakening {

    private Handle<String> token() {
      return loch.hold(
          "tok_1P9xyz4821",
          String.class,
          Billing.of("acme", Integrity.ENDORSED, Tlp.RED, DataClass.CARDHOLDER));
    }

    private AccessContext preparingApproval() {
      return acme("tool", "prepare_approval");
    }

    @Test
    @DisplayName("truncating a card lowers it to PII, which a person may then see")
    void truncating_a_card_lowers_it_to_pii() {
      Handle<Last4> last4 = loch.derive(token(), CARD_LAST4, preparingApproval()).orThrow();

      assertThat(loch.label(last4).dataClass()).isEqualTo(DataClass.PII);
      assertThat(loch.dereference(last4, APPROVAL_CARD, acme("clearance", "finance")).granted())
          .contains(new Last4("4821"));
    }

    @Test
    @DisplayName("and lowers nothing it did not name: still acme's, still endorsed")
    void lowers_nothing_it_did_not_name() {
      Handle<Last4> last4 = loch.derive(token(), CARD_LAST4, preparingApproval()).orThrow();

      assertThat(loch.label(last4).tenant().resolved()).contains("acme");
      assertThat(loch.label(last4).integrity()).isEqualTo(Integrity.ENDORSED);
    }

    /**
     * Lowering one dimension is not "declassified", it is declassified in one respect. The four
     * digits stop being cardholder data and remain RED, and RED does not reach an approval card.
     */
    @Test
    @DisplayName("lowering only one dimension leaves the other still blocking")
    void lowering_only_one_dimension_leaves_the_other_blocking() {
      Handle<Last4> partly = loch.derive(token(), CARD_LAST4_PARTIAL, acme()).orThrow();

      assertThat(loch.label(partly).dataClass()).isEqualTo(DataClass.PII);
      assertThat(loch.label(partly).tlp()).isEqualTo(Tlp.RED);
      assertThat(loch.dereference(partly, APPROVAL_CARD, acme("clearance", "finance")).allowed())
          .isFalse();
    }

    @Test
    @DisplayName("is offered only where it was declared to be")
    void is_offered_only_where_declared() {
      assertThat(loch.derive(token(), CARD_LAST4, acme()))
          .isInstanceOfSatisfying(
              Derived.Refused.class,
              refused -> assertThat(refused.reason()).isEqualTo(Derived.Reason.NOT_AVAILABLE_HERE));
    }

    /** Declaring a relabel that raises is a mistake, not a policy. Ordinary derivation raises. */
    @Test
    @DisplayName("a relabel that does not actually lower is refused")
    void a_relabel_that_does_not_lower_is_refused() {
      Handle<DisputeClaim> endorsed =
          loch.hold(
              new DisputeClaim("INV-1", "x"),
              DisputeClaim.class,
              Billing.of("acme", Integrity.ENDORSED, Tlp.CLEAR, DataClass.NONE));

      assertThat(loch.derive(endorsed, WISHFUL))
          .isInstanceOfSatisfying(
              Derived.Refused.class,
              refused -> assertThat(refused.reason()).isEqualTo(Derived.Reason.NOT_A_LOWERING));
    }

    @Test
    @DisplayName("the manifest names every operation that can weaken a label")
    void the_manifest_names_every_weakening_operation() {
      Manifest manifest = loch.manifest();

      assertThat(manifest.weakening()).isNotEmpty();
      assertThat(manifest.weakening())
          .extracting(Manifest.Entry::name)
          .contains("Card.last4", "Card.last4.dataClassOnly")
          .doesNotContain("DisputeClaim.invoiceNumber");
    }

    /**
     * A fold that lowers is as privileged as a derivation that lowers. Leaving folds out of the
     * list was a way of claiming the manifest is complete and not meaning it.
     */
    @Test
    @DisplayName("including ones that read several values, which used to weaken labels invisibly")
    void including_ones_that_read_several_values() {
      assertThat(loch.manifest().weakening())
          .extracting(Manifest.Entry::name)
          .contains(SUMMARISE_FOR_RELEASE.value())
          .doesNotContain(SUMMARISE.value());
    }

    /** A document meant to be diffed between reviews cannot reorder itself every restart. */
    @Test
    @DisplayName("and the report keeps the order everything was registered in")
    void the_report_keeps_registration_order() {
      Manifest manifest = loch.manifest();

      assertThat(manifest.toString()).isEqualTo(loch.manifest().toString());
      assertThat(manifest.destinations()).extracting(Manifest.Entry::name).startsWith("vendor-llm");
    }

    @Test
    @DisplayName("and is readable, which is the whole point of it")
    void and_is_readable() {
      String report = loch.manifest().toString();

      System.out.println(report);
      assertThat(report)
          .contains("WEAKENS LABELS")
          .contains("payment-processor")
          .contains("can WEAKEN a label");
    }
  }

  @Nested
  @DisplayName("asking instead of taking")
  class Checks {

    private Handle<Account> account() {
      return loch.hold(
          new Account("ACC-1", "someone@acme.example"),
          Account.class,
          Billing.of("acme", Integrity.ENDORSED, Tlp.AMBER, DataClass.PII));
    }

    @Test
    @DisplayName("answers the question without the account ever leaving")
    void answers_without_the_account_leaving() {
      Handle<Account> account = account();

      assertThat(loch.ask(account, OWNED_BY, "someone@acme.example").isTrue()).isTrue();
      assertThat(loch.ask(account, OWNED_BY, "attacker@elsewhere.example").isFalse()).isTrue();
    }

    /** A refusal is not a "no". Collapsing them is how a denied check reads as a failed one. */
    @Test
    @DisplayName("a refusal is neither true nor false")
    void a_refusal_is_neither_true_nor_false() {
      QuestionId<Account, String> invented = QuestionId.of("whatever");

      Answer answer = loch.ask(account(), invented, "x");

      assertThat(answer.isTrue()).isFalse();
      assertThat(answer.isFalse()).isFalse();
      assertThat(answer.ran()).isFalse();
    }

    @Test
    @DisplayName("refuses to look at a value it was never meant to see")
    void refuses_to_look_at_a_value_it_was_never_meant_to_see() {
      Loch<Billing> choosy =
          MemoryLoch.create(
              c ->
                  c.lattice(Billing.LATTICE)
                      .withoutAudit()
                      .askingWhoIsAsking(edge::get)
                      .question(
                          Question.<Billing, Account, String>of(
                                  OWNED_BY, Account.class, (account, sender) -> true)
                              .accepting(
                                  ctx ->
                                      Billing.ceilingFor(
                                          ctx, Integrity.ENDORSED, Tlp.CLEAR, DataClass.NONE))
                              .build()));
      Handle<Account> secret =
          choosy.hold(
              new Account("ACC-2", "x@y.example"),
              Account.class,
              Billing.of("acme", Integrity.ENDORSED, Tlp.RED, DataClass.CARDHOLDER));

      assertThat(choosy.ask(secret, OWNED_BY, "x@y.example", acme()))
          .isInstanceOfSatisfying(
              Answer.Refused.class,
              refused -> assertThat(refused.reason()).isEqualTo(Answer.Reason.ABOVE_CEILING));
    }
  }

  @Nested
  @DisplayName("not leaking by accident")
  class NotLeaking {

    @Test
    @DisplayName("an allowed result does not print the value it is carrying")
    void an_allowed_result_does_not_print_the_value() {
      Dereferenced<String> allowed = loch.dereference(customerEmail(), QUARANTINED_LLM, acme());

      assertThat(allowed.allowed()).isTrue();
      assertThat(allowed.toString()).doesNotContain("123-45-6789");
    }

    @Test
    @DisplayName("a refusal names the destination but not the labels")
    void a_refusal_names_the_destination_but_not_the_labels() {
      Dereferenced<String> denied = loch.dereference(customerEmail(), VENDOR_LLM, acme());

      String detail = ((Dereferenced.Denied<String>) denied).detail();
      assertThat(detail).contains("vendor-llm").doesNotContain("acme").doesNotContain("PII");
    }

    @Test
    @DisplayName("unless the application asks for the explanation")
    void unless_the_application_asks_for_the_explanation() {
      Loch<Billing> chatty =
          MemoryLoch.create(
              c ->
                  c.lattice(Billing.LATTICE)
                      .withoutAudit()
                      .askingWhoIsAsking(edge::get)
                      .explainRefusals()
                      .destination(
                          tenantScoped(VENDOR_LLM, Integrity.ENDORSED, Tlp.CLEAR, DataClass.NONE)));
      Handle<String> held =
          chatty.hold(
              "x",
              String.class,
              Billing.of("acme", Integrity.UNENDORSED, Tlp.AMBER, DataClass.PII));

      Dereferenced<String> denied = chatty.dereference(held, VENDOR_LLM, acme());

      assertThat(((Dereferenced.Denied<String>) denied).detail()).contains("PII");
    }

    /** A policy that cannot be evaluated has not said yes. */
    @Test
    @DisplayName("a destination whose ceiling throws denies, rather than exploding")
    void a_destination_whose_ceiling_throws_denies() {
      DestinationId broken = DestinationId.of("broken");
      Loch<Billing> fragile =
          MemoryLoch.create(
              c ->
                  c.lattice(Billing.LATTICE)
                      .withoutAudit()
                      .askingWhoIsAsking(edge::get)
                      .destination(
                          Destinations.varying(
                              broken,
                              ctx -> {
                                throw new IllegalStateException("policy service is down");
                              })));
      Handle<String> held =
          fragile.hold(
              "x", String.class, Billing.of("acme", Integrity.ENDORSED, Tlp.CLEAR, DataClass.NONE));

      Dereferenced<String> result = fragile.dereference(held, broken, acme());

      assertThat(result.allowed()).isFalse();
    }
  }

  @Nested
  @DisplayName("the record")
  class TheRecord {

    @Test
    @DisplayName("says who reached what, and never what the value was")
    void says_who_reached_what_and_never_the_value() {
      loch.dereference(customerEmail(), QUARANTINED_LLM, acme());

      AuditRecord entry = audit.of(AuditRecord.Operation.DEREFERENCE).getLast();
      assertThat(entry.outcome()).isEqualTo(AuditRecord.Outcome.ALLOWED);
      assertThat(entry.target()).contains("quarantined-llm");
      assertThat(entry.context()).containsEntry("tenant", "acme");
      assertThat(entry.toString()).doesNotContain("123-45-6789");
    }

    /** A thousand refusals against one value is the interesting event. */
    @Test
    @DisplayName("records refusals as carefully as permissions")
    void records_refusals_as_carefully_as_permissions() {
      loch.dereference(customerEmail(), VENDOR_LLM, acme());

      AuditRecord entry = audit.of(AuditRecord.Operation.DEREFERENCE).getLast();
      assertThat(entry.outcome()).isEqualTo(AuditRecord.Outcome.REFUSED);
      assertThat(entry.reason()).contains("ABOVE_CEILING");
    }

    @Test
    @DisplayName("records holding, because that is where labels are asserted rather than computed")
    void records_holding() {
      Handle<String> email = customerEmail();

      assertThat(audit.of(AuditRecord.Operation.HOLD))
          .anySatisfy(entry -> assertThat(entry.value()).isEqualTo(email.id()));
    }

    @Test
    @DisplayName("records a check, with the answer but never the question")
    void records_a_check_with_the_answer_but_not_the_question() {
      Handle<Account> account =
          loch.hold(
              new Account("ACC-1", "someone@acme.example"),
              Account.class,
              Billing.of("acme", Integrity.ENDORSED, Tlp.AMBER, DataClass.PII));

      loch.ask(account, OWNED_BY, "someone@acme.example", acme());

      AuditRecord entry = audit.of(AuditRecord.Operation.ASK).getLast();
      assertThat(entry.reason()).contains("answered true");
      assertThat(entry.toString()).doesNotContain("someone@acme.example");
    }

    /** The event an auditor most wants to find. */
    @Test
    @DisplayName("says so when a derivation weakened a label")
    void says_so_when_a_derivation_weakened_a_label() {
      Handle<String> token =
          loch.hold(
              "tok_1P9xyz4821",
              String.class,
              Billing.of("acme", Integrity.ENDORSED, Tlp.RED, DataClass.CARDHOLDER));

      loch.derive(token, CARD_LAST4, acme("tool", "prepare_approval"));

      AuditRecord entry = audit.of(AuditRecord.Operation.DERIVE).getLast();
      assertThat(entry.reason()).hasValueSatisfying(r -> assertThat(r).startsWith("weakened from"));
      assertThat(entry.target()).contains("Card.last4");
    }

    @Test
    @DisplayName("an ordinary derivation is recorded without that note")
    void an_ordinary_derivation_is_recorded_without_that_note() {
      Handle<DisputeClaim> claim =
          loch.hold(
              new DisputeClaim("INV-4471", "charged twice"),
              DisputeClaim.class,
              Billing.of("acme", Integrity.UNENDORSED, Tlp.AMBER, DataClass.PII));

      loch.derive(claim, CLAIMED_INVOICE, acme());

      assertThat(audit.of(AuditRecord.Operation.DERIVE).getLast().reason()).isEmpty();
    }

    @Test
    @DisplayName("but one made from several values says so, since it is more constrained than any")
    void one_made_from_several_says_so() {
      Handle<String> first =
          loch.hold(
              "a", String.class, Billing.of("acme", Integrity.ENDORSED, Tlp.CLEAR, DataClass.NONE));
      Handle<String> second =
          loch.hold(
              "b", String.class, Billing.of("acme", Integrity.ENDORSED, Tlp.CLEAR, DataClass.NONE));

      loch.deriveAll(java.util.List.of(first, second), SUMMARISE, acme());

      assertThat(audit.of(AuditRecord.Operation.DERIVE).getLast().reason())
          .contains("combined from 2 values");
    }

    /**
     * A control whose log is quietly failing still produces the report.
     *
     * <p>And nothing is left behind: the record is written before the value is stored, because the
     * other order commits a secret durably under an id the caller never receives.
     */
    @Test
    @DisplayName("an access that cannot be audited does not happen, and stores nothing")
    void an_access_that_cannot_be_audited_does_not_happen() {
      Loch<Billing> unloggable =
          MemoryLoch.create(
              c ->
                  c.lattice(Billing.LATTICE)
                      .askingWhoIsAsking(edge::get)
                      .auditor(
                          record -> {
                            throw new IllegalStateException("the audit sink is down");
                          })
                      .destination(
                          tenantScoped(
                              QUARANTINED_LLM, Integrity.UNENDORSED, Tlp.AMBER, DataClass.PII)));

      MemoryStorage<Billing> storage = new MemoryStorage<>();
      Loch<Billing> watched =
          new DefaultLoch<>(
              configuredTo(
                  record -> {
                    throw new IllegalStateException("the audit sink is down");
                  }),
              storage);

      assertThatThrownBy(
              () ->
                  watched.hold(
                      "anything",
                      String.class,
                      Billing.of("acme", Integrity.ENDORSED, Tlp.CLEAR, DataClass.NONE)))
          .isInstanceOf(IllegalStateException.class);

      // Nothing was written. The other order leaves a secret nobody can reach, read or erase.
      assertThat(storage.everything()).isEmpty();
    }

    @Test
    @DisplayName("keeping no record is something you say, not something you omit")
    void keeping_no_record_is_something_you_say() {
      assertThatThrownBy(() -> MemoryLoch.<Billing>create(c -> c.lattice(Billing.LATTICE)))
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("withoutAudit");
    }
  }

  /** Builds the same policy this test uses, with a chosen auditor. */
  private LochConfig<Billing> configuredTo(Auditor auditor) {
    LochConfig<Billing> config = new LochConfig<>();
    config.lattice(Billing.LATTICE).auditor(auditor).askingWhoIsAsking(edge::get);
    return config;
  }

  @Nested
  @DisplayName("refusals reach the record too")
  class RefusalsAreRecorded {

    private Handle<DisputeClaim> claim() {
      return loch.hold(
          new DisputeClaim("INV-4471", "charged twice"),
          DisputeClaim.class,
          Billing.of("acme", Integrity.UNENDORSED, Tlp.AMBER, DataClass.PII));
    }

    /**
     * The one that mattered most: a declined derivation has already read the plaintext.
     *
     * <p>A derivation function runs, looks at the value, decides the answer is no, and returns
     * nothing. Before this, that left no trace at all -- so a caller could read a value it was not
     * entitled to act on, repeatedly, and the log would be empty.
     */
    @Test
    @DisplayName("a derivation that declined is recorded, because it saw the value first")
    void a_derivation_that_declined_is_recorded() {
      audit.clear();

      loch.derive(claim(), DECLINES, acme());

      assertThat(audit.of(AuditRecord.Operation.DERIVE))
          .isNotEmpty()
          .anySatisfy(
              entry -> {
                assertThat(entry.outcome()).isEqualTo(AuditRecord.Outcome.REFUSED);
                assertThat(entry.reason()).contains("DECLINED");
                assertThat(entry.target()).contains(DECLINES.value());
              });
    }

    /** Refused before the value was looked at, so the record honestly has no label to give. */
    @Test
    @DisplayName("a derivation not offered here is recorded, and says nothing about the value")
    void a_derivation_not_offered_here_is_recorded() {
      Handle<String> token =
          loch.hold(
              "tok_1P9xyz4821",
              String.class,
              Billing.of("acme", Integrity.ENDORSED, Tlp.RED, DataClass.CARDHOLDER));
      audit.clear();

      loch.derive(token, CARD_LAST4, acme());

      AuditRecord entry = audit.of(AuditRecord.Operation.DERIVE).getLast();
      assertThat(entry.outcome()).isEqualTo(AuditRecord.Outcome.REFUSED);
      assertThat(entry.reason()).contains("NOT_AVAILABLE_HERE");
      assertThat(entry.label()).isEmpty();
    }

    /** But a refusal that happened after the value was read records what was read. */
    @Test
    @DisplayName("a refusal that came after reading the value records its label")
    void a_refusal_after_reading_records_its_label() {
      audit.clear();

      loch.derive(claim(), DECLINES, acme());

      assertThat(audit.of(AuditRecord.Operation.DERIVE).getLast().label())
          .hasValueSatisfying(label -> assertThat(label).contains("UNENDORSED"));
    }

    /** A check leaks a bit per call, so a thousand refused ones is the interesting event. */
    @Test
    @DisplayName("a refused check is recorded")
    void a_refused_check_is_recorded() {
      audit.clear();

      loch.ask(claim(), QuestionId.of("no-such-check"), "x", acme());

      assertThat(audit.of(AuditRecord.Operation.ASK))
          .anySatisfy(
              entry -> {
                assertThat(entry.outcome()).isEqualTo(AuditRecord.Outcome.REFUSED);
                assertThat(entry.reason()).contains("NO_SUCH_QUESTION");
              });
    }

    @Test
    @DisplayName("a fold refused at the gate is recorded")
    void a_fold_refused_at_the_gate_is_recorded() {
      audit.clear();

      loch.<String, Report>deriveAll(java.util.List.of(), SUMMARISE, acme());

      assertThat(audit.of(AuditRecord.Operation.DERIVE))
          .anySatisfy(
              entry -> {
                assertThat(entry.outcome()).isEqualTo(AuditRecord.Outcome.REFUSED);
                assertThat(entry.reason()).contains("NO_PARENTS");
              });
    }

    @Test
    @DisplayName("and a refusal never says what the value was")
    void a_refusal_never_says_what_the_value_was() {
      audit.clear();

      loch.derive(claim(), DECLINES, acme());

      assertThat(audit.of(AuditRecord.Operation.DERIVE).getLast().toString())
          .doesNotContain("charged twice")
          .doesNotContain("INV-4471");
    }
  }
}
