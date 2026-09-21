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
 * Where the record goes.
 *
 * <p><b>An access that cannot be audited does not happen.</b> If this throws, the gate refuses -- a
 * governance control whose log is silently dropping entries is worse than no control, because it
 * still produces the report. An application that would rather proceed can say so by catching inside
 * its own implementation, which makes that a decision somebody wrote down.
 *
 * <p>Implementations should be quick and should not call back into the loch.
 */
@FunctionalInterface
public interface Auditor {

  void record(AuditRecord record);
}
