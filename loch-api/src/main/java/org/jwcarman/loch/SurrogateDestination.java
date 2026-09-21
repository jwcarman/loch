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
 * Somewhere values may go, and the only thing that mints a reader for it.
 *
 * <p>A destination is a subsystem -- a payment processor, a shipping service, a vendor model --
 * together with both restrictions on reaching it. The ceiling says which labels may arrive; the
 * declared types say what it reads. Both are settled when it is declared and neither can be widened
 * afterwards.
 *
 * <p>Which is what makes a reader a view rather than a grant. {@link #reading} adds no authority:
 * it enforces the ceiling that was already there, and refuses a type that was not declared. So a
 * reader can be minted where it is needed and thrown away, and <b>a destination is safe to hand to
 * the service that talks to that subsystem</b>. It holds no configuration and cannot declare a new
 * door -- only look through this one.
 *
 * <p>It is still more authority than any one reader, because it can constitute a reader for every
 * type it was declared to read. Hand out the destination when a service talks to the whole
 * subsystem; hand out a reader when it needs one kind of value.
 */
public interface SurrogateDestination {

  /**
   * A reader for one of the types this destination was declared to read.
   *
   * @throws IllegalStateException if that type was not declared here
   */
  <T> Reveal<T> reading(SurrogateType<T> type);
}
