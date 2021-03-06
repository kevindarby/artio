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
package uk.co.real_logic.artio.session;

import io.aeron.logbuffer.ControlledFragmentHandler.Action;
import org.agrona.AsciiNumberFormatException;
import org.agrona.DirectBuffer;
import org.agrona.ErrorHandler;
import org.agrona.LangUtil;
import uk.co.real_logic.artio.FixGatewayException;
import uk.co.real_logic.artio.builder.Decoder;
import uk.co.real_logic.artio.decoder.*;
import uk.co.real_logic.artio.dictionary.FixDictionary;
import uk.co.real_logic.artio.dictionary.SessionConstants;
import uk.co.real_logic.artio.dictionary.generation.CodecUtil;
import uk.co.real_logic.artio.fields.RejectReason;
import uk.co.real_logic.artio.fields.UtcTimestampDecoder;
import uk.co.real_logic.artio.messages.SessionState;
import uk.co.real_logic.artio.util.AsciiBuffer;
import uk.co.real_logic.artio.util.MutableAsciiBuffer;
import uk.co.real_logic.artio.validation.MessageValidationStrategy;

import java.util.Arrays;

import static io.aeron.logbuffer.ControlledFragmentHandler.Action.CONTINUE;
import static uk.co.real_logic.artio.builder.Validation.CODEC_VALIDATION_ENABLED;
import static uk.co.real_logic.artio.builder.Validation.isValidMsgType;
import static uk.co.real_logic.artio.dictionary.SessionConstants.*;
import static uk.co.real_logic.artio.dictionary.generation.CodecUtil.MISSING_INT;
import static uk.co.real_logic.artio.dictionary.generation.CodecUtil.MISSING_LONG;
import static uk.co.real_logic.artio.messages.SessionState.AWAITING_LOGOUT;
import static uk.co.real_logic.artio.messages.SessionState.DISCONNECTED;
import static uk.co.real_logic.artio.session.Session.UNKNOWN;

public class SessionParser
{
    private final AsciiBuffer asciiBuffer = new MutableAsciiBuffer();
    private final UtcTimestampDecoder timestampDecoder = new UtcTimestampDecoder();

    private AbstractLogonDecoder logon;
    private AbstractLogoutDecoder logout;
    private AbstractRejectDecoder reject;
    private AbstractTestRequestDecoder testRequest;
    private SessionHeaderDecoder header;
    private AbstractSequenceResetDecoder sequenceReset;
    private AbstractHeartbeatDecoder heartbeat;

    private final boolean validateCompIdsOnEveryMessage;
    private char[] firstSenderCompId;
    private char[] firstSenderSubId;
    private char[] firstSenderLocationId;
    private char[] firstTargetCompId;
    private char[] firstTargetSubId;
    private char[] firstTargetLocationId;

    private final Session session;
    private final MessageValidationStrategy validationStrategy;
    private ErrorHandler errorHandler;

    public SessionParser(
        final Session session,
        final MessageValidationStrategy validationStrategy,
        final ErrorHandler errorHandler, // nullable
        final boolean validateCompIdsOnEveryMessage)
    {
        this.session = session;
        this.validationStrategy = validationStrategy;
        this.errorHandler = errorHandler;

        this.validateCompIdsOnEveryMessage = validateCompIdsOnEveryMessage;
    }

    public void fixDictionary(final FixDictionary fixDictionary)
    {
        logon = fixDictionary.makeLogonDecoder();
        logout = fixDictionary.makeLogoutDecoder();
        reject = fixDictionary.makeRejectDecoder();
        testRequest = fixDictionary.makeTestRequestDecoder();
        header = fixDictionary.makeHeaderDecoder();
        sequenceReset = fixDictionary.makeSequenceResetDecoder();
        heartbeat = fixDictionary.makeHeartbeatDecoder();
    }

    public static String username(final AbstractLogonDecoder logon)
    {
        return logon.supportsUsername() ? logon.usernameAsString() : null;
    }

    public static String password(final AbstractLogonDecoder logon)
    {
        return logon.supportsPassword() ? logon.passwordAsString() : null;
    }

