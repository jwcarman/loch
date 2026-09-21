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
import org.jwcarman.loch.lattice.Axis;
import org.jwcarman.loch.lattice.Ceiling;
import org.jwcarman.loch.lattice.Constraint;
import org.jwcarman.loch.lattice.Label;

/**
 * A multi-tenant SaaS billing system, written the way an application would write it.
 *
 * <p>This is the design's own acceptance test. If something here is awkward to write, the library
 * is wrong and this is where we find out.
 *
 * <p><b>The domain bound is {@code Object}</b>, not a marker interface. Every stored type here
 * (plaintext strings, {@link DisputeClaim}, {@link Account}, ...) already is what it is; a marker
 * interface would exist only to satisfy the compiler; and being able to hold a bare {@code String}
 * keeps most of this scenario's assertions exactly as an application would write them.
 */
@DisplayName("A billing system under a charter")
class BillingScenarioTest {

  private static final SurrogateType<String> STRING_TYPE = SurrogateType.of(String.class);
  private static final SurrogateType<DisputeClaim> DISPUTE_CLAIM_TYPE =
      SurrogateType.of(DisputeClaim.class);
  private static final SurrogateType<Account> ACCOUNT_TYPE = SurrogateType.of(Account.class);
  private static final SurrogateType<Report> REPORT_TYPE = SurrogateType.of(Report.class);
  private static final SurrogateType<InvoiceNumber> INVOICE_NUMBER_TYPE =
      SurrogateType.of(InvoiceNumber.class);
  private static final SurrogateType<Last4> LAST4_TYPE = SurrogateType.of(Last4.class);

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

  private static final Axis<String> TENANT = Axis.matching("tenant");
  private static final Axis<Integrity> INTEGRITY =
      Axis.ladder("integrity", Integrity.ENDORSED, Integrity.UNENDORSED);
  private static final Axis<Tlp> TLP = Axis.ladder("tlp", Tlp.CLEAR, Tlp.GREEN, Tlp.AMBER, Tlp.RED);
  private static final Axis<DataClass> DATA_CLASS =
      Axis.ladder("dataClass", DataClass.NONE, DataClass.PII, DataClass.CARDHOLDER);

  /** What a tenant's ordinary business data looks like. */
  private static Label label(String tenant, Integrity integrity, Tlp tlp, DataClass dataClass) {
    return Label.of(TENANT, tenant)
        .with(INTEGRITY, integrity)
        .with(TLP, tlp)
        .with(DATA_CLASS, dataClass);
  }

  /**
   * A ceiling for an access made on behalf of one tenant.
   *
   * <p>The tenant comes from the access, never from the destination. Naming the tenant of the
   * access is what makes foreign data and mixed data both fall below the bar -- a mixture is not
   * any one tenant, so it satisfies no {@code atMost} and no {@code any} either.
   *
   * <p>An access naming no tenant gets no ceiling rather than a broad one. The store treats a
   * ceiling it cannot evaluate as a refusal and writes the line, which is what a gate unable to say
   * it is open should do.
   */
  private static Ceiling ceilingFor(
      AccessContext ctx, Integrity integrity, Tlp tlp, DataClass dataClass) {
    String tenant =
        ctx.get("tenant")
            .orElseThrow(
                () -> new IllegalStateException("this access says nothing about a tenant"));
    return Ceiling.of(TENANT, Constraint.atMost(tenant))
        .with(INTEGRITY, Constraint.atMost(integrity))
        .with(TLP, Constraint.atMost(tlp))
        .with(DATA_CLASS, Constraint.atMost(dataClass));
  }

  // ---------------------------------------------------------------- what travels through the store

  record DisputeClaim(String invoiceNumber, String reason) {}

  record InvoiceNumber(String value) {}

  record Last4(String digits) {}

  record Account(String number, String email) {}

  record Report(String text) {}

  /** What an operation reading plaintext may look at: always the acting tenant's own data. */
  private static java.util.function.Function<AccessContext, Ceiling> reading(
      Integrity integrity, Tlp tlp, DataClass dataClass) {
    return ctx -> ceilingFor(ctx, integrity, tlp, dataClass);
  }

  /** The approval card's ceiling: a finance approver sees more than anyone else does. */
  private static java.util.function.Function<AccessContext, Ceiling> approvalCardCeiling() {
    return ctx ->
        ceilingFor(
            ctx,
            Integrity.ENDORSED,
            Tlp.AMBER,
            ctx.has("clearance", "finance") ? DataClass.PII : DataClass.NONE);
  }

  /**
   * The generic labelling function every source below is declared with: it reads the whole label,
   * axis by axis, out of whatever context {@link #holdAs} put there. An source is still the only
   * door a value can enter through, and its name is still a fixed property of the door -- but the
   * four label axes themselves come from the access, the same way a tenant always did.
   */
  private static Label labelFrom(AccessContext ctx) {
    return label(
        ctx.get("tenant").orElse(""),
        Integrity.valueOf(ctx.get("integrity").orElse(Integrity.UNENDORSED.name())),
        Tlp.valueOf(ctx.get("tlp").orElse(Tlp.CLEAR.name())),
        DataClass.valueOf(ctx.get("dataClass").orElse(DataClass.NONE.name())));
  }

  private static final String CLAIMED_INVOICE = "DisputeClaim.invoiceNumber";
  private static final String CARD_LAST4 = "Card.last4";
  private static final String CARD_LAST4_PARTIAL = "Card.last4.dataClassOnly";
  private static final String SUMMARISE = "notes.summarise";
  private static final String SUMMARISE_FOR_RELEASE = "notes.summarise.forRelease";
  private static final String DECLINES = "DisputeClaim.alwaysDeclines";
  private static final String WISHFUL = "DisputeClaim.invoiceNumber.trustMe";

  private final MemoryStorage storage = new MemoryStorage();

  /**
   * Standing in for the edge: a request, a message header, a session.
   *
   * <p>The tests set this rather than passing identity into calls, because a caller cannot be
   * allowed to say who it is. That is not a testing detail; it is the property that makes any of
   * the rest of this mean anything.
   */
  private final java.util.concurrent.atomic.AtomicReference<AccessContext> edge =
      new java.util.concurrent.atomic.AtomicReference<>(AccessContext.empty());

  private final DefaultCharter config =
      new DefaultCharter(TENANT, INTEGRITY, TLP, DATA_CLASS).currentAccess(edge::get);

