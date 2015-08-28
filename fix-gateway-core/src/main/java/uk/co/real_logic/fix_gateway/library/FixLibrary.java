/*
 * Copyright 2015 Real Logic Ltd.
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

import uk.co.real_logic.aeron.Subscription;
import uk.co.real_logic.agrona.DirectBuffer;
import uk.co.real_logic.agrona.collections.Long2ObjectHashMap;
import uk.co.real_logic.agrona.concurrent.EpochClock;
import uk.co.real_logic.agrona.concurrent.IdleStrategy;
import uk.co.real_logic.agrona.concurrent.SystemEpochClock;
import uk.co.real_logic.agrona.concurrent.SystemNanoClock;
import uk.co.real_logic.fix_gateway.*;
import uk.co.real_logic.fix_gateway.library.session.*;
import uk.co.real_logic.fix_gateway.library.validation.AuthenticationStrategy;
import uk.co.real_logic.fix_gateway.library.validation.MessageValidationStrategy;
import uk.co.real_logic.fix_gateway.messages.ConnectionType;
import uk.co.real_logic.fix_gateway.messages.GatewayError;
import uk.co.real_logic.fix_gateway.session.SessionIdStrategy;
import uk.co.real_logic.fix_gateway.streams.DataSubscriber;
import uk.co.real_logic.fix_gateway.streams.GatewayPublication;
import uk.co.real_logic.fix_gateway.util.AsciiFlyweight;

import java.util.ArrayList;
import java.util.List;

import static java.util.Collections.unmodifiableList;
import static uk.co.real_logic.fix_gateway.messages.ConnectionType.INITIATOR;
import static uk.co.real_logic.fix_gateway.messages.GatewayError.UNABLE_TO_CONNECT;

/**
 * FIX Library instances represent a process where session management,
 * message parsing and API users configure the gateway.
 *
 * Libraries can be run in the same process as the engine, or in a
 * different process.
 *
 * @see uk.co.real_logic.fix_gateway.engine.FixEngine
 */
public class FixLibrary extends GatewayProcess
{
    private final Subscription inboundSubscription;
    private final GatewayPublication outboundPublication;
    private final Long2ObjectHashMap<SessionSubscriber> connectionIdToSession = new Long2ObjectHashMap<>();
    private final List<Session> sessions = new ArrayList<>();
    private final List<Session> unmodifiableSessions = unmodifiableList(sessions);

    private final EpochClock clock;
    private final LibraryConfiguration configuration;
    private final SessionIdStrategy sessionIdStrategy;
    private final Timer timer = new Timer("Session", new SystemNanoClock());
    private final LivenessDetector livenessDetector;
    private final int libraryId;

    private Session incomingSession;

    private SessionConfiguration sessionConfiguration;
    private GatewayError errorType;

    private String errorMessage;

    public FixLibrary(final LibraryConfiguration configuration)
    {
        init(configuration);

        this.configuration = configuration;
        this.sessionIdStrategy = configuration.sessionIdStrategy();
        this.libraryId = configuration.libraryId();

        inboundSubscription = inboundLibraryStreams.subscription();
        outboundPublication = outboundLibraryStreams.gatewayPublication();

        clock = new SystemEpochClock();
        livenessDetector = new LivenessDetector(
            outboundPublication,
            configuration.libraryId(),
            configuration.replyTimeoutInMs(),
            System.currentTimeMillis(),
            false);
    }

    // ------------- Public API -------------

    /**
     * Poll the library all of its component sessions to process any messages
     * and events that have received from or should be sent to the engine.
     *
     * @param fragmentLimit the maximum number of events to read from the engine.
     *
     * @return 0 if no work was performed, > 0 otherwise.
     */
    public int poll(final int fragmentLimit)
    {
        final long timeInMs = clock.time();
        return inboundSubscription.poll(dataSubscriber, fragmentLimit) +
               pollSessions(timeInMs) +
               livenessDetector.poll(timeInMs);
    }

    /**
     * Check if the library is connected to an engine.
     *
     * Note that this refers to whether a library is connected to a FIX Engine,
     * not whether of its sessions are connected.
     *
     * @see Session#isConnected()
     * @see uk.co.real_logic.fix_gateway.engine.FixEngine
     *
     * @return true if the library is connected to an engine, false otherwise.
     */
    public boolean isConnected()
    {
        return livenessDetector.isConnected();
    }

