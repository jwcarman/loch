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

import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.jwcarman.loch.lattice.Axis;
import org.jwcarman.loch.lattice.Label;

/**
 * A source labels what arrives, and sometimes only the thing itself can say how.
 *
 * <p>Some of a label is a property of the door: mail from customers is untrusted because of where
 * it came in, whatever it contains. The rest is a property of the value. Whether the sender was
 * verified is in the message. Whether a document is restricted is written on the document. Whether
 * something holds card numbers is a question you answer by looking.
 *
 * <p>So the labelling function sees both. That is not a way for a caller to choose a label -- the
 * caller passes a value and nothing else, and the function deciding is the one declared when the
 * source was declared. What changes is that the door can read what it was handed before saying what
 * it is.
 */
@DisplayName("Labelling what arrives")
class LabellingWhatArrivesTest {

  private static final SurrogateType<Mail> MAIL_TYPE = SurrogateType.of(Mail.class);

  enum Integrity {
    ENDORSED,
    UNENDORSED
  }

  private static final Axis<String> TENANT = Axis.matching("tenant");
  private static final Axis<Integrity> INTEGRITY =
      Axis.ladder("integrity", Integrity.ENDORSED, Integrity.UNENDORSED);

  record Mail(String from, String body, boolean senderVerified) {}

  private final AtomicReference<AccessContext> edge = new AtomicReference<>(AccessContext.empty());

  private final DefaultCharter config =
      new DefaultCharter(TENANT, INTEGRITY).currentAccess(edge::get);

  /** The tenant comes from the access; the trust comes from the message. */
  private final Conceal<Mail> mail =
      config.source(
          "customer-mail",
          MAIL_TYPE,
          (message, ctx) ->
              ctx.get("tenant")
                  .map(tenant -> Label.of(TENANT, tenant))
                  .orElseGet(Label::nothing)
                  .with(
                      INTEGRITY,
                      message.senderVerified() ? Integrity.ENDORSED : Integrity.UNENDORSED));

  {
    config.seal(new MemoryStorage());
  }

  // there is no direct way left to ask what a stored label says on a given axis. Label.toString()
  // is the only supported path back to a per-axis value, and it is documented for rendering only
  // ("For a manifest line or an audit row. Never for a decision."). Using it here to assert what a
  // label says is a real deviation from PRESERVE-exactly, flagged for James: this test's whole
  // point -- that the source's labelling function reads part of the label from the value -- cannot
  // be checked any other way against the current API.
  @Test
  @DisplayName("takes the part of the label that only the value knows")
  void takes_the_part_only_the_value_knows() {
    edge.set(AccessContext.of(Map.of("tenant", "acme")));

    Surrogate<Mail> verified = mail.conceal(new Mail("known@acme.example", "hello", true));
    Surrogate<Mail> anonymous = mail.conceal(new Mail("who@nowhere.example", "hello", false));

    assertThat(config.label(verified.id()).says(INTEGRITY, Integrity.ENDORSED)).isTrue();
    assertThat(config.label(anonymous.id()).says(INTEGRITY, Integrity.UNENDORSED)).isTrue();
  }

  /** The rest of the label is still the access's business, and the value cannot touch it. */
  @Test
  @DisplayName("without letting the value choose the part that is not its business")
  void without_letting_the_value_choose_the_rest() {
    edge.set(AccessContext.of(Map.of("tenant", "acme")));
    Surrogate<Mail> acmeMail = mail.conceal(new Mail("x@y.example", "globex globex globex", true));

    assertThat(config.label(acmeMail.id()).says(TENANT, "acme")).isTrue();
  }
}