  // ---------------------------------------------------------------- doors in

  private final Conceal<String> customerMail =
      config.source("customer-mail", STRING_TYPE, BillingScenarioTest::labelFrom);

  private final Conceal<String> cardTokens =
      config.source("card-tokens", STRING_TYPE, BillingScenarioTest::labelFrom);

  private final Conceal<String> notes =
      config.source("notes", STRING_TYPE, BillingScenarioTest::labelFrom);

  private final Conceal<String> last4Digits =
      config.source("last4-digits", STRING_TYPE, BillingScenarioTest::labelFrom);

  private final Conceal<DisputeClaim> disputeClaims =
      config.source("dispute-claims", DISPUTE_CLAIM_TYPE, BillingScenarioTest::labelFrom);

  private final Conceal<Account> accounts =
      config.source("accounts", ACCOUNT_TYPE, BillingScenarioTest::labelFrom);

  // ---------------------------------------------------------------- doors out: one per (door,
  // type) pair. An sink is narrowed by type as well as by label, so a door that used to admit
  // whatever handle a caller presented is now one capability per shape of value it actually reads.
  // Holding everything text-shaped as a plain String (rather than one wrapper record per door)
  // keeps this down to one text sink per destination instead of three.

  // A vendor's model: nothing personal, nothing unendorsed.
  private final Reveal<String> vendorLlmText =
      config
          .destination(
              "vendor-llm",
              ctx -> ceilingFor(ctx, Integrity.ENDORSED, Tlp.CLEAR, DataClass.NONE),
              STRING_TYPE)
          .reading(STRING_TYPE);

  private final Reveal<Report> vendorLlmReports =
      config
          .destination(
              "vendor-llm-reports",
              ctx -> ceilingFor(ctx, Integrity.ENDORSED, Tlp.CLEAR, DataClass.NONE),
              REPORT_TYPE)
          .reading(REPORT_TYPE);

  private final Reveal<InvoiceNumber> vendorLlmInvoice =
      config
          .destination(
              "vendor-llm-invoice",
              ctx -> ceilingFor(ctx, Integrity.ENDORSED, Tlp.CLEAR, DataClass.NONE),
              INVOICE_NUMBER_TYPE)
          .reading(INVOICE_NUMBER_TYPE);

  // Ours, on our own hardware. Reads untrusted mail; holds no secrets.
  private final Reveal<String> quarantinedLlmText =
      config
          .destination(
              "quarantined-llm",
              ctx -> ceilingFor(ctx, Integrity.UNENDORSED, Tlp.AMBER, DataClass.PII),
              STRING_TYPE)
          .reading(STRING_TYPE);

  private final Reveal<Report> quarantinedLlmReports =
      config
          .destination(
              "quarantined-llm-reports",
              ctx -> ceilingFor(ctx, Integrity.UNENDORSED, Tlp.AMBER, DataClass.PII),
              REPORT_TYPE)
          .reading(REPORT_TYPE);

  private final Reveal<InvoiceNumber> quarantinedLlmInvoice =
      config
          .destination(
              "quarantined-llm-invoice",
              ctx -> ceilingFor(ctx, Integrity.UNENDORSED, Tlp.AMBER, DataClass.PII),
              INVOICE_NUMBER_TYPE)
          .reading(INVOICE_NUMBER_TYPE);

  // The only place cardholder data may go, anywhere in the system.
  private final Reveal<String> paymentProcessorText =
      config
          .destination(
              "payment-processor",
              ctx -> ceilingFor(ctx, Integrity.ENDORSED, Tlp.RED, DataClass.CARDHOLDER),
              STRING_TYPE)
          .reading(STRING_TYPE);

  private final Reveal<Report> paymentProcessorReports =
      config
          .destination(
              "payment-processor-reports",
              ctx -> ceilingFor(ctx, Integrity.ENDORSED, Tlp.RED, DataClass.CARDHOLDER),
              REPORT_TYPE)
          .reading(REPORT_TYPE);

  // A person. What they may see depends on who they are.
  private final Reveal<String> approvalCardText =
      config.destination("approval-card", approvalCardCeiling(), STRING_TYPE).reading(STRING_TYPE);

  private final Reveal<Last4> approvalCardLast4 =
      config
          .destination("approval-card-last4", approvalCardCeiling(), LAST4_TYPE)
          .reading(LAST4_TYPE);

  // ---------------------------------------------------------------- derivations and folds

  // A projection. Cannot weaken anything, so it needs no ceremony.
  private final Derivation<DisputeClaim, InvoiceNumber> claimedInvoice =
      config.derivation(
          CLAIMED_INVOICE,
          DISPUTE_CLAIM_TYPE,
          INVOICE_NUMBER_TYPE,
          claim -> new InvoiceNumber(claim.invoiceNumber()),
          d -> d.accepting(reading(Integrity.UNENDORSED, Tlp.AMBER, DataClass.PII)));

  // Truncating a card IS a declassification, and PCI auditors ask about it.
  private final Derivation<String, Last4> cardLast4 =
      config.derivation(
          CARD_LAST4,
          STRING_TYPE,
          LAST4_TYPE,
          token -> new Last4(token.substring(token.length() - 4)),
          d ->
              d.accepting(ctx -> ceilingFor(ctx, Integrity.ENDORSED, Tlp.RED, DataClass.CARDHOLDER))
                  // Both dimensions, deliberately: four digits are neither cardholder
                  // data nor RED any more, and saying so is the reviewed act.
                  .lowering(joined -> joined.with(DATA_CLASS, DataClass.PII).with(TLP, Tlp.AMBER))
                  .availableTo(ctx -> ctx.has("tool", "prepare_approval")));

  // The same truncation, lowering only one dimension. Still cannot be shown.
  private final Derivation<String, Last4> cardLast4Partial =
      config.derivation(
          CARD_LAST4_PARTIAL,
          STRING_TYPE,
          LAST4_TYPE,
          token -> new Last4(token.substring(token.length() - 4)),
          d ->
              d.accepting(reading(Integrity.ENDORSED, Tlp.RED, DataClass.CARDHOLDER))
                  .lowering(joined -> joined.with(DATA_CLASS, DataClass.PII)));

