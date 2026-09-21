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
 * Why a line in the trail says what it says.
 *
 * <p>Two halves of one answer, kept together because they are stored differently and must not be
 * confused. The <b>code</b> goes to disk in the clear, so that "how many refusals above a ceiling
 * this hour" is a question the trail can answer without decrypting anything. The <b>detail</b> --
 * which label, against which ceiling -- is label-shaped, so it is protected exactly like a label
 * is.
 *
 * <p>They travel as one value rather than as two adjacent string parameters. The alternative was an
 * eight-argument call with {@code null, null} in the middle of it, where putting an explanation in
 * the queryable column would describe every value in the system to anyone who could read the table,
 * and nothing would have complained.
 *
 * @param code names a rule, never a value
 * @param detail names a value, so it is never in the clear
 */
record Why(String code, String detail) {

  private static final Why NOTHING = new Why(null, null);

  /** A line that has nothing to explain: an ordinary, permitted operation. */
  static Why nothing() {
    return NOTHING;
  }

  /** A code with no explanation beyond itself. */
  static Why of(String code) {
    return new Why(code, null);
  }

  /** A code and the part of the answer that must not be in the clear. */
  static Why of(String code, String detail) {
    return new Why(code, detail);
  }
}
