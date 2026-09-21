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

/**
 * A store refused.
 *
 * <p>One exception for every gate, so a caller that wants to turn refusals into a 403 writes one
 * handler rather than discovering the second type in production. There were two of these, and the
 * example's controller caught one of them: a refused derivation -- the wrong tenant, not an
 * approver -- came back as a 500, and a test asserted that as though it were the intent.
 *
 * <p>The reason is a name rather than an enum because the gates refuse for different reasons and a
 * caller handling all of them wants a string to log, not a switch over a union.
 */
public class AccessDeniedException extends RuntimeException {

  private final String reason;

  public AccessDeniedException(String reason, String detail) {
    super(reason + ": " + detail);
    this.reason = reason;
  }

  public AccessDeniedException(Dereferenced.Reason reason, String detail) {
    this(reason.name(), detail);
  }

  /** Which gate said no, and why. */
  public String reason() {
    return reason;
  }
}