  // Declares itself an endorsement without checking anything. The charter refuses it.
  private final Derivation<DisputeClaim, InvoiceNumber> wishful =
      config.derivation(
          WISHFUL,
          DISPUTE_CLAIM_TYPE,
          INVOICE_NUMBER_TYPE,
          claim -> new InvoiceNumber(claim.invoiceNumber()),
          d ->
              d.accepting(reading(Integrity.ENDORSED, Tlp.CLEAR, DataClass.NONE))
                  .lowering(joined -> joined.with(INTEGRITY, Integrity.UNENDORSED)));

  // Several values in, one out. Every parent's label lands on the result.
  private final Fold<String, Report> summarise =
      config.fold(
          SUMMARISE,
          STRING_TYPE,
          REPORT_TYPE,
          notes -> new Report(String.join(" / ", notes)),
          // An internal reporting job, entitled to read across tenants. The point
          // of the test below is what happens to what it produces, not whether it
          // may read: a ceiling would refuse the combination earlier, and then
          // there would be nothing to demonstrate.
          d ->
              d.accepting(
                  ctx ->
                      Ceiling.of(TENANT, Constraint.any())
                          .with(INTEGRITY, Constraint.any())
                          .with(TLP, Constraint.any())
                          .with(DATA_CLASS, Constraint.any())));

  // Reads the value, then says no. The refusal has to be recorded because the
  // function already saw the plaintext.
  private final Derivation<DisputeClaim, InvoiceNumber> declines =
      config.checking(
          DECLINES,
          DISPUTE_CLAIM_TYPE,
          INVOICE_NUMBER_TYPE,
          (claim, ctx) -> java.util.Optional.empty(),
          d -> d.accepting(reading(Integrity.UNENDORSED, Tlp.AMBER, DataClass.PII)));

  // A fold that lowers is as privileged as a derivation that lowers.
  private final Fold<String, Report> summariseForRelease =
      config.fold(
          SUMMARISE_FOR_RELEASE,
          STRING_TYPE,
          REPORT_TYPE,
          notes -> new Report("redacted summary of " + notes.size()),
          d ->
              d.accepting(reading(Integrity.UNENDORSED, Tlp.AMBER, DataClass.PII))
                  .lowering(joined -> joined.with(DATA_CLASS, DataClass.NONE)));

  // The whole account never leaves the store to answer one question about it.
  private final Query<Account, String> ownedBy =
      config.query(
          "Account.ownedBy",
          ACCOUNT_TYPE,
          String.class,
          (account, sender, ctx) -> account.email().equalsIgnoreCase(sender),
          d -> d.accepting(reading(Integrity.ENDORSED, Tlp.AMBER, DataClass.PII)));

  {
    config.seal(storage);
  }

  /** Every access in this system is made on behalf of a tenant, established at the edge. */
  private AccessContext acme() {
    edge.set(AccessContext.of("tenant", "acme"));
    return AccessContext.empty();
  }

  private AccessContext acme(String key, String value) {
    edge.set(AccessContext.of(java.util.Map.of("tenant", "acme", key, value)));
    return AccessContext.empty();
  }

  private AccessContext globex() {
    edge.set(AccessContext.of("tenant", "globex"));
    return AccessContext.empty();
  }

  /**
   * Holds a value at exactly the label given, by encoding it into the ambient context a door's
   * generic {@link #labelFrom} reads back out, then restoring whatever the edge held before.
   *
   * <p>This is the plumbing equivalent of the old {@code config.hold(value, type, label)}: the
   * label is still asserted by trusted code at a boundary, not computed, and still fixed before the
   * value is stored. What changed is the mechanism -- there is no method left that takes a label as
   * an argument, so the label has to travel through the one channel a door reads.
   */
  private <T> Surrogate<T> holdAs(
      String tenant,
      Integrity integrity,
      Tlp tlp,
      DataClass dataClass,
      Conceal<T> source,
      T value) {
    AccessContext previous = edge.get();
    edge.set(
        AccessContext.of(
            java.util.Map.of(
                "tenant", tenant,
                "integrity", integrity.name(),
                "tlp", tlp.name(),
                "dataClass", dataClass.name())));
    try {
      return source.conceal(value);
    } finally {
      edge.set(previous);
    }
  }

  // ---------------------------------------------------------------- the scenario

  private Surrogate<String> customerEmail() {
    return holdAs(
        "acme",
        Integrity.UNENDORSED,
        Tlp.AMBER,
        DataClass.PII,
        customerMail,
        "I was charged twice for invoice INV-4471. My SSN is 123-45-6789 if that helps.");
  }

  @Nested
  @DisplayName("the customer's email")
  class TheEmail {

    @Test
    @DisplayName("never reaches a vendor's model")
    void never_reaches_a_vendors_model() {
      Surrogate<String> email = customerEmail();

      acme();
      Revealed<String> attempt = vendorLlmText.reveal(email);

      assertThat(attempt.allowed()).isFalse();
      assertThat(attempt)
          .isInstanceOfSatisfying(
              Revealed.Denied.class,
              denied -> assertThat(denied.reason()).isEqualTo(Revealed.Reason.ABOVE_CEILING));
    }

    @Test
    @DisplayName("does reach the quarantined model, which is what it is for")
    void does_reach_the_quarantined_model() {
      Surrogate<String> email = customerEmail();

      acme();
      assertThat(quarantinedLlmText.reveal(email).granted())
          .hasValueSatisfying(text -> assertThat(text).contains("INV-4471"));
    }

    @Test
    @DisplayName("is still a handle everywhere else, and says nothing when printed")
    void is_still_a_handle_everywhere_else() {
      Surrogate<String> email = customerEmail();

      assertThat(email.toString()).doesNotContain("123-45-6789").contains("sur_");
    }
  }

  @Nested
  @DisplayName("the card token")
  class TheCardToken {

    private Surrogate<String> token() {
      return holdAs(
          "acme", Integrity.ENDORSED, Tlp.RED, DataClass.CARDHOLDER, cardTokens, "tok_1P9xyz");
    }

    @Test
    @DisplayName("reaches the payment processor")
    void reaches_the_payment_processor() {
      acme();
      assertThat(paymentProcessorText.reveal(token()).granted()).contains("tok_1P9xyz");
    }

    /** Not by policy anyone wrote. By arithmetic: every model sits below CARDHOLDER. */
    @Test
    @DisplayName("cannot reach any model, and cannot reach a person")
    void cannot_reach_any_model_or_person() {
      Surrogate<String> token = token();

      acme();
      assertThat(vendorLlmText.reveal(token).allowed()).isFalse();
      acme();
      assertThat(quarantinedLlmText.reveal(token).allowed()).isFalse();
      acme("clearance", "finance");
      assertThat(approvalCardText.reveal(token).allowed()).isFalse();
    }
  }

