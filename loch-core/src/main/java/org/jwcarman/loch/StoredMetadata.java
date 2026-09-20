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
 * Everything about a held value except the value.
 *
 * <p>Kept separate on purpose. Rendering a handle, checking a label and answering "does this exist"
 * are all common, and none of them should decrypt and decode a payload to find out. A durable store
 * reads two columns for this and never touches the third.
 *
 * @param typeName what the value was stored as, compared against what a handle claims. A name
 *     rather than a reconstructed type, because comparing what was written to what is asked for
 *     needs no parser and cannot be fooled by one.
 */
public record StoredMetadata<A>(String typeName, A attribution, Lineage lineage) {}
