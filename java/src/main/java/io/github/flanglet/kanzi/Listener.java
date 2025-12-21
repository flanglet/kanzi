/*
 * Kanzi is a modern, modular, portable, and efficient lossless data compressor.
 *
 * Copyright (C) 2025 Frederic Langlet
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 *
 * You may obtain a copy of the License at
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.github.flanglet.kanzi;

/**
 * The {@code Listener} interface defines a contract for objects that need to handle and process
 * events.
 * <p>
 * Classes that implement this interface are expected to provide an implementation of the
 * {@code processEvent} method, which will be invoked when an event occurs.
 * </p>
 *
 */
public interface Listener {

  /**
   * Processes the given event.
   * <p>
   * This method will be called whenever an event occurs that the listener is interested in.
   * Implementations of this method should define how to handle the event.
   * </p>
   *
   * @param evt The event to be processed. Cannot be {@code null}.
   */
  public void processEvent(Event evt);
}