  @Nested
  @DisplayName("the approval card")
  class TheApprovalCard {

    private Surrogate<String> last4() {
      return holdAs("acme", Integrity.ENDORSED, Tlp.AMBER, DataClass.PII, last4Digits, "4821");
    }

    @Test
    @DisplayName("shows a finance approver the last four")
    void shows_a_finance_approver_the_last_four() {
      acme("clearance", "finance");
      assertThat(approvalCardText.reveal(last4()).granted()).contains("4821");
    }

    @Test
    @DisplayName("shows anyone else a handle")
    void shows_anyone_else_a_handle() {
      acme("clearance", "support");
      assertThat(approvalCardText.reveal(last4()).allowed()).isFalse();
    }

    @Test
    @DisplayName("and with nobody named at all, shows nothing")
    void with_nobody_named_shows_nothing() {
      acme();
      assertThat(approvalCardText.reveal(last4()).allowed()).isFalse();
    }
  }

  @Nested
  @DisplayName("tenants")
  class Tenants {

    /**
     * The claim the whole design rests on, produced the way an application would produce it.
     *
     * <p>Nobody labels anything as conflicted. An internal reporting job that is entitled to read
     * across tenants combines two customers' notes, because somebody wrote a perfectly reasonable
     * summariser and passed it perfectly reasonable inputs. The result is unusable everywhere, and
     * no rule had to be remembered for that to happen.
     *
     * <p>A tenant-scoped ceiling would have refused the combination earlier, which is the better
     * answer when it applies. This is the case where it does not: reading both was legitimate, and
     * the guarantee has to hold for what comes out.
     */
    @Test
    @DisplayName("folding two tenants' data makes a report that can go nowhere at all")
    void folding_two_tenants_data_makes_a_report_that_can_go_nowhere() {
      Surrogate<String> acmeNote =
          holdAs(
              "acme", Integrity.ENDORSED, Tlp.CLEAR, DataClass.NONE, notes, "acme disputes INV-1");
      Surrogate<String> globexNote =
          holdAs(
              "globex",
              Integrity.ENDORSED,
              Tlp.CLEAR,
              DataClass.NONE,
              notes,
              "globex disputes INV-2");

      acme();
      Surrogate<Report> report = summarise.fold(List.of(acmeNote, globexNote)).orThrow();

      assertThat(config.label(report).says(TENANT, "acme")).isFalse();
      acme();
      assertThat(vendorLlmReports.reveal(report).allowed()).isFalse();
      acme();
      assertThat(paymentProcessorReports.reveal(report).allowed()).isFalse();
      acme();
      assertThat(quarantinedLlmReports.reveal(report).allowed()).isFalse();
      AccessContext.of("tenant", "globex");
      assertThat(vendorLlmReports.reveal(report).allowed()).isFalse();
      // It exists, and it remembers where it came from.
      assertThat(config.lineage(report).parents()).containsExactly(acmeNote.id(), globexNote.id());
    }

    @Test
    @DisplayName("folding one tenant's own notes is perfectly usable")
    void folding_one_tenants_notes_is_usable() {
      Surrogate<String> first =
          holdAs("acme", Integrity.ENDORSED, Tlp.CLEAR, DataClass.NONE, notes, "first note");
      Surrogate<String> second =
          holdAs("acme", Integrity.ENDORSED, Tlp.CLEAR, DataClass.NONE, notes, "second note");

      acme();
      Surrogate<Report> report = summarise.fold(List.of(first, second)).orThrow();

      acme();
      assertThat(vendorLlmReports.reveal(report).granted())
          .contains(new Report("first note / second note"));
    }

    /** The fold takes the most constrained of everything it read, not the first thing it read. */
    @Test
    @DisplayName("one restricted parent constrains the whole result")
    void one_restricted_parent_constrains_the_whole_result() {
      Surrogate<String> ordinary =
          holdAs("acme", Integrity.ENDORSED, Tlp.CLEAR, DataClass.NONE, notes, "nothing special");
      Surrogate<String> personal =
          holdAs(
              "acme",
              Integrity.ENDORSED,
              Tlp.AMBER,
              DataClass.PII,
              notes,
              "and their home address");

      acme();
      Surrogate<Report> report = summarise.fold(List.of(ordinary, personal)).orThrow();

      assertThat(config.label(report).says(DATA_CLASS, DataClass.PII)).isTrue();
      acme();
      assertThat(vendorLlmReports.reveal(report).allowed()).isFalse();
      acme();
      assertThat(quarantinedLlmReports.reveal(report).allowed()).isTrue();
    }

    @Test
    @DisplayName("a fold with nothing to fold is refused")
    void a_fold_with_nothing_to_fold_is_refused() {
      acme();
      assertThat(summarise.fold(List.of()))
          .isInstanceOfSatisfying(
              Derived.Refused.class,
              refused -> assertThat(refused.reason()).isEqualTo(Derived.Reason.NO_PARENTS));
    }

    @Test
    @DisplayName("another tenant's data is refused, even though it is perfectly ordinary")
    void another_tenants_data_is_refused() {
      Surrogate<String> globexNote =
          holdAs(
              "globex",
              Integrity.ENDORSED,
              Tlp.CLEAR,
              DataClass.NONE,
              notes,
              "globex's entirely unremarkable note");

      acme();
      assertThat(vendorLlmText.reveal(globexNote).allowed()).isFalse();
    }

    @Test
    @DisplayName("one tenant's ordinary data is fine")
    void one_tenants_ordinary_data_is_fine() {
      Surrogate<String> held =
          holdAs("acme", Integrity.ENDORSED, Tlp.CLEAR, DataClass.NONE, notes, "nothing secret");

      acme();
      assertThat(vendorLlmText.reveal(held).allowed()).isTrue();
    }
  }

  @Nested
  @DisplayName("the gate itself")
  class TheGate {

    @Test
    @DisplayName("refuses an id nobody declared, rather than computing anything")
    void refuses_an_id_nobody_minted() {
      Surrogate<String> invented = Surrogate.of("sur_whatever-i-like");

      acme();
      assertThat(quarantinedLlmText.reveal(invented))
          .isInstanceOfSatisfying(
              Revealed.Denied.class,
              denied -> assertThat(denied.reason()).isEqualTo(Revealed.Reason.NO_SUCH_VALUE));
    }

