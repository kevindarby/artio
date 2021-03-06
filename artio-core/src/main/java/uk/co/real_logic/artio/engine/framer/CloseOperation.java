/*
 * Copyright 2019 Adaptive Financial Consulting Ltd.
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

import uk.co.real_logic.artio.messages.SessionState;
import uk.co.real_logic.artio.protocol.GatewayPublication;
import uk.co.real_logic.artio.session.InternalSession;

import java.util.List;

import static io.aeron.Publication.BACK_PRESSURED;

class CloseOperation implements Continuation
{
    private final GatewayPublication inboundPublication;
    private final List<LiveLibraryInfo> libraries;
    private final List<GatewaySession> gatewaySessions;
    private final ReceiverEndPoints receiverEndPoints;
    private final StartCloseCommand command;

    private Step step = Step.CLOSING_NOT_LOGGED_ON_RECEIVER_END_POINTS;
    private int libraryIndex = 0;
    private int gatewaySessionIndex = 0;

    CloseOperation(
        final GatewayPublication inboundPublication,
        final List<LiveLibraryInfo> libraries,
        final List<GatewaySession> gatewaySessions,
        final ReceiverEndPoints receiverEndPoints,
        final StartCloseCommand command)
    {
        this.inboundPublication = inboundPublication;
        this.libraries = libraries;
        this.gatewaySessions = gatewaySessions;
        this.receiverEndPoints = receiverEndPoints;
        this.command = command;
    }

    public long attempt()
    {
        switch (step)
        {
            case CLOSING_NOT_LOGGED_ON_RECEIVER_END_POINTS:
            {
                receiverEndPoints.closeRequiredPollingEndPoints();

                step = Step.LOGGING_OUT_LIBRARIES;
                return BACK_PRESSURED;
            }

            case LOGGING_OUT_LIBRARIES:
            {
                return logOutLibraries();
            }

            case LOGGING_OUT_GATEWAY_SESSIONS:
            {
                return logOutGatewaySessions();
            }
            case AWAITING_DISCONNECTS:
            {
                return awaitDisconnects();
            }

            default:
                // Impossible
                return 1;
        }
    }

    private long logOutLibraries()
    {
        final GatewayPublication inboundPublication = this.inboundPublication;
        final List<LiveLibraryInfo> libraries = this.libraries;
        final int libraryCount = libraries.size();

        while (libraryIndex < libraryCount)
        {
            final LiveLibraryInfo library = libraries.get(libraryIndex);
            final long position = inboundPublication.saveEndOfDay(library.libraryId());
            if (position < 0)
            {
                return position;
            }

            libraryIndex++;
        }

        step = Step.LOGGING_OUT_GATEWAY_SESSIONS;
        return BACK_PRESSURED;
    }

    // NB: if library is in the process of being acquired during an end of day operation then its sessions
    // Will be logged out at the point of acquisition.
    private long logOutGatewaySessions()
    {
        final List<GatewaySession> gatewaySessions = this.gatewaySessions;
        final int gatewaySessionCount = gatewaySessions.size();

        while (gatewaySessionIndex < gatewaySessionCount)
        {
            final GatewaySession gatewaySession = gatewaySessions.get(gatewaySessionIndex);
            final InternalSession session = gatewaySession.session();
            if (session != null)
            {
                final SessionState state = session.state();
                switch (state)
                {
                    case SENT_LOGON:
                    case ACTIVE:
                    case AWAITING_LOGOUT:
                    case LOGGING_OUT_AND_DISCONNECTING:
                    case LOGGING_OUT:
                    {
                        final long position = session.logoutAndDisconnect();
                        if (position < 0)
                        {
                            return position;
                        }

                        break;
                    }

                    case CONNECTED:
                    case CONNECTING:
                    case DISCONNECTING:
                    {
                        final long position = session.requestDisconnect();
                        if (position < 0)
                        {
                            return position;
                        }

                        break;
                    }

                    case DISCONNECTED:
                    case DISABLED:
                    default:
                        // deliberately blank
                        break;
                }
            }

            gatewaySessionIndex++;
        }

        step = Step.AWAITING_DISCONNECTS;
        return BACK_PRESSURED;
    }

    private long awaitDisconnects()
    {
        if (receiverEndPoints.size() > 0)
        {
            return BACK_PRESSURED;
        }

        command.success();

        return 1;
    }

    private enum Step
    {
        CLOSING_NOT_LOGGED_ON_RECEIVER_END_POINTS,
        LOGGING_OUT_LIBRARIES,
        LOGGING_OUT_GATEWAY_SESSIONS,
        AWAITING_DISCONNECTS
    }
}