    /**
     * Get the identifier of the library.
     *
     * @return the identifier of the library.
     */
    public int libraryId()
    {
        return libraryId;
    }

    /**
     * Get a list of the currently active sessions.
     *
     * Note: the list is unmodifiable.
     *
     * @return a list of the currently active sessions.
     */
    public List<Session> sessions()
    {
        return unmodifiableSessions;
    }

    /**
     * Close the Library.
     */
    public void close()
    {
        connectionIdToSession.values().forEach(SessionSubscriber::close);
        super.close();
    }

    // ------------- End Public API -------------

    public Session initiate(final SessionConfiguration configuration, final IdleStrategy idleStrategy)
    {
        if (sessionConfiguration != null || incomingSession != null || errorType != null)
        {
            throw new IllegalStateException("You can't initiate a session whilst initiating a session");
        }

        sessionConfiguration = configuration;

        try
        {
            final long replyTimeoutInMs = this.configuration.replyTimeoutInMs();
            final List<String> hosts = configuration.hosts();
            final List<Integer> ports = configuration.ports();
            final int size = hosts.size();
            for (int i = 0; i < size; i++)
            {
                final String host = hosts.get(i);
                final int port = ports.get(i);

                outboundPublication.saveInitiateConnection(
                    libraryId,
                    host,
                    port,
                    configuration.senderCompId(),
                    configuration.senderSubId(),
                    configuration.senderLocationId(),
                    configuration.targetCompId());

                final long latestReplyArrivalTime = clock.time() + replyTimeoutInMs;
                while (incomingSession == null && errorType == null)
                {
                    final int workCount = poll(5);

                    if (clock.time() > latestReplyArrivalTime)
                    {
                        throw new IllegalStateException(String.format(
                            "Failed to receive a reply from the engine within %dms, are you sure its running?",
                            replyTimeoutInMs));
                    }

                    idleStrategy.idle(workCount);
                }

                if (incomingSession != null)
                {
                    final Session session = incomingSession;
                    session.address(host, port);
                    return session;
                }
                else if (errorType != UNABLE_TO_CONNECT)
                {
                    throw new FixGatewayException(String.format("%s: %s", errorType, errorMessage));
                }

                errorType = null;
            }

            throw new FixGatewayException("Unable to connect to any of the addresses specified");
        }
        finally
        {
            sessionConfiguration = null;
            errorType = null;
            incomingSession = null;
        }
    }

    private int pollSessions(final long timeInMs)
    {
        final List<Session> sessions = this.sessions;
        int total = 0;

        for (int i = 0, size = sessions.size(); i < size; i++)
        {
            final Session session = sessions.get(i);
            total += session.poll(timeInMs);
        }

        return total;
    }