    public Action onMessage(
        final DirectBuffer buffer,
        final int offset,
        final int length,
        final long messageType,
        final long sessionId)
    {
        asciiBuffer.wrap(buffer);

        final Action action;

        try
        {
            if (messageType == LOGON_MESSAGE_TYPE)
            {
                action = onLogon(offset, length);
            }
            else if (messageType == LOGOUT_MESSAGE_TYPE)
            {
                action = onLogout(offset, length);
            }
            else if (messageType == HEARTBEAT_MESSAGE_TYPE)
            {
                action = onHeartbeat(offset, length);
            }
            else if (messageType == REJECT_MESSAGE_TYPE)
            {
                action = onReject(offset, length);
            }
            else if (messageType == TEST_REQUEST_MESSAGE_TYPE)
            {
                action = onTestRequest(offset, length);
            }
            else if (messageType == SEQUENCE_RESET_MESSAGE_TYPE)
            {
                action = onSequenceReset(offset, length);
            }
            else
            {
                action = onAnyOtherMessage(offset, length);
            }

            // Consider admin messages processed when they've been received by the session logic
            session.updateLastMessageProcessed();

            return action;
        }
        catch (final AsciiNumberFormatException e)
        {
            // We should just ignore the message when the first three fields are out of order.
            if (e.getMessage().contains("'^' is not a valid digit"))
            {
                return CONTINUE;
            }
            else
            {
                return rejectAndHandleExceptionalMessage(e, messageType);
            }
        }
        catch (final Exception e)
        {
            return rejectAndHandleExceptionalMessage(e, messageType);
        }
    }

    private Action rejectAndHandleExceptionalMessage(final Exception e, final long messageType)
    {
        final Action action = rejectExceptionalMessage(messageType);

        if (action == CONTINUE)
        {
            onError(e);
        }

        return action;
    }

    private Action rejectExceptionalMessage(final long messageType)
    {
        if (messageType == LOGON_MESSAGE_TYPE)
        {
            return onExceptionalMessage(logon.header());
        }
        else if (messageType == LOGOUT_MESSAGE_TYPE)
        {
            return onExceptionalMessage(logout.header());
        }
        else if (messageType == HEARTBEAT_MESSAGE_TYPE)
        {
            return onExceptionalMessage(heartbeat.header());
        }
        else if (messageType == REJECT_MESSAGE_TYPE)
        {
            return onExceptionalMessage(reject.header());
        }
        else if (messageType == TEST_REQUEST_MESSAGE_TYPE)
        {
            return onExceptionalMessage(testRequest.header());
        }
        else if (messageType == SEQUENCE_RESET_MESSAGE_TYPE)
        {
            return onExceptionalMessage(sequenceReset.header());
        }
        return onExceptionalMessage(header);
    }

    private Action onExceptionalMessage(final SessionHeaderDecoder header)
    {
        final int msgSeqNum = header.msgSeqNum();

        return session.onInvalidMessage(
            msgSeqNum,
            MISSING_INT,
            header.msgType(),
            header.msgTypeLength(),
            SessionConstants.INCORRECT_DATA_FORMAT_FOR_VALUE);
    }

    private Action onHeartbeat(final int offset, final int length)
    {
        final AbstractHeartbeatDecoder heartbeat = this.heartbeat;

        heartbeat.reset();
        heartbeat.decode(asciiBuffer, offset, length);
        final SessionHeaderDecoder header = heartbeat.header();
        if (CODEC_VALIDATION_ENABLED && (!heartbeat.validate() || !validateHeader(header)))
        {
            return onCodecInvalidMessage(heartbeat, header, false);
        }
        else if (heartbeat.hasTestReqID())
        {
            final long origSendingTime = origSendingTime(header);
            final long sendingTime = sendingTime(header);
            final int testReqIDLength = heartbeat.testReqIDLength();
            final char[] testReqID = heartbeat.testReqID();
            final int msgSeqNum = header.msgSeqNum();
            final boolean possDup = isPossDup(header);

            return session.onHeartbeat(
                msgSeqNum,
                testReqID,
                testReqIDLength,
                sendingTime,
                origSendingTime,
                isPossDupOrResend(possDup, header),
                possDup);
        }
        else
        {
            return onMessage(header);
        }
    }

    private long sendingTime(final SessionHeaderDecoder header)
    {
        final byte[] sendingTime = header.sendingTime();
        return decodeTimestamp(sendingTime);
    }

