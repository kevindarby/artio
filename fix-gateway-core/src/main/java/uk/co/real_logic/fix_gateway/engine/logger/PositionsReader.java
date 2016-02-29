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
package uk.co.real_logic.fix_gateway.engine.logger;

import uk.co.real_logic.agrona.concurrent.AtomicBuffer;
import uk.co.real_logic.fix_gateway.engine.SectorFramer;
import uk.co.real_logic.fix_gateway.messages.IndexedPositionDecoder;
import uk.co.real_logic.fix_gateway.messages.MessageHeaderDecoder;

import static uk.co.real_logic.fix_gateway.engine.SectorFramer.OUT_OF_SPACE;
import static uk.co.real_logic.fix_gateway.engine.logger.PositionsWriter.HEADER_LENGTH;
import static uk.co.real_logic.fix_gateway.engine.logger.PositionsWriter.POSITION_OFFSET;
import static uk.co.real_logic.fix_gateway.engine.logger.PositionsWriter.RECORD_LENGTH;

/**
 * .
 */
public class PositionsReader
{
    public static final long UNKNOWN_POSITION = -1;

    private final IndexedPositionDecoder decoder = new IndexedPositionDecoder();

    private final int actingBlockLength;
    private final int actingVersion;
    private final AtomicBuffer buffer;
    private final SectorFramer sectorFramer;

    public PositionsReader(final AtomicBuffer buffer)
    {
        this.buffer = buffer;
        final MessageHeaderDecoder messageHeader = new MessageHeaderDecoder();
        messageHeader.wrap(buffer, 0);
        actingBlockLength = messageHeader.blockLength();
        actingVersion = messageHeader.version();
        sectorFramer = new SectorFramer(buffer.capacity());
    }

    public long indexedPosition(final int aeronSessionId)
    {
        final IndexedPositionDecoder decoder = this.decoder;
        final int actingBlockLength = this.actingBlockLength;
        final int actingVersion = this.actingVersion;
        final AtomicBuffer buffer = this.buffer;

        int offset = HEADER_LENGTH;
        while (true)
        {
            offset = sectorFramer.claim(offset, RECORD_LENGTH);
            if (offset == OUT_OF_SPACE)
            {
                return UNKNOWN_POSITION;
            }

            decoder.wrap(buffer, offset, actingBlockLength, actingVersion);
            if (decoder.sessionId() == aeronSessionId)
            {
                return buffer.getLongVolatile(offset + POSITION_OFFSET);
            }

            offset += RECORD_LENGTH;
        }
    }
}
