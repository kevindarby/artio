/*
 * Copyright 2015-2016 Real Logic Ltd.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package uk.co.real_logic.fix_gateway.library;

/**
 * Callback handler to let clients know when the gateway has sent a message.
 *
 * This can be correlated against the position returned by the session's send method.
 *
 * @see uk.co.real_logic.fix_gateway.session.Session#send(uk.co.real_logic.fix_gateway.builder.MessageEncoder)
 */
@FunctionalInterface
public interface SentPositionHandler
{
    /**
     * Called when one or more messages has been sent.
     *
     * @param position the position that corresponds to what has been sent via TCP.
     */
    void onSendCompleted(final long position);
}