    /**
     * This used to invent a destination name and assert the config refused it. There is no longer a
     * method that takes one: a door is reached by holding the sink, and outlets are declared during
     * configuration. What is worth asserting is that the door really is gone, because it is exactly
     * the sort of thing that gets added back for a test fixture and left there.
     */
    @Test
    @DisplayName("refuses a destination nobody registered")
    void refuses_a_destination_nobody_registered() {
      assertThat(Charter.class.getMethods())
          .isNotEmpty()
          .noneSatisfy(method -> assertThat(method.getReturnType()).isEqualTo(Revealed.class));
    }

    @Test
    @DisplayName("refuses a handle whose claimed type is not what was stored")
    void refuses_a_handle_whose_type_is_wrong() {
      Surrogate<DisputeClaim> claim =
          holdAs(
              "acme",
              Integrity.UNENDORSED,
              Tlp.AMBER,
              DataClass.PII,
              disputeClaims,
              new DisputeClaim("INV-1", "x"));
      Surrogate<String> lying = new Surrogate<>(claim.id());

      acme();
      assertThat(quarantinedLlmText.reveal(lying))
          .isInstanceOfSatisfying(
              Revealed.Denied.class,
              denied -> assertThat(denied.reason()).isEqualTo(Revealed.Reason.WRONG_TYPE));
    }

    @Test
    @DisplayName("tells you the label and the ceiling when it refuses, without leaking the value")
    void explains_a_refusal_without_leaking() {
      acme();
      Revealed<String> denied = vendorLlmText.reveal(customerEmail());

      String detail = ((Revealed.Denied<String>) denied).detail();
      assertThat(detail).contains("vendor-llm").doesNotContain("123-45-6789");
    }
  }

  @Nested
  @DisplayName("deriving")
  class Deriving {

    private Surrogate<DisputeClaim> claim() {
      return holdAs(
          "acme",
          Integrity.UNENDORSED,
          Tlp.AMBER,
          DataClass.PII,
          disputeClaims,
          new DisputeClaim("INV-4471", "charged twice"));
    }

    @Test
    @DisplayName("a projection inherits its parent's labels exactly")
    void a_projection_inherits_its_parents_labels() {
      acme();
      Surrogate<InvoiceNumber> number = claimedInvoice.derive(claim()).orThrow();

      assertThat(config.label(number))
          .isEqualTo(label("acme", Integrity.UNENDORSED, Tlp.AMBER, DataClass.PII));
      acme();
      assertThat(quarantinedLlmInvoice.reveal(number).granted())
          .contains(new InvoiceNumber("INV-4471"));
      acme();
      assertThat(vendorLlmInvoice.reveal(number).allowed()).isFalse();
    }

    /** An invoice number a customer typed is a question, not an answer. */
    @Test
    @DisplayName("extracting a field does not make it trustworthy")
    void extracting_a_field_does_not_make_it_trustworthy() {
      acme();
      Surrogate<InvoiceNumber> number = claimedInvoice.derive(claim()).orThrow();

      assertThat(config.label(number).says(INTEGRITY, Integrity.UNENDORSED)).isTrue();
    }

    @Test
    @DisplayName("records what it came from, so erasure has something to follow")
    void records_what_it_came_from() {
      Surrogate<DisputeClaim> parent = claim();

      acme();
      Surrogate<InvoiceNumber> number = claimedInvoice.derive(parent).orThrow();

      assertThat(config.lineage(number).parents()).containsExactly(parent.id());
      assertThat(config.lineage(number).derivation()).contains(CLAIMED_INVOICE);
      assertThat(config.lineage(parent).asserted()).isTrue();
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
      Surrogate<DisputeClaim> parent = claim();

      acme();
      Surrogate<InvoiceNumber> once = claimedInvoice.derive(parent).orThrow();
      acme();
      Surrogate<InvoiceNumber> twice = claimedInvoice.derive(parent).orThrow();

      assertThat(once.id()).isNotEqualTo(twice.id());
      assertThat(config.lineage(once).parents()).containsExactly(parent.id());
      assertThat(config.lineage(twice).parents()).containsExactly(parent.id());
    }

    @Test
    @DisplayName("two different parents give two different handles")
    void two_different_parents_give_two_different_handles() {
      acme();
      assertThat(claimedInvoice.derive(claim()).orThrow().id())
          .isNotEqualTo(claimedInvoice.derive(claim()).orThrow().id());
    }

    /**
     * This used to invent a name and assert the config refused it. The name no longer buys anything
     * -- there is no method that takes one -- so what is worth asserting is the property that
     * replaced it, and it is the stronger one: a derivation cannot be run unless somebody handed
     * you the portal, and a sealed charter constitutes no more of them.
     *
     * <p>Not a policy. There is no check to disable: the authority graph of a sealed charter cannot
     * grow, so there is nothing to hand out.
     */
    @Test
    @DisplayName("a derivation cannot be declared after the charter was sealed")
    void a_derivation_cannot_be_declared_after_sealing() {
      assertThatThrownBy(
              () ->
                  config.derivation(
                      "whatever-i-like",
                      DISPUTE_CLAIM_TYPE,
                      INVOICE_NUMBER_TYPE,
                      c -> new InvoiceNumber(c.invoiceNumber()),
                      d ->
                          d.accepting(
                              ctx ->
                                  Ceiling.of(TENANT, Constraint.any())
                                      .with(INTEGRITY, Constraint.any())
                                      .with(TLP, Constraint.any())
                                      .with(DATA_CLASS, Constraint.any()))))
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("has been sealed");
    }
  }

  @Nested
  @DisplayName("weakening a label")
  class Weakening {

    private Surrogate<String> token() {
      return holdAs(
          "acme", Integrity.ENDORSED, Tlp.RED, DataClass.CARDHOLDER, cardTokens, "tok_1P9xyz4821");
    }

    private AccessContext preparingApproval() {
      return acme("tool", "prepare_approval");
    }

    @Test
    @DisplayName("truncating a card lowers it to PII, which a person may then see")
    void truncating_a_card_lowers_it_to_pii() {
      preparingApproval();
      Surrogate<Last4> last4 = cardLast4.derive(token()).orThrow();

      assertThat(config.label(last4).says(DATA_CLASS, DataClass.PII)).isTrue();
      acme("clearance", "finance");
      assertThat(approvalCardLast4.reveal(last4).granted()).contains(new Last4("4821"));
    }

