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
import org.jwcarman.loch.Derivation;
import org.jwcarman.loch.Loch;
import org.jwcarman.loch.Query;
import org.jwcarman.loch.SurrogateSink;
import org.jwcarman.loch.SurrogateSource;
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

/**
 * Everything this application is allowed to do, decided once, in one constructor.
 *
 * <p><b>Ordering is guaranteed because this is a constructor, not a bean graph.</b> Capabilities
 * are attached to a loch when that loch is built, so every one of them has to be minted first. A
 * constructor body runs top to bottom, so the compiler and the language settle the ordering and
 * Spring is never asked to. The {@code @Bean} methods below hand out what was already made; they do
 * not make anything.
 *
 * <p>Minting inside a {@code @Bean} method instead would mint <i>after</i> this class was built,
 * and quite possibly after the loch was. That capability would be attached to nothing and would
 * throw on first use rather than failing quietly, but the place to not do it is here.
 *
 * <p>The infrastructure it depends on lives in {@link StorageConfiguration}, because a
 * configuration class cannot both declare a bean and take it as a constructor parameter.
 */
@Configuration
public class LochConfiguration {

  private static final Logger log = LoggerFactory.getLogger(LochConfiguration.class);
  private static final Pattern INVOICE = Pattern.compile("INV-\\d+");

  private final Loch<BillingLabels> loch;
  private final SurrogateSource<Domain.Mail> customerMail;
  private final SurrogateSink<Domain.Invoice> supportUi;
  private final SurrogateSink<Domain.Last4> approvalDesk;
  private final SurrogateSink<Domain.Invoice> paymentProcessor;
  private final Derivation<Domain.Mail, Domain.Invoice> confirmInvoice;
  private final Derivation<Domain.Invoice, Domain.Last4> cardLast4;
  private final Query<Domain.Mail, String> mailMentions;

  public LochConfiguration(DataSource dataSource, StorageCodec storageCodec, Invoices invoices) {

    // The domain bound is the second parameter. Source<String> would not compile.
    JdbcLochConfig<BillingLabels, Domain.BillingValue> c = new JdbcLochConfig<>();
    c.dataSource(dataSource)
        .codecs(new JacksonCodecFactory(JsonMapper.builder().build()))
        .storedThrough(storageCodec)
        .lattice(BillingLabels.LATTICE)
        .askingWhoIsAsking(CurrentAccess::get);

    // ---- how values get in -----------------------------------------------------
    // The tenant is read from the access, never passed by the caller. Writing at another
    // tenant's label is not refused here so much as unsayable: nothing takes a label.
    this.customerMail =
        c.source("customer-mail", Domain.Mail.class, ctx -> label(ctx, UNENDORSED, PERSONAL));

    // ---- how values get out ----------------------------------------------------
    this.supportUi =
        c.sink("support-ui", Domain.Invoice.class, ctx -> label(ctx, ENDORSED, ORDINARY));
    this.approvalDesk =
        c.sink(
            "approval-desk",
            Domain.Last4.class,
            ctx -> label(ctx, ENDORSED, ctx.has("role", "approver") ? PERSONAL : ORDINARY));
    this.paymentProcessor =
        c.sink("payment-processor", Domain.Invoice.class, ctx -> label(ctx, ENDORSED, CARDHOLDER));

    // ---- one value from another ------------------------------------------------
    // The only operation that can raise trust, and it earns it by tying what the customer
    // claimed to the mailbox their message came from.
    this.confirmInvoice =
        c.checking(
                "mail.confirmedInvoice",
                Domain.Mail.class,
                Domain.Invoice.class,
                (mail, ctx) -> confirm(invoices, mail, ctx))
            .accepting(ctx -> label(ctx, UNENDORSED, PERSONAL))
            .lowering(joined -> joined.withIntegrity(ENDORSED))
            .mint();

    // Truncating a card is a declassification, which is what a PCI reviewer asks about.
    this.cardLast4 =
        c.derivation(
                "invoice.card.last4",
                Domain.Invoice.class,
                Domain.Last4.class,
                invoice -> new Domain.Last4(last4(invoice.cardToken())))
            .accepting(ctx -> label(ctx, ENDORSED, CARDHOLDER))
            .lowering(joined -> joined.withSensitivity(PERSONAL))
            .availableTo(ctx -> ctx.has("role", "approver"))
            .mint();

    // ---- one bit, without the value leaving ------------------------------------
    this.mailMentions =
        c.query(
                "mail.mentions",
                Domain.Mail.class,
                String.class,
                (mail, text, ctx) -> mail.body().toLowerCase().contains(text.toLowerCase()))
            .accepting(ctx -> label(ctx, UNENDORSED, PERSONAL))
            .mint();

    // Last, and only now: everything above is attached to this.
    this.loch = JdbcLoch.create(BillingLabels.class, c);
  }

  @Bean
  public Loch<BillingLabels> loch() {
    return loch;
  }

  /**
   * Hands the portals to the one class entitled to them.
   *
   * <p><b>The portals are not beans, and that is the point.</b> Spring's container is a
   * lookup-by-type service: publish an {@code SurrogateSink<Invoice>} and any class anywhere can
   * ask for one in its constructor and be given it. That is obtaining authority by naming it, which
   * is what deleting the id types was for. Authority is handed over here, in code somebody has to
   * write and a reviewer can read, or it is not handed over at all.
   */
  @Bean
  public DisputeService disputeService() {
    return new DisputeService(
        customerMail,
        supportUi,
        approvalDesk,
        paymentProcessor,
        confirmInvoice,
        cardLast4,
        mailMentions);
  }

  /** Printed once at startup, so what this service will allow is in the log. */
  @EventListener(ApplicationReadyEvent.class)
  public void announce(ApplicationReadyEvent event) {
    log.info("\n{}", event.getApplicationContext().getBean(Loch.class).manifest());
  }

  /**
   * A label for this access: the tenant comes from the request, never from the caller.
   *
   * <p>{@code Exact.none()} for a missing tenant would be a cross-tenant leak on its own -- none is
   * the bottom of the order, so it constrains nothing, so the value would be readable by every
   * tenant. It is safe here only because the tenant axis is declared required, which makes the loch
   * refuse the write rather than store something anybody can read.
   */
  private static BillingLabels label(
      AccessContext ctx, BillingLabels.Integrity integrity, BillingLabels.Sensitivity sensitivity) {
    return new BillingLabels(
        ctx.get("tenant").<Exact<String>>map(Exact::of).orElseGet(Exact::none),
        integrity,
        sensitivity);
  }

  /** Trust is earned by matching the claim against the mailbox it arrived from. */
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