    private long decodeTimestamp(final byte[] sendingTime)
    {
        return CODEC_VALIDATION_ENABLED ?
            timestampDecoder.decode(sendingTime, sendingTime.length) :
            MISSING_LONG;
    }

    private Action onAnyOtherMessage(final int offset, final int length)
    {
        final SessionHeaderDecoder header = this.header;
        header.reset();
        header.decode(asciiBuffer, offset, length);

        final char[] msgType = header.msgType();
        final int msgTypeLength = header.msgTypeLength();
        if (CODEC_VALIDATION_ENABLED && (!isValidMsgType(msgType, msgTypeLength) || !validateHeader(header)))
        {
            final int msgSeqNum = header.msgSeqNum();
            if (!isDisconnectedOrAwaitingLogout())
            {
                return session.onInvalidMessageType(msgSeqNum, msgType, msgTypeLength);
            }
        }
        else
        {
            return onMessage(header);
        }

        return CONTINUE;
    }

    private Action onMessage(final SessionHeaderDecoder header)
    {
        final long origSendingTime = origSendingTime(header);
        final long sendingTime = sendingTime(header);
        final boolean possDup = isPossDup(header);
        return session.onMessage(
            header.msgSeqNum(),
            header.msgType(),
            header.msgTypeLength(),
            sendingTime,
            origSendingTime,
            isPossDupOrResend(possDup, header),
            possDup);
    }

    private long origSendingTime(final SessionHeaderDecoder header)
    {
        return header.hasOrigSendingTime() ? decodeTimestamp(header.origSendingTime()) : UNKNOWN;
    }

    private Action onSequenceReset(final int offset, final int length)
    {
        final AbstractSequenceResetDecoder sequenceReset = this.sequenceReset;

        sequenceReset.reset();
        sequenceReset.decode(asciiBuffer, offset, length);
        final SessionHeaderDecoder header = sequenceReset.header();
        if (CODEC_VALIDATION_ENABLED && (!sequenceReset.validate() || !validateHeader(header)))
        {
            return onCodecInvalidMessage(sequenceReset, header, false);
        }
        else
        {
            final boolean gapFillFlag = sequenceReset.hasGapFillFlag() && sequenceReset.gapFillFlag();
            final boolean possDup = isPossDup(header);
            return session.onSequenceReset(
                header.msgSeqNum(),
                sequenceReset.newSeqNo(),
                gapFillFlag,
                isPossDupOrResend(possDup, header)
            );
        }
    }

    private Action onTestRequest(final int offset, final int length)
    {
        final AbstractTestRequestDecoder testRequest = this.testRequest;

        testRequest.reset();
        testRequest.decode(asciiBuffer, offset, length);
        final SessionHeaderDecoder header = testRequest.header();
        if (CODEC_VALIDATION_ENABLED && (!testRequest.validate() || !validateHeader(header)))
        {
            return onCodecInvalidMessage(testRequest, header, false);
        }
        else
        {
            final int msgSeqNo = header.msgSeqNum();
            final long origSendingTime = origSendingTime(header);
            final long sendingTime = sendingTime(header);
            final boolean possDup = isPossDup(header);
            return session.onTestRequest(
                msgSeqNo,
                testRequest.testReqID(),
                testRequest.testReqIDLength(),
                sendingTime,
                origSendingTime,
                isPossDupOrResend(possDup, header),
                possDup);
        }
    }

    private Action onReject(final int offset, final int length)
    {
        final AbstractRejectDecoder reject = this.reject;

        reject.reset();
        reject.decode(asciiBuffer, offset, length);
        final SessionHeaderDecoder header = reject.header();
        if (CODEC_VALIDATION_ENABLED && (!reject.validate() || !validateHeader(header)))
        {
            return onCodecInvalidMessage(reject, header, false);
        }
        else
        {
            final long origSendingTime = origSendingTime(header);
            final long sendingTime = sendingTime(header);
            final boolean possDup = isPossDup(header);
            return session.onReject(
                header.msgSeqNum(),
                sendingTime,
                origSendingTime,
                isPossDupOrResend(possDup, header),
                possDup);
        }
    }