    @Test
    @DisplayName("and lowers nothing it did not name: still acme's, still endorsed")
    void lowers_nothing_it_did_not_name() {
      preparingApproval();
      Surrogate<Last4> last4 = cardLast4.derive(token()).orThrow();

      assertThat(config.label(last4).says(TENANT, "acme")).isTrue();
      assertThat(config.label(last4).says(INTEGRITY, Integrity.ENDORSED)).isTrue();
    }

    /**
     * Lowering one dimension is not "declassified", it is declassified in one respect. The four
     * digits stop being cardholder data and remain RED, and RED does not reach an approval card.
     */
    @Test
    @DisplayName("lowering only one dimension leaves the other still blocking")
    void lowering_only_one_dimension_leaves_the_other_blocking() {
      acme();
      Surrogate<Last4> partly = cardLast4Partial.derive(token()).orThrow();

      assertThat(config.label(partly).says(DATA_CLASS, DataClass.PII)).isTrue();
      assertThat(config.label(partly).says(TLP, Tlp.RED)).isTrue();
      acme("clearance", "finance");
      assertThat(approvalCardLast4.reveal(partly).allowed()).isFalse();
    }

    @Test
    @DisplayName("is offered only where it was declared to be")
    void is_offered_only_where_declared() {
      acme();
      assertThat(cardLast4.derive(token()))
          .isInstanceOfSatisfying(
              Derived.Refused.class,
              refused -> assertThat(refused.reason()).isEqualTo(Derived.Reason.NOT_AVAILABLE_HERE));
    }

    /** Declaring a relabel that raises is a mistake, not a policy. Ordinary derivation raises. */
    @Test
    @DisplayName("a relabel that does not actually lower is refused")
    void a_relabel_that_does_not_lower_is_refused() {
      Surrogate<DisputeClaim> endorsed =
          holdAs(
              "acme",
              Integrity.ENDORSED,
              Tlp.CLEAR,
              DataClass.NONE,
              disputeClaims,
              new DisputeClaim("INV-1", "x"));

      acme();
      assertThat(wishful.derive(endorsed))
          .isInstanceOfSatisfying(
              Derived.Refused.class,
              refused -> assertThat(refused.reason()).isEqualTo(Derived.Reason.NOT_A_LOWERING));
    }

    @Test
    @DisplayName("the manifest names every operation that can weaken a label")
    void the_manifest_names_every_weakening_operation() {
      Manifest manifest = config.manifest();

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
      assertThat(config.manifest().weakening())
          .extracting(Manifest.Entry::name)
          .contains(SUMMARISE_FOR_RELEASE)
          .doesNotContain(SUMMARISE);
    }

    /** A document meant to be diffed between reviews cannot reorder itself every restart. */
    @Test
    @DisplayName("and the report keeps the order everything was registered in")
    void the_report_keeps_registration_order() {
      Manifest manifest = config.manifest();

      assertThat(manifest.toString()).isEqualTo(config.manifest().toString());
      assertThat(manifest.destinations()).extracting(Manifest.Entry::name).startsWith("vendor-llm");
    }

    @Test
    @DisplayName("and is readable, which is the whole point of it")
    void and_is_readable() {
      String report = config.manifest().toString();

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

    private Surrogate<Account> account() {
      return holdAs(
          "acme",
          Integrity.ENDORSED,
          Tlp.AMBER,
          DataClass.PII,
          accounts,
          new Account("ACC-1", "someone@acme.example"));
    }

    @Test
    @DisplayName("answers the question without the account ever leaving")
    void answers_without_the_account_leaving() {
      Surrogate<Account> account = account();

      acme();
      assertThat(ownedBy.ask(account, "someone@acme.example").isTrue()).isTrue();
      acme();
      assertThat(ownedBy.ask(account, "attacker@elsewhere.example").isFalse()).isTrue();
    }

    /**
     * An answer that never ran is not a false one. Code that treats a refusal as "no" would read a
     * denial as an answer, which is how a gate becomes a leak.
     */
    @Test
    @DisplayName("a refusal is neither true nor false")
    void a_refusal_is_neither_true_nor_false() {
      // Acme's account, asked about by globex: refused by the ceiling rather than by a name
      // nobody registered, which is the only kind of refusal there is now.
      Surrogate<Account> acmeAccount = account();

      globex();
      Answer answer = ownedBy.ask(acmeAccount, "x");

      assertThat(answer.isTrue()).isFalse();
      assertThat(answer.isFalse()).isFalse();
      assertThat(answer.ran()).isFalse();
    }

    @Test
    @DisplayName("refuses to look at a value it was never meant to see")
    void refuses_to_look_at_a_value_it_was_never_meant_to_see() {
      DefaultCharter choosyConfig = new DefaultCharter(TENANT, INTEGRITY, TLP, DATA_CLASS);
      choosyConfig.currentAccess(edge::get);
      Conceal<Account> secretAccounts =
          choosyConfig.source(
              "secret-accounts",
              ACCOUNT_TYPE,
              label("acme", Integrity.ENDORSED, Tlp.RED, DataClass.CARDHOLDER));
      Query<Account, String> secretOwnedBy =
          choosyConfig.query(
              "Account.ownedBy",
              ACCOUNT_TYPE,
              String.class,
              (account, sender, ctx) -> true,
              d -> d.accepting(reading(Integrity.ENDORSED, Tlp.CLEAR, DataClass.NONE)));
      choosyConfig.seal(new MemoryStorage());
      Surrogate<Account> secret = secretAccounts.conceal(new Account("ACC-2", "x@y.example"));

      acme();
      assertThat(secretOwnedBy.ask(secret, "x@y.example"))
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
      acme();
      Revealed<String> allowed = quarantinedLlmText.reveal(customerEmail());

      assertThat(allowed.allowed()).isTrue();
      assertThat(allowed.toString()).doesNotContain("123-45-6789");
    }

    @Test
    @DisplayName("a refusal names the destination but not the labels")
    void a_refusal_names_the_destination_but_not_the_labels() {
      acme();
      Revealed<String> denied = vendorLlmText.reveal(customerEmail());

      String detail = ((Revealed.Denied<String>) denied).detail();
      assertThat(detail).contains("vendor-llm").doesNotContain("acme").doesNotContain("PII");
    }

    /**
     * The other half of the rule, and the reason the caller can be told so little.
     *
     * <p>A refusal is recorded in full and explained to nobody. If the message said what the value
     * was labelled, code that may not read it could still learn its classification by asking often
     * enough -- so the label and the ceiling go to the record, where they are protected like any
     * other label.
     */
    @Test
    @DisplayName("and the explanation the caller never sees is in the record")
    void the_explanation_the_caller_never_sees_is_in_the_record() {
      Surrogate<String> email = customerEmail();
      storage.clearAudit();
      acme();

      vendorLlmText.reveal(email);

      assertThat(storage.audit()).isNotEmpty();
      assertThat(storage.audit())
          .anySatisfy(
              record -> {
                assertThat(record.outcome()).isEqualTo(AuditRecord.Outcome.REFUSED);
                // The code stays in the clear, so the trail can be queried on it.
                assertThat(record.reason()).contains("ABOVE_CEILING");
                // The part that names a label does not, and is protected like a label.
                assertThat(record.detail())
                    .hasValueSatisfying(why -> assertThat(why).contains("PII"));
              });
    }

    /** A policy that cannot be evaluated has not said yes. */
    @Test
    @DisplayName("a destination whose ceiling throws denies, rather than exploding")
    void a_destination_whose_ceiling_throws_denies() {
      DefaultCharter fragileConfig = new DefaultCharter(TENANT, INTEGRITY, TLP, DATA_CLASS);
      fragileConfig.currentAccess(edge::get);
      Conceal<String> fragileMail =
          fragileConfig.source("mail", STRING_TYPE, BillingScenarioTest::labelFrom);
      Reveal<String> broken =
          fragileConfig
              .destination(
                  "broken",
                  ctx -> {
                    throw new IllegalStateException("policy service is down");
                  },
                  STRING_TYPE)
              .reading(STRING_TYPE);
      fragileConfig.seal(new MemoryStorage());
      Surrogate<String> held =
          holdAs("acme", Integrity.ENDORSED, Tlp.CLEAR, DataClass.NONE, fragileMail, "x");

      acme();
      Revealed<String> result = broken.reveal(held);

      assertThat(result.allowed()).isFalse();
    }
  }

