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

/** Thrown by {@link Dereferenced#orThrow()} when the gate refused. */
public class AccessDeniedException extends RuntimeException {

  private final transient Dereferenced.Reason reason;

  public AccessDeniedException(Dereferenced.Reason reason, String detail) {
    super(reason + ": " + detail);
    this.reason = reason;
  }

  public Dereferenced.Reason reason() {
    return reason;
  }
}
