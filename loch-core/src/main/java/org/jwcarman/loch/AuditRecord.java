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

import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * One line of the record: something was asked of a held value, and this is what happened.
 *
 * <p><b>Never the plaintext.</b> An audit says which value, who asked, where it was going and what
 * was decided. If it said what the value was, the audit log would become the largest collection of
 * protected data in the system and the least protected.
 *
 * <p>It does carry the label, because an audit that cannot say <i>why</i> something was refused is
 * not much of an audit. A label can itself be sensitive -- a tenant's name, a project codeword --
 * so an audit sink deserves the same protection as the values it describes.
 *
 * <p>Refusals are recorded as carefully as permissions. A thousand refused attempts against one
 * value is the interesting event, and a log that only records successes cannot show it.
 *
 * @param target what was on the other side: a destination, a derivation, a check
 * @param context whatever the application contributed about who was asking
 */
public record AuditRecord(
    Instant at,
    Operation operation,
    HeldId value,
    Optional<String> target,
    Outcome outcome,
    Optional<String> reason,
    Optional<String> label,
    Map<String, String> context) {

  /** What was being attempted. */
  public enum Operation {
    /** A value was taken into custody, with labels its caller asserted. */
    HOLD,
    /** Plaintext was asked for, on its way somewhere. */
    DEREFERENCE,
    /** A new value was made from one already held. */
    DERIVE,
    /** A question was answered about a value without the value leaving. */
    CHECK
  }

  public enum Outcome {
    ALLOWED,
    REFUSED
  }

  public AuditRecord {
    Objects.requireNonNull(at, "an audit record needs a time");
    Objects.requireNonNull(operation, "an audit record needs an operation");
    context = Map.copyOf(context);
  }

  @Override
  public String toString() {
    return "%s %s %s%s %s%s"
        .formatted(
            at,
            operation,
            value,
            target.map(" -> "::concat).orElse(""),
            outcome,
            reason.map(": "::concat).orElse(""));
  }
}