    private final DataSubscriber dataSubscriber = new DataSubscriber(new SessionHandler()
    {
        private final AsciiFlyweight asciiFlyweight = new AsciiFlyweight();

        public void onConnect(
            final int libraryId,
            final long connectionId,
            final ConnectionType type,
            final DirectBuffer buffer,
            final int addressOffset,
            final int addressLength)
        {
            if (libraryId == FixLibrary.this.libraryId)
            {
                if (type == INITIATOR)
                {
                    DebugLogger.log("Init Connect: %d, %d\n", connectionId, libraryId);
                    final Session session = initiateSession(connectionId);
                    newSession(connectionId, session);
                    incomingSession = session;
                }
                else
                {
                    DebugLogger.log("Acct Connect: %d, %d\n", connectionId, libraryId);
                    asciiFlyweight.wrap(buffer);
                    final Session session = acceptSession(
                        connectionId, asciiFlyweight.getAscii(addressOffset, addressLength));
                    newSession(connectionId, session);
                }
            }
        }

        public void onLogon(final int libraryId, final long connectionId, final long sessionId)
        {
            if (libraryId == FixLibrary.this.libraryId)
            {
                DebugLogger.log("Library Logon: %d, %d\n", connectionId, sessionId);
                final SessionSubscriber subscriber = connectionIdToSession.get(connectionId);
                if (subscriber != null)
                {
                    subscriber.onLogon(connectionId, sessionId);
                }
            }
        }

        public void onMessage(
            final DirectBuffer buffer,
            final int offset,
            final int length,
            final int libraryId,
            final long connectionId,
            final long sessionId,
            final int messageType,
            final long timestamp)
        {
            if (libraryId == FixLibrary.this.libraryId)
            {
                DebugLogger.log("Received %s\n", buffer, offset, length);
                final SessionSubscriber subscriber = connectionIdToSession.get(connectionId);
                if (subscriber != null)
                {
                    subscriber.onMessage(
                        buffer, offset, length, libraryId, connectionId, sessionId, messageType, timestamp);
                }
            }
        }

        public void onDisconnect(final int libraryId, final long connectionId)
        {
            if (libraryId == FixLibrary.this.libraryId)
            {
                final SessionSubscriber subscriber = connectionIdToSession.remove(connectionId);
                DebugLogger.log("Library Disconnect %s\n", connectionId);
                if (subscriber != null)
                {
                    subscriber.onDisconnect(libraryId, connectionId);
                    sessions.remove(subscriber.session());
                }
            }
        }

        public void onError(final GatewayError errorType, final int libraryId, final String message)
        {
            if (libraryId == FixLibrary.this.libraryId)
            {
                FixLibrary.this.errorType = errorType;
                FixLibrary.this.errorMessage = message;
            }
        }

        public void onApplicationHeartbeat(final int libraryId)
        {
            if (libraryId == FixLibrary.this.libraryId)
            {
                livenessDetector.onHeartbeat(clock.time());
            }
        }
    });

    private void newSession(final long connectionId, final Session session)
    {
        final AuthenticationStrategy authenticationStrategy = configuration.authenticationStrategy();
        final MessageValidationStrategy validationStrategy = configuration.messageValidationStrategy();
        final SessionParser parser = new SessionParser(
            session, sessionIdStrategy, authenticationStrategy, validationStrategy);
        final SessionHandler handler = configuration.newSessionHandler().onConnect(session);
        final SessionSubscriber subscriber = new SessionSubscriber(parser, session, handler, timer);
        connectionIdToSession.put(connectionId, subscriber);
        sessions.add(session);
    }

    private Session initiateSession(final long connectionId)
    {
        final Object key = sessionIdStrategy.onInitiatorLogon(
            sessionConfiguration.senderCompId(), sessionConfiguration.senderSubId(),
            sessionConfiguration.senderLocationId(), sessionConfiguration.targetCompId());
        final int defaultInterval = configuration.defaultHeartbeatInterval();
        final GatewayPublication publication = outboundLibraryStreams.gatewayPublication();

        return new InitiatorSession(
            defaultInterval,
            connectionId,
            clock,
            sessionProxy(connectionId).setupSession(-1, key),
            publication,
            sessionIdStrategy,
            configuration.beginString(),
            configuration.sendingTimeWindow(),
            fixCounters.receivedMsgSeqNo(connectionId),
            fixCounters.sentMsgSeqNo(connectionId),
            sessionConfiguration.username(),
            sessionConfiguration.password(),
            libraryId,
            sessionConfiguration.bufferSize());
    }

    private Session acceptSession(final long connectionId, final String address)
    {
        final GatewayPublication publication = outboundLibraryStreams.gatewayPublication();
        final int defaultInterval = configuration.defaultHeartbeatInterval();
        final int split = address.lastIndexOf(':');
        final int start = address.startsWith("/") ? 1 : 0;
        final String host = address.substring(start, split);
        final int port = Integer.parseInt(address.substring(split + 1));

        return new AcceptorSession(
            defaultInterval,
            connectionId,
            clock,
            sessionProxy(connectionId),
            publication,
            sessionIdStrategy,
            configuration.beginString(),
            configuration.sendingTimeWindow(),
            fixCounters.receivedMsgSeqNo(connectionId),
            fixCounters.sentMsgSeqNo(connectionId),
            libraryId,
            configuration.acceptorSessionBufferSize())
           .address(host, port);
    }

    private SessionProxy sessionProxy(final long connectionId)
    {
        return new SessionProxy(
            configuration.encoderBufferSize(), outboundLibraryStreams.gatewayPublication(), sessionIdStrategy,
            configuration.sessionCustomisationStrategy(), System::currentTimeMillis, connectionId, libraryId);
    }
}
