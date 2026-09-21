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

import static org.jwcarman.loch.example.billing.BillingLabels.Integrity.ENDORSED;
import static org.jwcarman.loch.example.billing.BillingLabels.Integrity.UNENDORSED;
import static org.jwcarman.loch.example.billing.BillingLabels.Sensitivity.CARDHOLDER;
import static org.jwcarman.loch.example.billing.BillingLabels.Sensitivity.ORDINARY;
import static org.jwcarman.loch.example.billing.BillingLabels.Sensitivity.PERSONAL;

import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.sql.DataSource;
import org.jwcarman.codec.jackson.JacksonCodecFactory;
import org.jwcarman.loch.AccessContext;
import org.jwcarman.loch.Auditor;
import org.jwcarman.loch.Derivations;
import org.jwcarman.loch.Inlet;
import org.jwcarman.loch.Loch;
import org.jwcarman.loch.Outlet;
import org.jwcarman.loch.Question;
import org.jwcarman.loch.jdbc.JdbcLoch;
import org.jwcarman.loch.jdbc.JdbcLochConfig;
import org.jwcarman.loch.jdbc.StorageCodec;
import org.jwcarman.loch.lattice.Exact;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.event.EventListener;
import tools.jackson.databind.json.JsonMapper;

/** This application's entire information-flow policy, in one file. */
@Configuration
public class LochConfiguration {

  private static final Logger log = LoggerFactory.getLogger(LochConfiguration.class);
  private static final Pattern INVOICE = Pattern.compile("INV-\\d+");

  // Minted once, in a constructor, and handed out below. Nothing can obtain one any other way:
  // there is no method that trades an id for the capability it names, which is the only reason
  // holding one of these means anything.

  private final Loch<BillingLabels> loch;
  private final Inlet<Domain.Mail> customerMail;
  private final Outlet supportUi;
  private final Outlet approvalDesk;
  private final Outlet paymentProcessor;

  public LochConfiguration(
      DataSource dataSource, StorageCodec storageCodec, Auditor auditor, Invoices invoices) {

    JdbcLochConfig<BillingLabels> c = new JdbcLochConfig<>();
    c.dataSource(dataSource)
        .codecs(new JacksonCodecFactory(JsonMapper.builder().build()))
        .storedThrough(storageCodec)
        .lattice(BillingLabels.LATTICE)
        .auditor(auditor)
        .askingWhoIsAsking(CurrentAccess::get)
        // A stopgap, and only that. Inlets make a blind write up unsayable -- no method on one
        // takes a label -- but Loch.hold still exists and still takes a label, so the old door
        // needs its old lock until that door is gone. Deleting this line while hold(...) remains
        // leaves the application strictly less safe than before inlets existed.
        .mayHold(
            (label, ctx) ->
                label.tenant().resolved().filter(t -> ctx.has("tenant", t)).isPresent());

    // ---- how values get in ----------------------------------------------------
    // The tenant is read from the access, never passed by the caller. A blind write up is not
    // refused here so much as unsayable: no method on an inlet takes a label.
    this.customerMail =
        c.inlet(Billing.CUSTOMER_MAIL, Domain.Mail.class, ctx -> label(ctx, UNENDORSED, PERSONAL));

    // ---- how values get out ---------------------------------------------------
    this.supportUi = c.outlet(Billing.SUPPORT_UI, ctx -> label(ctx, ENDORSED, ORDINARY));
    this.approvalDesk =
        c.outlet(
            Billing.APPROVAL_DESK,
            ctx -> label(ctx, ENDORSED, ctx.has("role", "approver") ? PERSONAL : ORDINARY));
    this.paymentProcessor =
        c.outlet(Billing.PAYMENT_PROCESSOR, ctx -> label(ctx, ENDORSED, CARDHOLDER));

    // ---- one value from another -----------------------------------------------
    // The only operation that can raise trust, and it earns it by tying what the customer
    // claimed to the mailbox their message came from.
    c.derivation(
            Derivations.<BillingLabels, Domain.Mail, Domain.Invoice>checking(
                    Billing.CONFIRMED_INVOICE,
                    Domain.Mail.class,
                    Domain.Invoice.class,
                    (mail, ctx) -> confirm(invoices, mail, ctx))
                // Reads untrusted personal mail, and only this tenant's.
                .accepting(ctx -> label(ctx, UNENDORSED, PERSONAL))
                .lowering(joined -> joined.withIntegrity(ENDORSED))
                .build())

        // Truncating a card is a declassification, which is what a PCI reviewer asks about.
        .derivation(
            Derivations.<BillingLabels, Domain.Invoice, Domain.Last4>of(
                    Billing.CARD_LAST4,
                    Domain.Invoice.class,
                    Domain.Last4.class,
                    invoice -> new Domain.Last4(last4(invoice.cardToken())))
                .accepting(ctx -> label(ctx, ENDORSED, CARDHOLDER))
                .lowering(joined -> joined.withSensitivity(PERSONAL))
                .availableTo(ctx -> ctx.has("role", "approver"))
                .build())

        // ---- questions answered without handing the value over --------------------
        .question(
            Question.<BillingLabels, Domain.Mail, String>of(
                    Billing.MAIL_MENTIONS,
                    Domain.Mail.class,
                    (mail, text) -> mail.body().toLowerCase().contains(text.toLowerCase()))
                // A ceiling, like an outlet's, takes the tenant from the access: there is no
                // fixed ceiling meaning "any one tenant but not a mixture".
                .accepting(ctx -> label(ctx, UNENDORSED, PERSONAL))
                .build());

    this.loch = JdbcLoch.create(BillingLabels.class, c);
  }

