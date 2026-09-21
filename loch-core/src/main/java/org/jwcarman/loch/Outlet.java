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

import org.jwcarman.codec.spi.TypeRef;

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
 * <p><b>Narrowed by type as well as by label</b>, because a label alone is a coarse instrument. The
 * day somebody holds a session token at the same label as an invoice -- not maliciously, just
 * without thinking about it -- a door that accepts any type reads it. Saying what comes out of a
 * door is a second, independent narrowing, and the authority this confers is smaller for it.
 *
 * <p>It also moves the type check left. {@code Outlet<Invoice>.read(handleToSomeMail)} does not
 * compile, where naming a destination at a call site accepted any handle and refused at runtime.
 * The runtime check remains and is not redundant: a {@link HandleId} that arrived as text can be
 * given any type by {@link Handle#of}, so what the store actually wrote is still the only ground
 * truth, and it is still what gets compared.
 *
 * @param <T> the type of value this outlet will hand over, and no other
 */
public interface Outlet<T> {

  /** What comes out of it. */
  TypeRef<T> type();

  /**
   * Reads the value, if its label is at or below what this outlet accepts.
   *
   * <p>Returns a refusal rather than throwing, because being turned away is an ordinary outcome
   * that calling code is expected to handle: show the handle instead, ask for approval, take the
   * other branch.
   *
   * @throws IllegalStateException if this outlet was never bound to a loch
   */
  Dereferenced<T> read(Handle<T> held);

  /**
   * The same, with attributes the caller is contributing to the decision.
   *
   * <p>Only the keys named by {@code callerMayContribute} are honoured, and ambient context always
   * wins where the two disagree. A caller that could name its own tenant here would have defeated
   * the whole arrangement.
   */
  Dereferenced<T> read(Handle<T> held, AccessContext context);
}
