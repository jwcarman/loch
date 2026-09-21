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

import java.util.function.Supplier;

/**
 * Where the access happening right now comes from.
 *
 * <p>Identity is established at the edge -- a request, a message, a session -- and needed at the
 * gate, which may be many layers down. Threading an {@link AccessContext} parameter through all of
 * them would make the safety feature the most annoying thing in the codebase, and annoying safety
 * features get routed around. So an application says once where the answer lives, and the store
 * asks each time it needs to know.
 *
 * <p>A {@code ThreadLocal}, a {@code ScopedValue}, Spring's {@code SecurityContextHolder} -- a
 * store has no opinion about how a request scope works.
 *
 * <p><b>Whatever this returns is taken as fact.</b> It is the one input a caller cannot argue with:
 * the ceiling of every door is evaluated against it, and a caller that could influence it could let
 * itself through. So it has to read from somewhere the caller does not control -- a verified token,
 * a header a gateway sets and strips from client traffic -- and never from anything the request
 * body carries.
 *
 * <p>Returning an empty context is allowed and means nobody is acting. That is not a way through:
 * an empty context has no tenant, no role and no clearance, so a ceiling that asks for any of them
 * refuses, and a source whose label needs one refuses to write.
 */
/**
 * A {@link Supplier} with a name on it. The contract is the JDK's, so anything that already
 * supplies an {@code AccessContext} satisfies it; the name exists so a container can wire one by
 * type, and so a reviewer reading a constructor parameter can tell what it is for.
 */
@FunctionalInterface
public interface AccessContextProvider extends Supplier<AccessContext> {

  /** Nobody is acting, ever. The default, and the right one for a store with no notion of who. */
  static AccessContextProvider none() {
    return AccessContext::empty;
  }
}
