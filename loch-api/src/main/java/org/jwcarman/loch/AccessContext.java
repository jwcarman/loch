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

import java.util.Map;
import java.util.Optional;

/**
 * What the application knows about whoever is asking, at the moment they ask.
 *
 * <p><b>SurrogateStore never interprets this.</b> There is no principal, no role and no identity
 * concept in this library -- only a bag of strings an application fills in and its own ceiling
 * functions read. An application with no notion of identity contributes nothing and every
 * destination ignores it.
 *
 * <p>It exists because of one case: a destination that is a <em>person</em>. Two approvers looking
 * at the same record are not necessarily entitled to see the same thing. Machine destinations
 * almost always ignore the context entirely -- a payment processor accepts cardholder data or it
 * does not, whoever asked.
 *
 * <p>It is also what an audit line records, which is the other reason it cannot be skipped.
 */
public record AccessContext(Map<String, String> attributes) {

  private static final AccessContext EMPTY = new AccessContext(Map.of());

  public AccessContext {
    attributes = Map.copyOf(attributes);
  }

  /** Nobody in particular: the right answer for machine-to-machine work. */
  public static AccessContext empty() {
    return EMPTY;
  }

  public static AccessContext of(Map<String, String> attributes) {
    return new AccessContext(attributes);
  }

  public static AccessContext of(String key, String value) {
    return new AccessContext(Map.of(key, value));
  }

  public Optional<String> get(String key) {
    return Optional.ofNullable(attributes.get(key));
  }

  /**
   * The ambient facts, plus whichever of these a caller is permitted to contribute.
   *
   * <p><b>Ambient wins, always.</b> Identity is established at the edge -- a request, a message, a
   * session -- and a call site is not entitled to revise it. If a caller could override what the
   * edge asserted, then any code holding a store could name itself whichever tenant it liked and
   * the gate would agree, which is not a policy system, it is a formality.
   *
   * <p>What a caller legitimately has is something the edge does not know: the purpose of this
   * operation, which tool is running. So it may <i>add</i> keys, and only keys the application
   * declared it may add. Everything else it says is ignored rather than refused, because a caller
   * naming something it should not is a bug in the caller, not an attack the gate should fail over.
   *
   * @param permitted the keys a caller is allowed to contribute at all
   */
  AccessContext contributedTo(AccessContext ambient, java.util.Set<String> permitted) {
    if (attributes.isEmpty() || permitted.isEmpty()) {
      return ambient;
    }
    Map<String, String> combined = new java.util.LinkedHashMap<>(ambient.attributes());
    attributes.forEach(
        (key, value) -> {
          if (permitted.contains(key)) {
            combined.putIfAbsent(key, value);
          }
        });
    return new AccessContext(combined);
  }

  /** Whether an attribute has exactly this value, which is what a ceiling function usually asks. */
  public boolean has(String key, String value) {
    return value.equals(attributes.get(key));
  }
}