  @Nested
  @DisplayName("the record")
  class TheRecord {

    @Test
    @DisplayName("says who reached what, and never what the value was")
    void says_who_reached_what_and_never_the_value() {
      acme();
      quarantinedLlmText.reveal(customerEmail());

      AuditRecord entry = storage.audit(AuditRecord.Operation.DEREFERENCE).getLast();
      assertThat(entry.outcome()).isEqualTo(AuditRecord.Outcome.ALLOWED);
      assertThat(entry.target()).contains("quarantined-llm");
      assertThat(entry.context()).containsEntry("tenant", "acme");
      assertThat(entry.toString()).doesNotContain("123-45-6789");
    }

    /** A thousand refusals against one value is the interesting event. */
    @Test
    @DisplayName("records refusals as carefully as permissions")
    void records_refusals_as_carefully_as_permissions() {
      acme();
      vendorLlmText.reveal(customerEmail());

      AuditRecord entry = storage.audit(AuditRecord.Operation.DEREFERENCE).getLast();
      assertThat(entry.outcome()).isEqualTo(AuditRecord.Outcome.REFUSED);
      assertThat(entry.reason())
          .hasValueSatisfying(why -> assertThat(why).startsWith("ABOVE_CEILING"));
    }

    @Test
    @DisplayName("records holding, because that is where labels are asserted rather than computed")
    void records_holding() {
      Surrogate<String> email = customerEmail();

      assertThat(storage.audit(AuditRecord.Operation.HOLD))
          .anySatisfy(entry -> assertThat(entry.value()).isEqualTo(email.id()));
    }

    @Test
    @DisplayName("records a check, with the answer but never the question")
    void records_a_check_with_the_answer_but_not_the_question() {
      Surrogate<Account> account =
          holdAs(
              "acme",
              Integrity.ENDORSED,
              Tlp.AMBER,
              DataClass.PII,
              accounts,
              new Account("ACC-1", "someone@acme.example"));

      acme();
      ownedBy.ask(account, "someone@acme.example");

      AuditRecord entry = storage.audit(AuditRecord.Operation.ASK).getLast();
      assertThat(entry.reason()).contains("answered true");
      assertThat(entry.toString()).doesNotContain("someone@acme.example");
    }

    /** The event an auditor most wants to find. */
    @Test
    @DisplayName("says so when a derivation weakened a label")
    void says_so_when_a_derivation_weakened_a_label() {
      Surrogate<String> token =
          holdAs(
              "acme",
              Integrity.ENDORSED,
              Tlp.RED,
              DataClass.CARDHOLDER,
              cardTokens,
              "tok_1P9xyz4821");

      acme("tool", "prepare_approval");
      cardLast4.derive(token);

      AuditRecord entry = storage.audit(AuditRecord.Operation.DERIVE).getLast();
      assertThat(entry.reason()).hasValueSatisfying(r -> assertThat(r).startsWith("weakened from"));
      assertThat(entry.target()).contains("Card.last4");
    }

    @Test
    @DisplayName("an ordinary derivation is recorded without that note")
    void an_ordinary_derivation_is_recorded_without_that_note() {
      Surrogate<DisputeClaim> claim =
          holdAs(
              "acme",
              Integrity.UNENDORSED,
              Tlp.AMBER,
              DataClass.PII,
              disputeClaims,
              new DisputeClaim("INV-4471", "charged twice"));

      acme();
      claimedInvoice.derive(claim);

      assertThat(storage.audit(AuditRecord.Operation.DERIVE).getLast().reason()).isEmpty();
    }

    @Test
    @DisplayName("but one made from several values says so, since it is more constrained than any")
    void one_made_from_several_says_so() {
      Surrogate<String> first =
          holdAs("acme", Integrity.ENDORSED, Tlp.CLEAR, DataClass.NONE, notes, "a");
      Surrogate<String> second =
          holdAs("acme", Integrity.ENDORSED, Tlp.CLEAR, DataClass.NONE, notes, "b");

      acme();
      summarise.fold(java.util.List.of(first, second));

      assertThat(storage.audit(AuditRecord.Operation.DERIVE).getLast().reason())
          .contains("combined from 2 values");
    }

