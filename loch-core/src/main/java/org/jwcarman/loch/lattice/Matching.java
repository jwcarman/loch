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
package org.jwcarman.loch.lattice;

/**
 * A value that has to match exactly, and what happens when two of them do not.
 *
 * <p>Two tenants are not one more constrained than the other; they are incomparable. So combining
 * them cannot pick a winner, and refusing is not an option either -- a combine that can fail makes
 * the whole algebra partial, and it tells you at the wrong moment, while combining rather than when
 * someone tries to use the result.
 *
 * <p>So the mixture is a <i>value</i>. It sits above every single value, no ceiling admits it, and
 * a mixed thing exists with honest lineage to both parents while being incapable of reaching any
 * destination. Cross-tenant leakage is not forbidden by a rule someone remembered to write; the
 * value that would carry it is born unusable.
 *
 * <p><b>Only this class can make one.</b> An application writes {@code "acme"} and gets a single
 * value; there is no public constructor anywhere that produces a mixture. It arises only from
 * {@link #join}, inside the engine, which is what turns "no ceiling admits a mixture" from a
 * convention into a fact about what can be spoken.
 */
final class Matching implements Order {

  /**
   * One value, nothing, or a mixture.
   *
   * @param value the single value, or null when unsaid or mixed
   * @param mixed whether two different values were combined
   */
  private record Matched(String value, boolean mixed) {}

  private static final Matched UNSAID = new Matched(null, false);
  private static final Matched MIXED = new Matched(null, true);

  private static final String MIXED_ENCODING = "~";
  private static final String VALUE_PREFIX = "=";

  @Override
  public Object lift(Object written) {
    return new Matched((String) written, false);
  }

  @Override
  public Object bottom() {
    return UNSAID;
  }

  @Override
  public Object join(Object left, Object right) {
    Matched one = (Matched) left;
    Matched other = (Matched) right;
    if (one.mixed() || other.mixed()) {
      return MIXED;
    }
    if (one.value() == null) {
      return other;
    }
    if (other.value() == null) {
      return one;
    }
    return one.value().equals(other.value()) ? one : MIXED;
  }

  @Override
  public boolean admitsAny(Object value) {
    return !((Matched) value).mixed();
  }

  @Override
  public String render(Object value) {
    Matched matched = (Matched) value;
    if (matched.mixed()) {
      return "(mixed)";
    }
    return matched.value() == null ? "(unsaid)" : matched.value();
  }

  @Override
  public String encode(Object value) {
    Matched matched = (Matched) value;
    if (matched.mixed()) {
      return MIXED_ENCODING;
    }
    return matched.value() == null ? "" : VALUE_PREFIX + matched.value();
  }

  @Override
  public Object decode(String encoded) {
    if (encoded.isEmpty()) {
      return UNSAID;
    }
    if (MIXED_ENCODING.equals(encoded)) {
      return MIXED;
    }
    if (!encoded.startsWith(VALUE_PREFIX)) {
      throw new IllegalArgumentException("'" + encoded + "' is not a matching-axis value");
    }
    return new Matched(encoded.substring(VALUE_PREFIX.length()), false);
  }
}