    private Action onLogout(final int offset, final int length)
    {
        final AbstractLogoutDecoder logout = this.logout;

        logout.reset();
        logout.decode(asciiBuffer, offset, length);
        final SessionHeaderDecoder header = logout.header();
        if (CODEC_VALIDATION_ENABLED && (!logout.validate() || !validateHeader(header)))
        {
            return onCodecInvalidMessage(logout, header, false);
        }
        else
        {
            final long origSendingTime = origSendingTime(header);
            final long sendingTime = sendingTime(header);
            final boolean possDup = isPossDup(header);
            return session.onLogout(
                header.msgSeqNum(),
                sendingTime,
                origSendingTime,
                possDup);
        }
    }

    private Action onLogon(final int offset, final int length)
    {
        final AbstractLogonDecoder logon = this.logon;
        final Session session = this.session;

        logon.reset();
        logon.decode(asciiBuffer, offset, length);
        final SessionHeaderDecoder header = logon.header();
        final char[] beginString = header.beginString();
        final int beginStringLength = header.beginStringLength();
        if (CODEC_VALIDATION_ENABLED &&
            (!logon.validate() || !session.onBeginString(beginString, beginStringLength, true)))
        {
            return onCodecInvalidMessage(logon, header, true);
        }
        else
        {
            final long origSendingTime = origSendingTime(header);
            final String username = username(logon);
            final String password = password(logon);
            final boolean possDup = isPossDup(header);

            if (validateCompIdsOnEveryMessage)
            {
                initialiseFirstIds(header);
            }

            return session.onLogon(
                logon.heartBtInt(),
                header.msgSeqNum(),
                sendingTime(header),
                origSendingTime,
                username,
                password,
                isPossDupOrResend(possDup, header),
                resetSeqNumFlag(logon),
                possDup);
        }
    }

    private void initialiseFirstIds(final SessionHeaderDecoder header)
    {
        firstSenderCompId = Arrays.copyOf(header.senderCompID(), header.senderCompIDLength());
        firstTargetCompId = Arrays.copyOf(header.targetCompID(), header.targetCompIDLength());
        if (header.hasSenderSubID())
        {
            firstSenderSubId = Arrays.copyOf(header.senderSubID(), header.senderSubIDLength());
        }
        if (header.hasSenderLocationID())
        {
            firstSenderLocationId = Arrays.copyOf(header.senderLocationID(), header.senderLocationIDLength());
        }
        if (header.hasTargetSubID())
        {
            firstTargetSubId = Arrays.copyOf(header.targetSubID(), header.targetSubIDLength());
        }
        if (header.hasTargetLocationID())
        {
            firstTargetLocationId = Arrays.copyOf(header.targetLocationID(), header.targetLocationIDLength());
        }
    }

    private void onStrategyError(final String strategyName, final Throwable throwable, final String fixMessage)
    {
        final String message = String.format(
            "Exception thrown by %s strategy for connectionId=%d, [%s], defaulted to false",
            strategyName,
            session.connectionId(),
            fixMessage);

        onError(new FixGatewayException(message, throwable));
    }

    private void onError(final Throwable throwable)
    {
        // Library code should throw the exception to make users aware of it
        // Engine code should log it through the normal error handling process.
        if (errorHandler == null)
        {
            LangUtil.rethrowUnchecked(throwable);
        }
        else
        {
            errorHandler.onError(throwable);
        }
    }

    private boolean resetSeqNumFlag(final AbstractLogonDecoder logon)
    {
        return logon.hasResetSeqNumFlag() && logon.resetSeqNumFlag();
    }

    private boolean validateHeader(final SessionHeaderDecoder header)
    {
        // Validate begin string
        if (!session.onBeginString(header.beginString(), header.beginStringLength(), false))
        {
            return false;
        }

        boolean validated = true;
        int rejectReason = RejectReason.OTHER.representation();

        int invalidTagId = validateCompIds(header);
        if (invalidTagId != 0)
        {
            validated = false;
            rejectReason = RejectReason.COMPID_PROBLEM.representation();
        }

        // Apply validation strategy
        if (validated)
        {
            try
            {
                validated = validationStrategy.validate(header);
                if (!validated)
                {
                    rejectReason = validationStrategy.rejectReason();
                    invalidTagId = validationStrategy.invalidTagId();
                }
            }
            catch (final Throwable throwable)
            {
                onStrategyError("validation", throwable, header.toString());
                validated = false;
            }
        }

        if (!validated)
        {
            session.onInvalidMessage(
                header.msgSeqNum(),
                invalidTagId,
                header.msgType(),
                header.msgTypeLength(),
                rejectReason);
            session.logoutRejectReason(rejectReason);
            session.startLogout();
            return false;
        }

        return true;
    }