    /**
     * A control whose record is quietly failing still produces the report.
     *
     * <p>Nothing is left behind either. The value and the line saying it arrived are written as one
     * act, so a config that cannot record leaves no value. The other outcome -- a secret committed
     * durably under an id nobody received, that nothing can reach, read or erase -- is the one this
     * is here to prevent.
     */
    @Test
    @DisplayName("an access that cannot be recorded does not happen, and stores nothing")
    void an_access_that_cannot_be_recorded_does_not_happen() {
      DefaultCharter watchedConfig = new DefaultCharter(TENANT, INTEGRITY, TLP, DATA_CLASS);
      watchedConfig.currentAccess(edge::get);
      Conceal<String> watchedMail =
          watchedConfig.source("mail", STRING_TYPE, BillingScenarioTest::labelFrom);
      MemoryStorage kept = new MemoryStorage();
      Storage broken =
          new Storage() {
            @Override
            public void put(String id, StoredValue value, AuditRecord record) {
              throw new IllegalStateException("the record could not be written");
            }

            @Override
            public void record(AuditRecord record) {
              kept.record(record);
            }

            @Override
            public java.util.Optional<StoredMetadata> metadata(String id) {
              return kept.metadata(id);
            }

            @Override
            public <T> java.util.Optional<T> value(String id, org.jwcarman.codec.spi.TypeRef<T> t) {
              return kept.value(id, t);
            }

            @Override
            public boolean contains(String id) {
              return kept.contains(id);
            }

            @Override
            public int erase(String root) {
              return kept.erase(root);
            }
          };
      watchedConfig.seal(broken);

      assertThatThrownBy(
              () ->
                  holdAs(
                      "acme",
                      Integrity.ENDORSED,
                      Tlp.CLEAR,
                      DataClass.NONE,
                      watchedMail,
                      "anything"))
          .isInstanceOf(IllegalStateException.class);

      assertThat(watchedConfig.holds("anything")).isFalse();
      assertThat(kept.everything()).isEmpty();
    }

    /**
     * There is no longer anything to say.
     *
     * <p>Keeping no record used to be a thing you could configure, and this asserted that you had
     * to say so out loud rather than omit an auditor. Both are gone: the record is written by the
     * config, so there is no switch to leave off and nothing to declare.
     */
    @Test
    @DisplayName("keeping no record is not something you can ask for")
    void keeping_no_record_is_not_something_you_can_ask_for() {
      assertThat(Charter.class.getMethods())
          .isNotEmpty()
          .noneSatisfy(method -> assertThat(method.getName()).contains("udit"));
    }
  }

  @Nested
  @DisplayName("refusals reach the record too")
  class RefusalsAreRecorded {

    private Surrogate<DisputeClaim> claim() {
      return holdAs(
          "acme",
          Integrity.UNENDORSED,
          Tlp.AMBER,
          DataClass.PII,
          disputeClaims,
          new DisputeClaim("INV-4471", "charged twice"));
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
      storage.clearAudit();

      acme();
      declines.derive(claim());

      assertThat(storage.audit(AuditRecord.Operation.DERIVE))
          .isNotEmpty()
          .anySatisfy(
              entry -> {
                assertThat(entry.outcome()).isEqualTo(AuditRecord.Outcome.REFUSED);
                assertThat(entry.reason())
                    .hasValueSatisfying(why -> assertThat(why).startsWith("DECLINED"));
                assertThat(entry.target()).contains(DECLINES);
              });
    }

    /** Refused before the value was looked at, so the record honestly has no label to give. */
    @Test
    @DisplayName("a derivation not offered here is recorded, and says nothing about the value")
    void a_derivation_not_offered_here_is_recorded() {
      Surrogate<String> token =
          holdAs(
              "acme",
              Integrity.ENDORSED,
              Tlp.RED,
              DataClass.CARDHOLDER,
              cardTokens,
              "tok_1P9xyz4821");
      storage.clearAudit();

      acme();
      cardLast4.derive(token);

      AuditRecord entry = storage.audit(AuditRecord.Operation.DERIVE).getLast();
      assertThat(entry.outcome()).isEqualTo(AuditRecord.Outcome.REFUSED);
      assertThat(entry.reason())
          .hasValueSatisfying(why -> assertThat(why).startsWith("NOT_AVAILABLE_HERE"));
      assertThat(entry.label()).isEmpty();
    }

    /** But a refusal that happened after the value was read records what was read. */
    @Test
    @DisplayName("a refusal that came after reading the value records its label")
    void a_refusal_after_reading_records_its_label() {
      storage.clearAudit();

      acme();
      declines.derive(claim());

      assertThat(storage.audit(AuditRecord.Operation.DERIVE).getLast().label())
          .hasValueSatisfying(label -> assertThat(label).contains("UNENDORSED"));
    }

    /**
     * A refusal reaches the record too, because a query reads plaintext in order to answer and a
     * turned-away read is exactly what an auditor is looking for.
     */
    @Test
    @DisplayName("a refused check is recorded")
    void a_refused_check_is_recorded() {
      storage.clearAudit();

      Surrogate<Account> acmeAccount =
          holdAs(
              "acme",
              Integrity.ENDORSED,
              Tlp.AMBER,
              DataClass.PII,
              accounts,
              new Account("ACC-1", "someone@acme.example"));
      storage.clearAudit();
      globex();
      ownedBy.ask(acmeAccount, "x");

      assertThat(storage.audit(AuditRecord.Operation.ASK))
          .anySatisfy(
              entry -> {
                assertThat(entry.outcome()).isEqualTo(AuditRecord.Outcome.REFUSED);
                assertThat(entry.reason())
                    .hasValueSatisfying(why -> assertThat(why).startsWith("ABOVE_CEILING"));
              });
    }

    @Test
    @DisplayName("a fold refused at the gate is recorded")
    void a_fold_refused_at_the_gate_is_recorded() {
      storage.clearAudit();

      acme();
      summarise.fold(java.util.List.of());

      assertThat(storage.audit(AuditRecord.Operation.DERIVE))
          .anySatisfy(
              entry -> {
                assertThat(entry.outcome()).isEqualTo(AuditRecord.Outcome.REFUSED);
                assertThat(entry.reason())
                    .hasValueSatisfying(why -> assertThat(why).startsWith("NO_PARENTS"));
              });
    }

    @Test
    @DisplayName("and a refusal never says what the value was")
    void a_refusal_never_says_what_the_value_was() {
      storage.clearAudit();

      acme();
      declines.derive(claim());

      assertThat(storage.audit(AuditRecord.Operation.DERIVE).getLast().toString())
          .doesNotContain("charged twice")
          .doesNotContain("INV-4471");
    }
  }
}
