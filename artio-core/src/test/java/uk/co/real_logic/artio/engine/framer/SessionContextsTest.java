/*
 * Copyright 2015-2020 Real Logic Limited., Monotonic Ltd.
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

import org.agrona.ErrorHandler;
import org.agrona.IoUtil;
import org.agrona.concurrent.AtomicBuffer;
import org.agrona.concurrent.UnsafeBuffer;
import org.junit.Test;
import uk.co.real_logic.artio.FileSystemCorruptionException;
import uk.co.real_logic.artio.builder.LogonEncoder;
import uk.co.real_logic.artio.dictionary.FixDictionary;
import uk.co.real_logic.artio.engine.MappedFile;
import uk.co.real_logic.artio.session.CompositeKey;
import uk.co.real_logic.artio.session.SessionIdStrategy;
import uk.co.real_logic.artio.util.MutableAsciiBuffer;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.stream.IntStream;

import static java.util.stream.Collectors.toList;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.mockito.Mockito.*;
import static uk.co.real_logic.artio.engine.framer.SessionContexts.DUPLICATE_SESSION;
import static uk.co.real_logic.artio.engine.framer.SessionContexts.LOWEST_VALID_SESSION_ID;

public class SessionContextsTest
{
    private static final int BUFFER_SIZE = 8 * 1024;

    private long time = System.currentTimeMillis();
    private ErrorHandler errorHandler = mock(ErrorHandler.class);
    private AtomicBuffer buffer = new UnsafeBuffer(ByteBuffer.allocate(BUFFER_SIZE));
    private MappedFile mappedFile = mock(MappedFile.class);
    private SessionIdStrategy idStrategy = SessionIdStrategy.senderAndTarget();
    private SessionContexts sessionContexts = newSessionContexts(buffer);
    private MutableAsciiBuffer asciiBuffer = new MutableAsciiBuffer(ByteBuffer.allocate(BUFFER_SIZE));
    private LogonEncoder logonEncoder = new LogonEncoder();
    private FixDictionary fixDictionary = FixDictionary.of(FixDictionary.findDefault());

    private CompositeKey aSession = idStrategy.onInitiateLogon("a", null, null, "b", null, null);
    private CompositeKey bSession = idStrategy.onInitiateLogon("b", null, null, "a", null, null);
    private CompositeKey cSession = idStrategy.onInitiateLogon("c", null, null, "c", null, null);

    @Test
    public void sessionContextsAreUnique()
    {
        assertNotEquals(sessionContexts.onLogon(aSession, fixDictionary), sessionContexts.onLogon(bSession,
            fixDictionary));
    }

    @Test
    public void findsDuplicateSessions()
    {
        sessionContexts.onLogon(aSession, fixDictionary);

        assertEquals(SessionContexts.DUPLICATE_SESSION, sessionContexts.onLogon(aSession, fixDictionary));
    }

    @Test
    public void handsOutSameSessionContextAfterDisconnect()
    {
        final SessionContext sessionContext = sessionContexts.onLogon(aSession, fixDictionary);
        sessionContexts.onDisconnect(sessionContext.sessionId());

        assertValuesEqual(sessionContext, sessionContexts.onLogon(aSession, fixDictionary));
    }

    @Test
    public void persistsSessionContextsOverARestart()
    {
        final SessionContext bContext = sessionContexts.onLogon(bSession, fixDictionary);
        final SessionContext aContext = sessionContexts.onLogon(aSession, fixDictionary);

        bContext.onSequenceReset(time);
        aContext.onSequenceReset(time);

        final SessionContexts sessionContextsAfterRestart = newSessionContexts(buffer);
        final SessionContext reloadedAContext = sessionContextsAfterRestart.onLogon(aSession, fixDictionary);
        final SessionContext reloadedBContext = sessionContextsAfterRestart.onLogon(bSession, fixDictionary);

        assertValuesEqual(aContext, reloadedAContext);
        assertValuesEqual(bContext, reloadedBContext);

        assertEquals(time, reloadedAContext.lastSequenceResetTime());
        assertEquals(time, reloadedBContext.lastSequenceResetTime());
    }

    @Test
    public void continuesIncrementingSessionContextsAfterRestart()
    {
        final SessionContext bContext = sessionContexts.onLogon(bSession, fixDictionary);
        final SessionContext aContext = sessionContexts.onLogon(aSession, fixDictionary);

        final SessionContexts sessionContextsAfterRestart = newSessionContexts(buffer);

        final SessionContext cContext = sessionContextsAfterRestart.onLogon(cSession, fixDictionary);
        assertValidSessionId(cContext.sessionId());
        assertNotEquals("C is a duplicate of A", aContext, cContext);
        assertNotEquals("C is a duplicate of B", bContext, cContext);
    }

    @Test
    public void checksFileCorruption()
    {
        sessionContexts.onLogon(bSession, fixDictionary);
        sessionContexts.onLogon(aSession, fixDictionary);

        // corrupt buffer
        buffer.putBytes(8, new byte[1024]);

        newSessionContexts(buffer);

        verify(errorHandler).onError(any(FileSystemCorruptionException.class));
    }

    @Test(expected = IllegalArgumentException.class)
    public void validateSizeOfBuffer()
    {
        final AtomicBuffer buffer = new UnsafeBuffer(ByteBuffer.allocate(1024));
        newSessionContexts(buffer);
    }

    @Test
    public void wrapsOverSectorBoundaries()
    {
        final int requiredNumberOfWritesToSpanSector = 45;

        final List<CompositeKey> keys = IntStream
            .range(0, requiredNumberOfWritesToSpanSector)
            .mapToObj((i) -> idStrategy.onInitiateLogon("b" + i, null, null, "a" + i, null, null))
            .collect(toList());

        final List<SessionContext> contexts = keys
            .stream()
            .map(compositeKey -> sessionContexts.onLogon(compositeKey, fixDictionary))
            .peek(sessionContext -> sessionContext.onSequenceReset(time))
            .collect(toList());

        // Test an update of something not at the tail of the buffer.
        final SessionContext firstContext = contexts.get(0);
        firstContext.onSequenceReset(time);

        final SessionContexts contextsAfterRestart = newSessionContexts(buffer);
        IntStream
            .range(0, requiredNumberOfWritesToSpanSector)
            .forEach((i) -> assertValuesEqual(contexts.get(i),
            contextsAfterRestart.onLogon(keys.get(i), fixDictionary)));
    }

    @Test
    public void resetsSessionContexts()
    {
        final SessionContext aContext = sessionContexts.onLogon(aSession, fixDictionary);
        sessionContexts.onDisconnect(aContext.sessionId());

        sessionContexts.reset(null);

        assertSessionContextsReset(aContext, sessionContexts);

        verifyNoBackUp();
    }

    @Test
    public void resetsSessionContextsFile()
    {
        final SessionContext aContext = sessionContexts.onLogon(aSession, fixDictionary);
        sessionContexts.onDisconnect(aContext.sessionId());

        sessionContexts.reset(null);

        final SessionContexts sessionContextsAfterRestart = newSessionContexts(buffer);

        assertSessionContextsReset(aContext, sessionContextsAfterRestart);

        verifyNoBackUp();
    }

    @Test
    public void copiesOldSessionContextFile() throws IOException
    {
        final File backupLocation = File.createTempFile("sessionContexts", "tmp");
        try
        {
            final SessionContext aContext = sessionContexts.onLogon(aSession, fixDictionary);
            sessionContexts.onDisconnect(aContext.sessionId());

            final byte[] oldData = new byte[BUFFER_SIZE];
            buffer.getBytes(0, oldData);

            sessionContexts.reset(backupLocation);

            verify(mappedFile).transferTo(backupLocation);
        }
        finally
        {
            IoUtil.deleteIfExists(backupLocation);
        }
    }

    @Test
    public void doesNotReuseExistingSessionIdsForDistinctCompositeKeys()
    {
        sessionContexts.onLogon(aSession, fixDictionary);
        sessionContexts.onLogon(bSession, fixDictionary); // bump counter

        logonWithSenderAndTarget(aSession.localCompId(), aSession.remoteCompId());

        final SessionContext cContext = sessionContexts.onLogon(cSession, fixDictionary);

        assertNotEquals(DUPLICATE_SESSION, cContext);
        assertEquals(3, cContext.sessionId());
    }

    private void verifyNoBackUp()
    {
        verify(mappedFile, never()).transferTo(any());
    }

    private void assertSessionContextsReset(final SessionContext aContext, final SessionContexts sessionContexts)
    {
        final SessionContext bContext = sessionContexts.onLogon(bSession, fixDictionary);
        final SessionContext newAContext = sessionContexts.onLogon(aSession, fixDictionary);
        assertValidSessionId(bContext.sessionId());
        assertValidSessionId(newAContext.sessionId());
        assertEquals("Session Contexts haven't been reset", aContext, bContext);
        assertNotEquals("Session Contexts haven't been reset", aContext, newAContext);
    }

    private void assertValidSessionId(final long cId)
    {
        assertThat(cId, greaterThanOrEqualTo(LOWEST_VALID_SESSION_ID));
    }

    private SessionContexts newSessionContexts(final AtomicBuffer buffer)
    {
        when(mappedFile.buffer()).thenReturn(buffer);
        return new SessionContexts(mappedFile, idStrategy, errorHandler);
    }

    private void assertValuesEqual(
        final SessionContext sessionContext,
        final SessionContext secondSessionContext)
    {
        assertEquals(sessionContext, secondSessionContext);
        assertEquals(sessionContext.sequenceIndex(), secondSessionContext.sequenceIndex());
    }

    private long logonWithSenderAndTarget(final String senderCompID, final String targetCompID)
    {
        logonEncoder.header()
            .sendingTime(new byte[] {0})
            .senderCompID(senderCompID)
            .targetCompID(targetCompID);
        return logonEncoder.encryptMethod(0).heartBtInt(0).encode(asciiBuffer, 0);
    }
}
