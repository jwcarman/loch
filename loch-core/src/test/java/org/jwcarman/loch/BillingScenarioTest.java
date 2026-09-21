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
 *
 * <p><b>The domain bound is {@code Object}</b>, not a marker interface. Every stored type here
 * (plaintext strings, {@link DisputeClaim}, {@link Account}, ...) already is what it is; a marker
 * interface would exist only to satisfy the compiler; and being able to hold a bare {@code String}
 * keeps most of this scenario's assertions exactly as an application would write them.
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

  // ---------------------------------------------------------------- what travels through the loch

  record DisputeClaim(String invoiceNumber, String reason) {}

  record InvoiceNumber(String value) {}

  record Last4(String digits) {}

  record Account(String number, String email) {}

  record Report(String text) {}

  /** What an operation reading plaintext may look at: always the acting tenant's own data. */
  private static java.util.function.Function<AccessContext, Billing> reading(
      Integrity integrity, Tlp tlp, DataClass dataClass) {
    return ctx -> Billing.ceilingFor(ctx, integrity, tlp, dataClass);
  }

  /** The approval card's ceiling: a finance approver sees more than anyone else does. */
  private static java.util.function.Function<AccessContext, Billing> approvalCardCeiling() {
    return ctx ->
        Billing.ceilingFor(
            ctx,
            Integrity.ENDORSED,
            Tlp.AMBER,
            ctx.has("clearance", "finance") ? DataClass.PII : DataClass.NONE);
  }

  /**
   * The generic labelling function every inlet below is minted with: it reads the whole label, axis
   * by axis, out of whatever context {@link #holdAs} put there. An inlet is still the only door a
   * value can enter through, and its name is still a fixed property of the door -- but the four
   * label axes themselves come from the access, the same way a tenant always did.
   */
  private static Billing labelFrom(AccessContext ctx) {
    return Billing.of(
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

  private final LochConfig<Billing, Object> config =
      new LochConfig<Billing, Object>()
          .lattice(Billing.LATTICE)
          .auditor(audit)
          .askingWhoIsAsking(edge::get);

  // ---------------------------------------------------------------- doors in

  private final Inlet<String> customerMail =
      config.inlet("customer-mail", String.class, BillingScenarioTest::labelFrom);

  private final Inlet<String> cardTokens =
      config.inlet("card-tokens", String.class, BillingScenarioTest::labelFrom);

  private final Inlet<String> notes =
      config.inlet("notes", String.class, BillingScenarioTest::labelFrom);

  private final Inlet<String> last4Digits =
      config.inlet("last4-digits", String.class, BillingScenarioTest::labelFrom);

  private final Inlet<DisputeClaim> disputeClaims =
      config.inlet("dispute-claims", DisputeClaim.class, BillingScenarioTest::labelFrom);

  private final Inlet<Account> accounts =
      config.inlet("accounts", Account.class, BillingScenarioTest::labelFrom);

  // ---------------------------------------------------------------- doors out: one per (door,
  // type) pair. An outlet is narrowed by type as well as by label, so a door that used to admit
  // whatever handle a caller presented is now one capability per shape of value it actually reads.
  // Holding everything text-shaped as a plain String (rather than one wrapper record per door)
  // keeps this down to one text outlet per destination instead of three.

  // A vendor's model: nothing personal, nothing unendorsed.
  private final Outlet<String> vendorLlmText =
      config.outlet(
          "vendor-llm",
          String.class,
          ctx -> Billing.ceilingFor(ctx, Integrity.ENDORSED, Tlp.CLEAR, DataClass.NONE));

  private final Outlet<Report> vendorLlmReports =
      config.outlet(
          "vendor-llm-reports",
          Report.class,
          ctx -> Billing.ceilingFor(ctx, Integrity.ENDORSED, Tlp.CLEAR, DataClass.NONE));

  private final Outlet<InvoiceNumber> vendorLlmInvoice =
      config.outlet(
          "vendor-llm-invoice",
          InvoiceNumber.class,
          ctx -> Billing.ceilingFor(ctx, Integrity.ENDORSED, Tlp.CLEAR, DataClass.NONE));

  // Ours, on our own hardware. Reads untrusted mail; holds no secrets.
  private final Outlet<String> quarantinedLlmText =
      config.outlet(
          "quarantined-llm",
          String.class,
          ctx -> Billing.ceilingFor(ctx, Integrity.UNENDORSED, Tlp.AMBER, DataClass.PII));

  private final Outlet<Report> quarantinedLlmReports =
      config.outlet(
          "quarantined-llm-reports",
          Report.class,
          ctx -> Billing.ceilingFor(ctx, Integrity.UNENDORSED, Tlp.AMBER, DataClass.PII));

  private final Outlet<InvoiceNumber> quarantinedLlmInvoice =
      config.outlet(
          "quarantined-llm-invoice",
          InvoiceNumber.class,
          ctx -> Billing.ceilingFor(ctx, Integrity.UNENDORSED, Tlp.AMBER, DataClass.PII));

  // The only place cardholder data may go, anywhere in the system.
  private final Outlet<String> paymentProcessorText =
      config.outlet(
          "payment-processor",
          String.class,
          ctx -> Billing.ceilingFor(ctx, Integrity.ENDORSED, Tlp.RED, DataClass.CARDHOLDER));

  private final Outlet<Report> paymentProcessorReports =
      config.outlet(
          "payment-processor-reports",
          Report.class,
          ctx -> Billing.ceilingFor(ctx, Integrity.ENDORSED, Tlp.RED, DataClass.CARDHOLDER));

  // A person. What they may see depends on who they are.
  private final Outlet<String> approvalCardText =
      config.outlet("approval-card", String.class, approvalCardCeiling());

  private final Outlet<Last4> approvalCardLast4 =
      config.outlet("approval-card-last4", Last4.class, approvalCardCeiling());

  // ---------------------------------------------------------------- derivations and folds

  // A projection. Cannot weaken anything, so it needs no ceremony.
  private final Derivation<DisputeClaim, InvoiceNumber> claimedInvoice =
      config
          .derivation(
              CLAIMED_INVOICE,
              DisputeClaim.class,
              InvoiceNumber.class,
              claim -> new InvoiceNumber(claim.invoiceNumber()))
          .accepting(reading(Integrity.UNENDORSED, Tlp.AMBER, DataClass.PII))
          .mint();

  // Truncating a card IS a declassification, and PCI auditors ask about it.
  private final Derivation<String, Last4> cardLast4 =
      config
          .derivation(
              CARD_LAST4,
              String.class,
              Last4.class,
              token -> new Last4(token.substring(token.length() - 4)))
          .accepting(
              ctx -> Billing.ceilingFor(ctx, Integrity.ENDORSED, Tlp.RED, DataClass.CARDHOLDER))
          // Both dimensions, deliberately: four digits are neither cardholder
          // data nor RED any more, and saying so is the reviewed act.
          .lowering(joined -> joined.withDataClass(DataClass.PII).withTlp(Tlp.AMBER))
          .availableTo(ctx -> ctx.has("tool", "prepare_approval"))
          .mint();

  // The same truncation, lowering only one dimension. Still cannot be shown.
  private final Derivation<String, Last4> cardLast4Partial =
      config
          .derivation(
              CARD_LAST4_PARTIAL,
              String.class,
              Last4.class,
              token -> new Last4(token.substring(token.length() - 4)))
          .accepting(reading(Integrity.ENDORSED, Tlp.RED, DataClass.CARDHOLDER))
          .lowering(joined -> joined.withDataClass(DataClass.PII))
          .mint();

  // Declares itself an endorsement without checking anything. Loch refuses it.
  private final Derivation<DisputeClaim, InvoiceNumber> wishful =
      config
          .derivation(
              WISHFUL,
              DisputeClaim.class,
              InvoiceNumber.class,
              claim -> new InvoiceNumber(claim.invoiceNumber()))
          .accepting(reading(Integrity.ENDORSED, Tlp.CLEAR, DataClass.NONE))
          .lowering(joined -> joined.withIntegrity(Integrity.UNENDORSED))
          .mint();

  // Several values in, one out. Every parent's label lands on the result.
  private final Fold<String, Report> summarise =
      config
          .fold(
              SUMMARISE, String.class, Report.class, notes -> new Report(String.join(" / ", notes)))
          // An internal reporting job, entitled to read across tenants. The point
          // of the test below is what happens to what it produces, not whether it
          // may read: a ceiling would refuse the combination earlier, and then
          // there would be nothing to demonstrate.
          .acceptingAnything()
          .mint();

  // Reads the value, then says no. The refusal has to be recorded because the
  // function already saw the plaintext.
  private final Derivation<DisputeClaim, InvoiceNumber> declines =
      config
          .checking(
              DECLINES,
              DisputeClaim.class,
              InvoiceNumber.class,
              (claim, ctx) -> java.util.Optional.empty())
          .accepting(reading(Integrity.UNENDORSED, Tlp.AMBER, DataClass.PII))
          .mint();

  // A fold that lowers is as privileged as a derivation that lowers.
  private final Fold<String, Report> summariseForRelease =
      config
          .fold(
              SUMMARISE_FOR_RELEASE,
              String.class,
              Report.class,
              notes -> new Report("redacted summary of " + notes.size()))
          .accepting(reading(Integrity.UNENDORSED, Tlp.AMBER, DataClass.PII))
          .lowering(joined -> joined.withDataClass(DataClass.NONE))
          .mint();

  // The whole account never leaves the loch to answer one question about it.
  private final Query<Account, String> ownedBy =
      config
          .query(
              "Account.ownedBy",
              Account.class,
              String.class,
              (account, sender, ctx) -> account.email().equalsIgnoreCase(sender))
          .accepting(reading(Integrity.ENDORSED, Tlp.AMBER, DataClass.PII))
          .mint();

  private final Loch<Billing> loch = MemoryLoch.create(config);

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
   * Holds a value at exactly the label given, by encoding it into the ambient context an inlet's
   * generic {@link #labelFrom} reads back out, then restoring whatever the edge held before.
   *
   * <p>This is the plumbing equivalent of the old {@code loch.hold(value, type, label)}: the label
   * is still asserted by trusted code at a boundary, not computed, and still fixed before the value
   * is stored. What changed is the mechanism -- there is no method left that takes a label as an
   * argument, so the label has to travel through the one channel an inlet reads.
   */
  private <T> Handle<T> holdAs(Billing label, Inlet<T> inlet, T value) {
    AccessContext previous = edge.get();
    edge.set(
        AccessContext.of(
            java.util.Map.of(
                "tenant", label.tenant().resolved().orElse(""),
                "integrity", label.integrity().name(),
                "tlp", label.tlp().name(),
                "dataClass", label.dataClass().name())));
    try {
      return inlet.hold(value);
    } finally {
      edge.set(previous);
    }
  }

  // ---------------------------------------------------------------- the scenario

  private Handle<String> customerEmail() {
    return holdAs(
        Billing.of("acme", Integrity.UNENDORSED, Tlp.AMBER, DataClass.PII),
        customerMail,
        "I was charged twice for invoice INV-4471. My SSN is 123-45-6789 if that helps.");
  }

  @Nested
  @DisplayName("the customer's email")
  class TheEmail {

    @Test
    @DisplayName("never reaches a vendor's model")
    void never_reaches_a_vendors_model() {
      Handle<String> email = customerEmail();

      Dereferenced<String> attempt = vendorLlmText.read(email, acme());

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

      assertThat(quarantinedLlmText.read(email, acme()).granted())
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
      return holdAs(
          Billing.of("acme", Integrity.ENDORSED, Tlp.RED, DataClass.CARDHOLDER),
          cardTokens,
          "tok_1P9xyz");
    }

    @Test
    @DisplayName("reaches the payment processor")
    void reaches_the_payment_processor() {
      assertThat(paymentProcessorText.read(token(), acme()).granted()).contains("tok_1P9xyz");
    }

    /** Not by policy anyone wrote. By arithmetic: every model sits below CARDHOLDER. */
    @Test
    @DisplayName("cannot reach any model, and cannot reach a person")
    void cannot_reach_any_model_or_person() {
      Handle<String> token = token();

      assertThat(vendorLlmText.read(token, acme()).allowed()).isFalse();
      assertThat(quarantinedLlmText.read(token, acme()).allowed()).isFalse();
      assertThat(approvalCardText.read(token, acme("clearance", "finance")).allowed()).isFalse();
    }
  }

  @Nested
  @DisplayName("the approval card")
  class TheApprovalCard {

    private Handle<String> last4() {
      return holdAs(
          Billing.of("acme", Integrity.ENDORSED, Tlp.AMBER, DataClass.PII), last4Digits, "4821");
    }

    @Test
    @DisplayName("shows a finance approver the last four")
    void shows_a_finance_approver_the_last_four() {
      assertThat(approvalCardText.read(last4(), acme("clearance", "finance")).granted())
          .contains("4821");
    }

    @Test
    @DisplayName("shows anyone else a handle")
    void shows_anyone_else_a_handle() {
      assertThat(approvalCardText.read(last4(), acme("clearance", "support")).allowed()).isFalse();
    }

    @Test
    @DisplayName("and with nobody named at all, shows nothing")
    void with_nobody_named_shows_nothing() {
      assertThat(approvalCardText.read(last4(), acme()).allowed()).isFalse();
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
      Handle<String> acmeNote =
          holdAs(
              Billing.of("acme", Integrity.ENDORSED, Tlp.CLEAR, DataClass.NONE),
              notes,
              "acme disputes INV-1");
      Handle<String> globexNote =
          holdAs(
              Billing.of("globex", Integrity.ENDORSED, Tlp.CLEAR, DataClass.NONE),
              notes,
              "globex disputes INV-2");

      Handle<Report> report = summarise.fold(List.of(acmeNote, globexNote), acme()).orThrow();

      assertThat(loch.label(report).tenant().conflicted()).isTrue();
      assertThat(vendorLlmReports.read(report, acme()).allowed()).isFalse();
      assertThat(paymentProcessorReports.read(report, acme()).allowed()).isFalse();
      assertThat(quarantinedLlmReports.read(report, acme()).allowed()).isFalse();
      assertThat(vendorLlmReports.read(report, AccessContext.of("tenant", "globex")).allowed())
          .isFalse();
      // It exists, and it remembers where it came from.
      assertThat(loch.lineage(report).parents()).containsExactly(acmeNote.id(), globexNote.id());
    }

    @Test
    @DisplayName("folding one tenant's own notes is perfectly usable")
    void folding_one_tenants_notes_is_usable() {
      Handle<String> first =
          holdAs(
              Billing.of("acme", Integrity.ENDORSED, Tlp.CLEAR, DataClass.NONE),
              notes,
              "first note");
      Handle<String> second =
          holdAs(
              Billing.of("acme", Integrity.ENDORSED, Tlp.CLEAR, DataClass.NONE),
              notes,
              "second note");

      Handle<Report> report = summarise.fold(List.of(first, second), acme()).orThrow();

      assertThat(vendorLlmReports.read(report, acme()).granted())
          .contains(new Report("first note / second note"));
    }

    /** The fold takes the most constrained of everything it read, not the first thing it read. */
    @Test
    @DisplayName("one restricted parent constrains the whole result")
    void one_restricted_parent_constrains_the_whole_result() {
      Handle<String> ordinary =
          holdAs(
              Billing.of("acme", Integrity.ENDORSED, Tlp.CLEAR, DataClass.NONE),
              notes,
              "nothing special");
      Handle<String> personal =
          holdAs(
              Billing.of("acme", Integrity.ENDORSED, Tlp.AMBER, DataClass.PII),
              notes,
              "and their home address");

      Handle<Report> report = summarise.fold(List.of(ordinary, personal), acme()).orThrow();

      assertThat(loch.label(report).dataClass()).isEqualTo(DataClass.PII);
      assertThat(vendorLlmReports.read(report, acme()).allowed()).isFalse();
      assertThat(quarantinedLlmReports.read(report, acme()).allowed()).isTrue();
    }

    @Test
    @DisplayName("a fold with nothing to fold is refused")
    void a_fold_with_nothing_to_fold_is_refused() {
      assertThat(summarise.fold(List.of(), acme()))
          .isInstanceOfSatisfying(
              Derived.Refused.class,
              refused -> assertThat(refused.reason()).isEqualTo(Derived.Reason.NO_PARENTS));
    }

    @Test
    @DisplayName("another tenant's data is refused, even though it is perfectly ordinary")
    void another_tenants_data_is_refused() {
      Handle<String> globexNote =
          holdAs(
              Billing.of("globex", Integrity.ENDORSED, Tlp.CLEAR, DataClass.NONE),
              notes,
              "globex's entirely unremarkable note");

      assertThat(vendorLlmText.read(globexNote, acme()).allowed()).isFalse();
    }

    @Test
    @DisplayName("one tenant's ordinary data is fine")
    void one_tenants_ordinary_data_is_fine() {
      Handle<String> held =
          holdAs(
              Billing.of("acme", Integrity.ENDORSED, Tlp.CLEAR, DataClass.NONE),
              notes,
              "nothing secret");

      assertThat(vendorLlmText.read(held, acme()).allowed()).isTrue();
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

      assertThat(quarantinedLlmText.read(invented, acme()))
          .isInstanceOfSatisfying(
              Dereferenced.Denied.class,
              denied -> assertThat(denied.reason()).isEqualTo(Dereferenced.Reason.NO_SUCH_VALUE));
    }

    /**
     * This used to invent a destination name and assert the loch refused it. There is no longer a
     * method that takes one: a door is reached by holding the outlet, and outlets are minted during
     * configuration. What is worth asserting is that the door really is gone, because it is exactly
     * the sort of thing that gets added back for a test fixture and left there.
     */
    @Test
    @DisplayName("refuses a destination nobody registered")
    void refuses_a_destination_nobody_registered() {
      assertThat(Loch.class.getMethods())
          .isNotEmpty()
          .noneSatisfy(method -> assertThat(method.getReturnType()).isEqualTo(Dereferenced.class));
    }

    @Test
    @DisplayName("refuses a handle whose claimed type is not what was stored")
    void refuses_a_handle_whose_type_is_wrong() {
      Handle<DisputeClaim> claim =
          holdAs(
              Billing.of("acme", Integrity.UNENDORSED, Tlp.AMBER, DataClass.PII),
              disputeClaims,
              new DisputeClaim("INV-1", "x"));
      Handle<String> lying = new Handle<>(claim.id(), TypeRef.of(String.class));

      assertThat(quarantinedLlmText.read(lying, acme()))
          .isInstanceOfSatisfying(
              Dereferenced.Denied.class,
              denied -> assertThat(denied.reason()).isEqualTo(Dereferenced.Reason.WRONG_TYPE));
    }

    @Test
    @DisplayName("tells you the label and the ceiling when it refuses, without leaking the value")
    void explains_a_refusal_without_leaking() {
      Dereferenced<String> denied = vendorLlmText.read(customerEmail(), acme());

      String detail = ((Dereferenced.Denied<String>) denied).detail();
      assertThat(detail).contains("vendor-llm").doesNotContain("123-45-6789");
    }
  }

  @Nested
  @DisplayName("deriving")
  class Deriving {

    private Handle<DisputeClaim> claim() {
      return holdAs(
          Billing.of("acme", Integrity.UNENDORSED, Tlp.AMBER, DataClass.PII),
          disputeClaims,
          new DisputeClaim("INV-4471", "charged twice"));
    }

    @Test
    @DisplayName("a projection inherits its parent's labels exactly")
    void a_projection_inherits_its_parents_labels() {
      Handle<InvoiceNumber> number = claimedInvoice.derive(claim(), acme()).orThrow();

      assertThat(loch.label(number))
          .isEqualTo(Billing.of("acme", Integrity.UNENDORSED, Tlp.AMBER, DataClass.PII));
      assertThat(quarantinedLlmInvoice.read(number, acme()).granted())
          .contains(new InvoiceNumber("INV-4471"));
      assertThat(vendorLlmInvoice.read(number, acme()).allowed()).isFalse();
    }

    /** An invoice number a customer typed is a question, not an answer. */
    @Test
    @DisplayName("extracting a field does not make it trustworthy")
    void extracting_a_field_does_not_make_it_trustworthy() {
      Handle<InvoiceNumber> number = claimedInvoice.derive(claim(), acme()).orThrow();

      assertThat(loch.label(number).integrity()).isEqualTo(Integrity.UNENDORSED);
    }

    @Test
    @DisplayName("records what it came from, so erasure has something to follow")
    void records_what_it_came_from() {
      Handle<DisputeClaim> parent = claim();

      Handle<InvoiceNumber> number = claimedInvoice.derive(parent, acme()).orThrow();

      assertThat(loch.lineage(number).parents()).containsExactly(parent.id());
      assertThat(loch.lineage(number).derivation()).contains(CLAIMED_INVOICE);
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

      Handle<InvoiceNumber> once = claimedInvoice.derive(parent, acme()).orThrow();
      Handle<InvoiceNumber> twice = claimedInvoice.derive(parent, acme()).orThrow();

      assertThat(once.id()).isNotEqualTo(twice.id());
      assertThat(loch.lineage(once).parents()).containsExactly(parent.id());
      assertThat(loch.lineage(twice).parents()).containsExactly(parent.id());
    }

    @Test
    @DisplayName("two different parents give two different handles")
    void two_different_parents_give_two_different_handles() {
      assertThat(claimedInvoice.derive(claim(), acme()).orThrow().id())
          .isNotEqualTo(claimedInvoice.derive(claim(), acme()).orThrow().id());
    }

    /**
     * This used to invent a name and assert the loch refused it. The name no longer buys anything
     * -- there is no method that takes one -- so what is worth asserting is the property that
     * replaced it, and it is the stronger one: a derivation cannot be run unless somebody handed
     * you the capability, and a capability minted after the loch was built was handed nothing.
     *
     * <p>Not a policy. There is no check to disable: a late capability is attached to no loch, so
     * there is nothing for it to act on.
     */
    @Test
    @DisplayName("a derivation minted after the loch was built is attached to nothing")
    void a_derivation_minted_afterwards_is_attached_to_nothing() {
      Derivation<DisputeClaim, InvoiceNumber> invented =
          config
              .derivation(
                  "whatever-i-like",
                  DisputeClaim.class,
                  InvoiceNumber.class,
                  c -> new InvoiceNumber(c.invoiceNumber()))
              .acceptingAnything()
              .mint();
      Handle<DisputeClaim> claim = claim();

      assertThatThrownBy(() -> invented.derive(claim, acme()))
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("attached to no loch");
    }
  }

  @Nested
  @DisplayName("weakening a label")
  class Weakening {

    private Handle<String> token() {
      return holdAs(
          Billing.of("acme", Integrity.ENDORSED, Tlp.RED, DataClass.CARDHOLDER),
          cardTokens,
          "tok_1P9xyz4821");
    }

    private AccessContext preparingApproval() {
      return acme("tool", "prepare_approval");
    }

    @Test
    @DisplayName("truncating a card lowers it to PII, which a person may then see")
    void truncating_a_card_lowers_it_to_pii() {
      Handle<Last4> last4 = cardLast4.derive(token(), preparingApproval()).orThrow();

      assertThat(loch.label(last4).dataClass()).isEqualTo(DataClass.PII);
      assertThat(approvalCardLast4.read(last4, acme("clearance", "finance")).granted())
          .contains(new Last4("4821"));
    }

    @Test
    @DisplayName("and lowers nothing it did not name: still acme's, still endorsed")
    void lowers_nothing_it_did_not_name() {
      Handle<Last4> last4 = cardLast4.derive(token(), preparingApproval()).orThrow();

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
      Handle<Last4> partly = cardLast4Partial.derive(token(), acme()).orThrow();

      assertThat(loch.label(partly).dataClass()).isEqualTo(DataClass.PII);
      assertThat(loch.label(partly).tlp()).isEqualTo(Tlp.RED);
      assertThat(approvalCardLast4.read(partly, acme("clearance", "finance")).allowed()).isFalse();
    }

    @Test
    @DisplayName("is offered only where it was declared to be")
    void is_offered_only_where_declared() {
      assertThat(cardLast4.derive(token(), acme()))
          .isInstanceOfSatisfying(
              Derived.Refused.class,
              refused -> assertThat(refused.reason()).isEqualTo(Derived.Reason.NOT_AVAILABLE_HERE));
    }

    /** Declaring a relabel that raises is a mistake, not a policy. Ordinary derivation raises. */
    @Test
    @DisplayName("a relabel that does not actually lower is refused")
    void a_relabel_that_does_not_lower_is_refused() {
      Handle<DisputeClaim> endorsed =
          holdAs(
              Billing.of("acme", Integrity.ENDORSED, Tlp.CLEAR, DataClass.NONE),
              disputeClaims,
              new DisputeClaim("INV-1", "x"));

      assertThat(wishful.derive(endorsed, acme()))
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
          .contains(SUMMARISE_FOR_RELEASE)
          .doesNotContain(SUMMARISE);
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
      return holdAs(
          Billing.of("acme", Integrity.ENDORSED, Tlp.AMBER, DataClass.PII),
          accounts,
          new Account("ACC-1", "someone@acme.example"));
    }

    @Test
    @DisplayName("answers the question without the account ever leaving")
    void answers_without_the_account_leaving() {
      Handle<Account> account = account();

      assertThat(ownedBy.ask(account, "someone@acme.example", acme()).isTrue()).isTrue();
      assertThat(ownedBy.ask(account, "attacker@elsewhere.example", acme()).isFalse()).isTrue();
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
      Handle<Account> acmeAccount = account();

      Answer answer = ownedBy.ask(acmeAccount, "x", globex());

      assertThat(answer.isTrue()).isFalse();
      assertThat(answer.isFalse()).isFalse();
      assertThat(answer.ran()).isFalse();
    }

    @Test
    @DisplayName("refuses to look at a value it was never meant to see")
    void refuses_to_look_at_a_value_it_was_never_meant_to_see() {
      LochConfig<Billing, Object> choosyConfig = new LochConfig<>();
      choosyConfig.lattice(Billing.LATTICE).withoutAudit().askingWhoIsAsking(edge::get);
      Inlet<Account> secretAccounts =
          choosyConfig.inlet(
              "secret-accounts",
              Account.class,
              Billing.of("acme", Integrity.ENDORSED, Tlp.RED, DataClass.CARDHOLDER));
      Query<Account, String> secretOwnedBy =
          choosyConfig
              .query("Account.ownedBy", Account.class, String.class, (account, sender, ctx) -> true)
              .accepting(reading(Integrity.ENDORSED, Tlp.CLEAR, DataClass.NONE))
              .mint();
      Loch<Billing> choosy = MemoryLoch.create(choosyConfig);
      Handle<Account> secret = secretAccounts.hold(new Account("ACC-2", "x@y.example"));

      assertThat(secretOwnedBy.ask(secret, "x@y.example", acme()))
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
      Dereferenced<String> allowed = quarantinedLlmText.read(customerEmail(), acme());

      assertThat(allowed.allowed()).isTrue();
      assertThat(allowed.toString()).doesNotContain("123-45-6789");
    }

    @Test
    @DisplayName("a refusal names the destination but not the labels")
    void a_refusal_names_the_destination_but_not_the_labels() {
      Dereferenced<String> denied = vendorLlmText.read(customerEmail(), acme());

      String detail = ((Dereferenced.Denied<String>) denied).detail();
      assertThat(detail).contains("vendor-llm").doesNotContain("acme").doesNotContain("PII");
    }

    @Test
    @DisplayName("unless the application asks for the explanation")
    void unless_the_application_asks_for_the_explanation() {
      LochConfig<Billing, Object> chattyConfig = new LochConfig<>();
      chattyConfig
          .lattice(Billing.LATTICE)
          .withoutAudit()
          .askingWhoIsAsking(edge::get)
          .explainRefusals();
      Inlet<String> chattyMail =
          chattyConfig.inlet("mail", String.class, BillingScenarioTest::labelFrom);
      Outlet<String> chattyVendorLlm =
          chattyConfig.outlet(
              "vendor-llm",
              String.class,
              ctx -> Billing.ceilingFor(ctx, Integrity.ENDORSED, Tlp.CLEAR, DataClass.NONE));
      Loch<Billing> chatty = MemoryLoch.create(chattyConfig);
      Handle<String> held =
          holdAs(
              Billing.of("acme", Integrity.UNENDORSED, Tlp.AMBER, DataClass.PII), chattyMail, "x");

      Dereferenced<String> denied = chattyVendorLlm.read(held, acme());

      assertThat(((Dereferenced.Denied<String>) denied).detail()).contains("PII");
    }

    /** A policy that cannot be evaluated has not said yes. */
    @Test
    @DisplayName("a destination whose ceiling throws denies, rather than exploding")
    void a_destination_whose_ceiling_throws_denies() {
      LochConfig<Billing, Object> fragileConfig = new LochConfig<>();
      fragileConfig.lattice(Billing.LATTICE).withoutAudit().askingWhoIsAsking(edge::get);
      Inlet<String> fragileMail =
          fragileConfig.inlet("mail", String.class, BillingScenarioTest::labelFrom);
      Outlet<String> broken =
          fragileConfig.outlet(
              "broken",
              String.class,
              ctx -> {
                throw new IllegalStateException("policy service is down");
              });
      Loch<Billing> fragile = MemoryLoch.create(fragileConfig);
      Handle<String> held =
          holdAs(
              Billing.of("acme", Integrity.ENDORSED, Tlp.CLEAR, DataClass.NONE), fragileMail, "x");

      Dereferenced<String> result = broken.read(held, acme());

      assertThat(result.allowed()).isFalse();
    }
  }

  @Nested
  @DisplayName("the record")
  class TheRecord {

    @Test
    @DisplayName("says who reached what, and never what the value was")
    void says_who_reached_what_and_never_the_value() {
      quarantinedLlmText.read(customerEmail(), acme());

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
      vendorLlmText.read(customerEmail(), acme());

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
          holdAs(
              Billing.of("acme", Integrity.ENDORSED, Tlp.AMBER, DataClass.PII),
              accounts,
              new Account("ACC-1", "someone@acme.example"));

      ownedBy.ask(account, "someone@acme.example", acme());

      AuditRecord entry = audit.of(AuditRecord.Operation.ASK).getLast();
      assertThat(entry.reason()).contains("answered true");
      assertThat(entry.toString()).doesNotContain("someone@acme.example");
    }

    /** The event an auditor most wants to find. */
    @Test
    @DisplayName("says so when a derivation weakened a label")
    void says_so_when_a_derivation_weakened_a_label() {
      Handle<String> token =
          holdAs(
              Billing.of("acme", Integrity.ENDORSED, Tlp.RED, DataClass.CARDHOLDER),
              cardTokens,
              "tok_1P9xyz4821");

      cardLast4.derive(token, acme("tool", "prepare_approval"));

      AuditRecord entry = audit.of(AuditRecord.Operation.DERIVE).getLast();
      assertThat(entry.reason()).hasValueSatisfying(r -> assertThat(r).startsWith("weakened from"));
      assertThat(entry.target()).contains("Card.last4");
    }

    @Test
    @DisplayName("an ordinary derivation is recorded without that note")
    void an_ordinary_derivation_is_recorded_without_that_note() {
      Handle<DisputeClaim> claim =
          holdAs(
              Billing.of("acme", Integrity.UNENDORSED, Tlp.AMBER, DataClass.PII),
              disputeClaims,
              new DisputeClaim("INV-4471", "charged twice"));

      claimedInvoice.derive(claim, acme());

      assertThat(audit.of(AuditRecord.Operation.DERIVE).getLast().reason()).isEmpty();
    }

    @Test
    @DisplayName("but one made from several values says so, since it is more constrained than any")
    void one_made_from_several_says_so() {
      Handle<String> first =
          holdAs(Billing.of("acme", Integrity.ENDORSED, Tlp.CLEAR, DataClass.NONE), notes, "a");
      Handle<String> second =
          holdAs(Billing.of("acme", Integrity.ENDORSED, Tlp.CLEAR, DataClass.NONE), notes, "b");

      summarise.fold(java.util.List.of(first, second), acme());

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
      LochConfig<Billing, Object> unloggableConfig = new LochConfig<>();
      unloggableConfig
          .lattice(Billing.LATTICE)
          .askingWhoIsAsking(edge::get)
          .auditor(
              record -> {
                throw new IllegalStateException("the audit sink is down");
              });
      unloggableConfig.outlet(
          "quarantined-llm",
          String.class,
          ctx -> Billing.ceilingFor(ctx, Integrity.UNENDORSED, Tlp.AMBER, DataClass.PII));
      Loch<Billing> unloggable = MemoryLoch.create(unloggableConfig);

      LochConfig<Billing, Object> watchedConfig =
          configuredTo(
              record -> {
                throw new IllegalStateException("the audit sink is down");
              });
      Inlet<String> watchedMail =
          watchedConfig.inlet("mail", String.class, BillingScenarioTest::labelFrom);
      MemoryStorage<Billing> storage = new MemoryStorage<>();
      Loch<Billing> watched = new DefaultLoch<>(watchedConfig, storage);

      assertThatThrownBy(
              () ->
                  holdAs(
                      Billing.of("acme", Integrity.ENDORSED, Tlp.CLEAR, DataClass.NONE),
                      watchedMail,
                      "anything"))
          .isInstanceOf(IllegalStateException.class);

      // Nothing was written. The other order leaves a secret nobody can reach, read or erase.
      assertThat(storage.everything()).isEmpty();
    }

    @Test
    @DisplayName("keeping no record is something you say, not something you omit")
    void keeping_no_record_is_something_you_say() {
      assertThatThrownBy(() -> MemoryLoch.<Billing, Object>create(c -> c.lattice(Billing.LATTICE)))
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("withoutAudit");
    }
  }

  /** Builds the same policy this test uses, with a chosen auditor. */
  private LochConfig<Billing, Object> configuredTo(Auditor auditor) {
    LochConfig<Billing, Object> config = new LochConfig<>();
    config.lattice(Billing.LATTICE).auditor(auditor).askingWhoIsAsking(edge::get);
    return config;
  }

  @Nested
  @DisplayName("refusals reach the record too")
  class RefusalsAreRecorded {

    private Handle<DisputeClaim> claim() {
      return holdAs(
          Billing.of("acme", Integrity.UNENDORSED, Tlp.AMBER, DataClass.PII),
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
      audit.clear();

      declines.derive(claim(), acme());

      assertThat(audit.of(AuditRecord.Operation.DERIVE))
          .isNotEmpty()
          .anySatisfy(
              entry -> {
                assertThat(entry.outcome()).isEqualTo(AuditRecord.Outcome.REFUSED);
                assertThat(entry.reason()).contains("DECLINED");
                assertThat(entry.target()).contains(DECLINES);
              });
    }

    /** Refused before the value was looked at, so the record honestly has no label to give. */
    @Test
    @DisplayName("a derivation not offered here is recorded, and says nothing about the value")
    void a_derivation_not_offered_here_is_recorded() {
      Handle<String> token =
          holdAs(
              Billing.of("acme", Integrity.ENDORSED, Tlp.RED, DataClass.CARDHOLDER),
              cardTokens,
              "tok_1P9xyz4821");
      audit.clear();

      cardLast4.derive(token, acme());

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

      declines.derive(claim(), acme());

      assertThat(audit.of(AuditRecord.Operation.DERIVE).getLast().label())
          .hasValueSatisfying(label -> assertThat(label).contains("UNENDORSED"));
    }

    /**
     * A refusal reaches the record too, because a query reads plaintext in order to answer and a
     * turned-away read is exactly what an auditor is looking for.
     */
    @Test
    @DisplayName("a refused check is recorded")
    void a_refused_check_is_recorded() {
      audit.clear();

      Handle<Account> acmeAccount =
          holdAs(
              Billing.of("acme", Integrity.ENDORSED, Tlp.AMBER, DataClass.PII),
              accounts,
              new Account("ACC-1", "someone@acme.example"));
      audit.clear();
      ownedBy.ask(acmeAccount, "x", globex());

      assertThat(audit.of(AuditRecord.Operation.ASK))
          .anySatisfy(
              entry -> {
                assertThat(entry.outcome()).isEqualTo(AuditRecord.Outcome.REFUSED);
                assertThat(entry.reason()).contains("ABOVE_CEILING");
              });
    }

    @Test
    @DisplayName("a fold refused at the gate is recorded")
    void a_fold_refused_at_the_gate_is_recorded() {
      audit.clear();

      summarise.fold(java.util.List.of(), acme());

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

      declines.derive(claim(), acme());

      assertThat(audit.of(AuditRecord.Operation.DERIVE).getLast().toString())
          .doesNotContain("charged twice")
          .doesNotContain("INV-4471");
    }
  }
}
