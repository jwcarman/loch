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
import org.jwcarman.loch.lattice.Exact;
import org.jwcarman.loch.lattice.Lattice;
import org.jwcarman.loch.lattice.Lattices;

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
 * source was minted. What changes is that the door can read what it was handed before saying what
 * it is.
 */
@DisplayName("Labelling what arrives")
class LabellingWhatArrivesTest {

  enum Integrity {
    ENDORSED,
    UNENDORSED
  }

  record Labels(Exact<String> tenant, Integrity integrity) {
    static final Lattice<Labels> LATTICE =
        Lattices.product(
            Labels::new,
            Lattices.axis(Labels::tenant, Lattices.exact()),
            Lattices.axis(
                Labels::integrity, Lattices.ladder(Integrity.ENDORSED, Integrity.UNENDORSED)));
  }

  record Mail(String from, String body, boolean senderVerified) {}

  private final AtomicReference<AccessContext> edge = new AtomicReference<>(AccessContext.empty());

  private final SurrogateStoreConfig<Labels, Object> config =
      new SurrogateStoreConfig<Labels, Object>()
          .lattice(Labels.LATTICE)
          .askingWhoIsAsking(edge::get);

  /** The tenant comes from the access; the trust comes from the message. */
  private final SurrogateSource<Mail> mail =
      config.source(
          "customer-mail",
          Mail.class,
          (message, ctx) ->
              new Labels(
                  ctx.get("tenant").<Exact<String>>map(Exact::of).orElseGet(Exact::none),
                  message.senderVerified() ? Integrity.ENDORSED : Integrity.UNENDORSED));

  private final SurrogateStore<Labels> store = MemorySurrogateStore.create(config);

  @Test
  @DisplayName("takes the part of the label that only the value knows")
  void takes_the_part_only_the_value_knows() {
    edge.set(AccessContext.of(Map.of("tenant", "acme")));

    Surrogate<Mail> verified = mail.exchange(new Mail("known@acme.example", "hello", true));
    Surrogate<Mail> anonymous = mail.exchange(new Mail("who@nowhere.example", "hello", false));

    assertThat(store.label(verified.id()).integrity()).isEqualTo(Integrity.ENDORSED);
    assertThat(store.label(anonymous.id()).integrity()).isEqualTo(Integrity.UNENDORSED);
  }

  /** The rest of the label is still the access's business, and the value cannot touch it. */
  @Test
  @DisplayName("without letting the value choose the part that is not its business")
  void without_letting_the_value_choose_the_rest() {
    edge.set(AccessContext.of(Map.of("tenant", "acme")));
    Surrogate<Mail> acmeMail = mail.exchange(new Mail("x@y.example", "globex globex globex", true));

    assertThat(store.label(acmeMail.id()).tenant()).isEqualTo(Exact.of("acme"));
  }
}
