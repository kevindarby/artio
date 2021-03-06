/*
 * Copyright 2015-2020 Real Logic Limited.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package uk.co.real_logic.artio.engine.framer;

import uk.co.real_logic.artio.dictionary.FixDictionary;
import uk.co.real_logic.artio.session.Session;

/**
 * Context information about a FIX session, that persists across restarts.
 */
class SessionContext
{
    static final int UNKNOWN_SEQUENCE_INDEX = -1;

    private final long sessionId;
    private final SessionContexts sessionContexts;

    private final int filePosition;

    // onSequenceReset() will be called upon logon or not depending upon whether this is a persistent
    // session or not.
    private int sequenceIndex;

    private long lastLogonTime;
    private long lastSequenceResetTime;
    private FixDictionary lastFixDictionary;

    SessionContext(
        final long sessionId,
        final int sequenceIndex,
        final long lastLogonTime,
        final long lastSequenceResetTime,
        final SessionContexts sessionContexts,
        final int filePosition,
        final FixDictionary lastFixDictionary)
    {
        this.sessionId = sessionId;
        this.sequenceIndex = sequenceIndex;
        lastLogonTime(lastLogonTime);
        this.lastSequenceResetTime = lastSequenceResetTime;
        this.sessionContexts = sessionContexts;
        this.filePosition = filePosition;
        this.lastFixDictionary = lastFixDictionary;
    }

    private void lastLogonTime(final long lastLogonTime)
    {
        this.lastLogonTime = lastLogonTime;
    }

    void onSequenceReset(final long resetTime)
    {
        lastSequenceResetTime = resetTime;
        sequenceIndex++;
        save();
    }

    void updateAndSaveFrom(final Session session)
    {
        updateFrom(session);
        save();
    }

    private void save()
    {
        // NB: we deliberately don't update the fix dictionary as this can't change within
        // a connection
        sessionContexts.updateSavedData(
            filePosition, sequenceIndex, lastLogonTime, lastSequenceResetTime);
    }

    void updateFrom(final Session session)
    {
        sequenceIndex = session.sequenceIndex();
        lastLogonTime(session.lastLogonTime());
        lastSequenceResetTime = session.lastSequenceResetTime();
    }

    void onLogon(final boolean resetSeqNum, final long time, final FixDictionary fixDictionary)
    {
        lastFixDictionary = fixDictionary;
        lastLogonTime(time);
        // increment if we're going to reset the sequence number or if it's persistent
        // sequence numbers and it's the first time we're logging on.
        if (resetSeqNum || sequenceIndex == SessionContext.UNKNOWN_SEQUENCE_INDEX)
        {
            onSequenceReset(time);
        }
        else
        {
            // onSequenceReset also saves.
            save();
        }
    }

    int sequenceIndex()
    {
        return sequenceIndex;
    }

    long sessionId()
    {
        return sessionId;
    }

    public long lastSequenceResetTime()
    {
        return lastSequenceResetTime;
    }

    public long lastLogonTime()
    {
        return lastLogonTime;
    }

    public FixDictionary lastFixDictionary()
    {
        return lastFixDictionary;
    }

    public boolean equals(final Object o)
    {
        if (this == o)
        {
            return true;
        }

        if (o == null || getClass() != o.getClass())
        {
            return false;
        }

        final SessionContext that = (SessionContext)o;

        return sessionId == that.sessionId;
    }

    public int hashCode()
    {
        return (int)(sessionId ^ (sessionId >>> 32));
    }

    public String toString()
    {
        return "SessionContext{" +
            "sessionId=" + sessionId +
            ", sequenceIndex=" + sequenceIndex +
            '}';
    }
}