    private int validateCompIds(final SessionHeaderDecoder header)
    {
        if (!validateCompIdsOnEveryMessage)
        {
            return 0;
        }

        // Case can happen when switching control of the Session between Library and Engine.
        if (firstSenderCompId == null)
        {
            initialiseFirstIds(header);
            return 0;
        }

        if (!CodecUtil.equals(firstSenderCompId, header.senderCompID(), header.senderCompIDLength()))
        {
            return SENDER_COMP_ID;
        }

        if (!CodecUtil.equals(firstTargetCompId, header.targetCompID(), header.targetCompIDLength()))
        {
            return TARGET_COMP_ID;
        }

        // Optional cases are a bit goofy on account of getters throwing if the has method returns false.

        final boolean hasSenderSubID = header.hasSenderSubID();
        if (!(firstSenderSubId == null ? !hasSenderSubID : hasSenderSubID &&
            CodecUtil.equals(firstSenderSubId, header.senderSubID(), header.senderSubIDLength())))
        {
            return SENDER_SUB_ID;
        }

        final boolean hasSenderLocationID = header.hasSenderLocationID();
        if (!(firstSenderLocationId == null ? !hasSenderLocationID : hasSenderLocationID &&
            CodecUtil.equals(firstSenderLocationId, header.senderLocationID(), header.senderLocationIDLength())))
        {
            return SENDER_LOCATION_ID;
        }

        final boolean hasTargetSubID = header.hasTargetSubID();
        if (!(firstTargetSubId == null ? !hasTargetSubID : hasTargetSubID &&
            CodecUtil.equals(firstTargetSubId, header.targetSubID(), header.targetSubIDLength())))
        {
            return TARGET_SUB_ID;
        }

        final boolean hasTargetLocationID = header.hasTargetLocationID();
        if (!(firstTargetLocationId == null ? !hasTargetLocationID : hasTargetLocationID &&
            CodecUtil.equals(firstTargetLocationId, header.targetLocationID(), header.targetLocationIDLength())))
        {
            return TARGET_LOCATION_ID;
        }

        return 0;
    }

    private Action onCodecInvalidMessage(
        final Decoder decoder,
        final SessionHeaderDecoder header,
        final boolean requestDisconnect)
    {
        if (!isDisconnectedOrAwaitingLogout())
        {
            final int msgTypeLength = header.msgTypeLength();

            if (header.msgSeqNum() == MISSING_INT)
            {
                final long origSendingTime = origSendingTime(header);
                final long sendingTime = sendingTime(header);
                final char[] msgType = header.msgType();
                return session.onMessage(MISSING_INT, msgType, msgTypeLength, sendingTime, origSendingTime, false,
                    false);
            }

            final Action action = session.onInvalidMessage(
                header.msgSeqNum(),
                decoder.invalidTagId(),
                header.msgType(),
                msgTypeLength,
                decoder.rejectReason());

            if (action == CONTINUE && requestDisconnect)
            {
                return session.onInvalidFixDisconnect();
            }

            return action;
        }

        if (requestDisconnect)
        {
            return session.onInvalidFixDisconnect();
        }

        return CONTINUE;
    }

    private boolean isDisconnectedOrAwaitingLogout()
    {
        final SessionState state = session.state();
        return state == DISCONNECTED || state == AWAITING_LOGOUT;
    }

    private boolean isPossDupOrResend(final boolean possDup, final SessionHeaderDecoder header)
    {
        return possDup || (header.hasPossResend() && header.possResend());
    }

    private boolean isPossDup(final SessionHeaderDecoder header)
    {
        return header.hasPossDupFlag() && header.possDupFlag();
    }

    public Session session()
    {
        return session;
    }

    public void sequenceIndex(final int sequenceIndex)
    {
        session.sequenceIndex(sequenceIndex);
    }
}
