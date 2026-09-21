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
package org.jwcarman.loch.jdbc;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.jwcarman.loch.Handle;
import org.jwcarman.loch.HandleId;
import tools.jackson.databind.json.JsonMapper;

/**
 * The claim this library makes most often: a reference can go anywhere, because holding one is not
 * permission to read it.
 *
 * <p>It only holds if the reference actually survives the journey. A {@code Handle<T>} does not: it
 * carries a {@code TypeRef}, which no serialiser can reconstruct, and the type was never authority
 * anyway -- the gate checks it against what the store wrote. So what travels is the id, and the
 * receiving side says what it expects.
 */
@DisplayName("A reference in an event")
class HandleTravelTest {

  /** An ordinary application event. Nothing here knows about Loch except the id. */
  record InboundMail(String from, HandleId body) {}

  record Card(String number) {}

  @Test
  @DisplayName("survives a round trip through JSON with nothing taught to any serialiser")
  void survives_a_round_trip_through_json() {
    JsonMapper mapper = JsonMapper.builder().build();
    HandleId id = HandleId.fresh();

    String json = mapper.writeValueAsString(new InboundMail("x@y.example", id));
    InboundMail back = mapper.readValue(json, InboundMail.class);

    assertThat(back.body()).isEqualTo(id);
    assertThat(json).contains(id.value());
  }

  @Test
  @DisplayName("becomes a typed view again where it is used")
  void becomes_a_typed_view_again_where_it_is_used() {
    HandleId id = HandleId.fresh();

    Handle<Card> held = Handle.of(id, Card.class);

    assertThat(held.id()).isEqualTo(id);
    assertThat(held.type().rawClass()).isEqualTo(Card.class);
  }

  /** Anything that prints a reference gets a name, and learns nothing else from it. */
  @Test
  @DisplayName("prints as its id and nothing else")
  void prints_as_its_id_and_nothing_else() {
    HandleId id = HandleId.fresh();

    assertThat(Handle.of(id, Card.class).toString()).isEqualTo(id.value()).doesNotContain("Card");
  }
}