  @Bean
  public Loch<BillingLabels> loch() {
    return loch;
  }

  @Bean
  public Inlet<Domain.Mail> customerMail() {
    return customerMail;
  }

  @Bean
  public Outlet supportUi() {
    return supportUi;
  }

  @Bean
  public Outlet approvalDesk() {
    return approvalDesk;
  }

  @Bean
  public Outlet paymentProcessor() {
    return paymentProcessor;
  }

  /** Printed once at startup, so what this service will allow is in the log. */
  @EventListener(ApplicationReadyEvent.class)
  public void announce(ApplicationReadyEvent event) {
    log.info("\n{}", event.getApplicationContext().getBean(Loch.class).manifest());
  }

  /**
   * An access is always on behalf of one tenant, and a ceiling says so.
   *
   * <p>Naming nobody gives a ceiling of "no tenant", which admits only unattributed values: the
   * fail-closed answer, and deliberate.
   */
  private static BillingLabels label(
      AccessContext ctx, BillingLabels.Integrity integrity, BillingLabels.Sensitivity sensitivity) {
    return new BillingLabels(
        ctx.get("tenant").<Exact<String>>map(Exact::of).orElseGet(Exact::none),
        integrity,
        sensitivity);
  }

  /**
   * An endorsement is only as strong as what it bound: here, the sender's own mailbox.
   *
   * <p>There used to be a {@code ctx.has("tenant", ...)} filter here too. It is gone because the
   * derivation now declares a ceiling, so it cannot be handed another tenant's mail in the first
   * place. Hand-written filtering inside a function that reads plaintext is exactly what a ceiling
   * is for, and writing both meant the safety depended on the weaker one.
   */
  private static Optional<Domain.Invoice> confirm(
      Invoices invoices, Domain.Mail mail, AccessContext ctx) {
    Matcher matcher = INVOICE.matcher(mail.body());
    if (!matcher.find()) {
      return Optional.empty();
    }
    return invoices
        .find(matcher.group())
        .filter(invoice -> ctx.has("tenant", invoice.tenant()))
        .filter(invoice -> invoice.customerEmail().equalsIgnoreCase(mail.from()));
  }

  private static String last4(String token) {
    return token.substring(token.length() - 4);
  }
}
