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
 * <p><b>Loch never interprets this.</b> There is no principal, no role and no identity concept in
 * this library -- only a bag of strings an application fills in and its own ceiling functions read.
 * An application with no notion of identity contributes nothing and every destination ignores it.
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
   * This context laid over another, so ambient facts and call-site facts combine.
   *
   * <p>Whoever is asking is usually known far from the call: a tenant and a principal come from a
   * request, a session or a message header, and threading them through every layer to reach a
   * {@code dereference} is the kind of plumbing that makes a safety feature something people route
   * around. So a loch resolves the ambient context itself, and a call that has something to add --
   * a purpose, which tool is running -- adds it rather than replacing everything.
   *
   * <p>This one wins where both say something about the same key.
   */
  public AccessContext over(AccessContext ambient) {
    if (ambient.attributes().isEmpty()) {
      return this;
    }
    if (attributes.isEmpty()) {
      return ambient;
    }
    Map<String, String> combined = new java.util.LinkedHashMap<>(ambient.attributes());
    combined.putAll(attributes);
    return new AccessContext(combined);
  }

  /** Whether an attribute has exactly this value, which is what a ceiling function usually asks. */
  public boolean has(String key, String value) {
    return value.equals(attributes.get(key));
  }
}
