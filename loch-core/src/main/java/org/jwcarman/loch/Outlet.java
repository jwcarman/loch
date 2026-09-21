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
 * The authority to take plaintext out of a loch at one particular ceiling.
 *
 * <p>The counterpart to {@link Inlet}, and named from the loch's point of view rather than the
 * data's: values enter through an inlet and leave through an outlet. That frame is fixed, which
 * source-and-sink is not -- two flows cross here, plaintext and handles, and a reader cannot tell
 * which one a "source" belongs to.
 *
 * <p><b>Minted during configuration, obtained only by being handed one.</b> Before this existed, a
 * caller named the door it wanted: {@code loch.dereference(handle, DestinationId.of("payment
 * processor"), ctx)}. That factory is public, so any class could construct the name of any door and
 * present it, and the only thing between an arbitrary caller and cardholder plaintext was a ceiling
 * re-checked on every call. Holding the outlet is now the authority, and there is no method that
 * trades a {@link DestinationId} for one.
 *
 * <p>The ceiling is still checked, and still may depend on who is asking -- an outlet is a door,
 * not a bypass. What changed is that reaching the door at all is no longer something a caller can
 * decide for itself.
 *
 * <p>Not generic in the value type, because a ceiling is a statement about labels and says nothing
 * about types. One outlet serves every kind of value that may pass it.
 */
public interface Outlet {

  /** What this door is called in the manifest and in the record. */
  DestinationId id();

  /**
   * Reads the value, if its label is at or below what this outlet accepts.
   *
   * <p>Returns a refusal rather than throwing, because being turned away is an ordinary outcome
   * that calling code is expected to handle: show the handle instead, ask for approval, take the
   * other branch.
   *
   * @throws IllegalStateException if this outlet was never bound to a loch
   */
  <T> Dereferenced<T> read(Handle<T> held);

  /**
   * The same, with attributes the caller is contributing to the decision.
   *
   * <p>Only the keys named by {@code callerMayContribute} are honoured, and ambient context always
   * wins where the two disagree. A caller that could name its own tenant here would have defeated
   * the whole arrangement.
   */
  <T> Dereferenced<T> read(Handle<T> held, AccessContext context);
}
