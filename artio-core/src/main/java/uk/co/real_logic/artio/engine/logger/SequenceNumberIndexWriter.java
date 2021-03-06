/*
 * Copyright 2015-2020 Real Logic Limited, Adaptive Financial Consulting Ltd., Monotonic Ltd.
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
package uk.co.real_logic.artio.engine.logger;

import io.aeron.logbuffer.Header;
import io.aeron.protocol.DataHeaderFlyweight;
import org.agrona.CloseHelper;
import org.agrona.DirectBuffer;
import org.agrona.ErrorHandler;
import org.agrona.LangUtil;
import org.agrona.collections.CollectionUtil;
import org.agrona.collections.Long2LongHashMap;
import org.agrona.concurrent.AtomicBuffer;
import org.agrona.concurrent.EpochClock;
import uk.co.real_logic.artio.dictionary.generation.Exceptions;
import uk.co.real_logic.artio.engine.ChecksumFramer;
import uk.co.real_logic.artio.engine.MappedFile;
import uk.co.real_logic.artio.engine.SequenceNumberExtractor;
import uk.co.real_logic.artio.engine.framer.FramerContext;
import uk.co.real_logic.artio.engine.framer.WriteMetaDataResponse;
import uk.co.real_logic.artio.messages.*;
import uk.co.real_logic.artio.storage.messages.LastKnownSequenceNumberDecoder;
import uk.co.real_logic.artio.storage.messages.LastKnownSequenceNumberEncoder;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;
import java.util.zip.CRC32;

import static io.aeron.protocol.DataHeaderFlyweight.BEGIN_FLAG;
import static uk.co.real_logic.artio.engine.SectorFramer.*;
import static uk.co.real_logic.artio.engine.SequenceNumberExtractor.NO_SEQUENCE_NUMBER;
import static uk.co.real_logic.artio.engine.logger.SequenceNumberIndexDescriptor.*;
import static uk.co.real_logic.artio.messages.FixMessageDecoder.metaDataSinceVersion;
import static uk.co.real_logic.artio.storage.messages.LastKnownSequenceNumberEncoder.SCHEMA_VERSION;

/**
 * Writes updates into an in-memory buffer. This buffer is then flushed down to disk. A passing place
 * file is used to ensure that there's a recoverable option if it fails.
 */
public class SequenceNumberIndexWriter implements Index
{
    public static final boolean RUNNING_ON_WINDOWS = System.getProperty("os.name").startsWith("Windows");

    private static final long MISSING_RECORD = -1L;
    private static final long UNINITIALISED = -1;
    static final int SEQUENCE_NUMBER_OFFSET = LastKnownSequenceNumberEncoder.sequenceNumberEncodingOffset();
    static final int META_DATA_OFFSET = LastKnownSequenceNumberEncoder.metaDataPositionEncodingOffset();

    private final MessageHeaderDecoder messageHeader = new MessageHeaderDecoder();
    private final FixMessageDecoder messageFrame = new FixMessageDecoder();
    private final ResetSequenceNumberDecoder resetSequenceNumber = new ResetSequenceNumberDecoder();
    private final WriteMetaDataDecoder writeMetaData = new WriteMetaDataDecoder();

    private final MessageHeaderDecoder fileHeaderDecoder = new MessageHeaderDecoder();
    private final MessageHeaderEncoder fileHeaderEncoder = new MessageHeaderEncoder();
    private final LastKnownSequenceNumberEncoder lastKnownEncoder = new LastKnownSequenceNumberEncoder();
    private final LastKnownSequenceNumberDecoder lastKnownDecoder = new LastKnownSequenceNumberDecoder();
    private final Long2LongHashMap recordOffsets = new Long2LongHashMap(MISSING_RECORD);

    // Meta data state
    private final File metaDataLocation;
    private final CRC32 checksum = new CRC32();
    private final List<WriteMetaDataResponse> responsesToResend = new ArrayList<>();
    private final Predicate<WriteMetaDataResponse> sendResponseFunc = this::sendResponse;
    private final RandomAccessFile metaDataFile;
    private byte[] metaDataWriteBuffer = new byte[0];

    private final SequenceNumberExtractor sequenceNumberExtractor;
    private FramerContext framerContext;
    private final ChecksumFramer checksumFramer;
    private final AtomicBuffer inMemoryBuffer;
    private final ErrorHandler errorHandler;
    private final Path indexPath;
    private final Path writablePath;
    private final Path passingPlacePath;
    private final int fileCapacity;
    private final RecordingIdLookup recordingIdLookup;
    private final int streamId;
    private final int indexedPositionsOffset;
    private final IndexedPositionWriter positions;

    private MappedFile writableFile;
    private MappedFile indexFile;
    private long nextRollPosition = UNINITIALISED;

    private final EpochClock clock;
    private final long indexFileStateFlushTimeoutInMs;
    private long lastUpdatedFileTimeInMs;
    private boolean hasSavedRecordSinceFileUpdate = false;

    public SequenceNumberIndexWriter(
        final AtomicBuffer inMemoryBuffer,
        final MappedFile indexFile,
        final ErrorHandler errorHandler,
        final int streamId,
        final RecordingIdLookup recordingIdLookup,
        final long indexFileStateFlushTimeoutInMs,
        final EpochClock clock,
        final String metaDataDir)
    {
        this.inMemoryBuffer = inMemoryBuffer;
        this.indexFile = indexFile;
        this.errorHandler = errorHandler;
        this.streamId = streamId;
        this.fileCapacity = indexFile.buffer().capacity();
        this.recordingIdLookup = recordingIdLookup;
        this.indexFileStateFlushTimeoutInMs = indexFileStateFlushTimeoutInMs;
        this.clock = clock;

        final String indexFilePath = indexFile.file().getAbsolutePath();
        indexPath = indexFile.file().toPath();
        final File writeableFile = writableFile(indexFilePath);
        writablePath = writeableFile.toPath();
        passingPlacePath = passingFile(indexFilePath).toPath();
        writableFile = MappedFile.map(writeableFile, fileCapacity);
        sequenceNumberExtractor = new SequenceNumberExtractor(errorHandler);

        // TODO: Fsync parent directory
        indexedPositionsOffset = positionTableOffset(fileCapacity);
        checksumFramer = new ChecksumFramer(
            inMemoryBuffer, indexedPositionsOffset, errorHandler, 0, "SequenceNumberIndex");
        try
        {
            initialiseBuffer();
            positions = new IndexedPositionWriter(
                positionsBuffer(inMemoryBuffer, indexedPositionsOffset),
                errorHandler,
                indexedPositionsOffset,
                "SequenceNumberIndex");

            if (metaDataDir != null)
            {
                metaDataLocation = metaDataFile(metaDataDir);
                metaDataFile = openMetaDataFile(metaDataLocation);
            }
            else
            {
                metaDataLocation = null;
                metaDataFile = null;
            }
        }
        catch (final Exception e)
        {
            CloseHelper.close(writableFile);
            indexFile.close();
            throw e;
        }
    }

    private RandomAccessFile openMetaDataFile(final File metaDataLocation)
    {
        RandomAccessFile file = null;
        try
        {
            file = new RandomAccessFile(metaDataLocation, "rw");
            if (file.length() == 0)
            {
                writeMetaDataFileHeader(file);
            }
            else
            {
                final long magicNumber = file.readLong();
                final int fileVersion = file.readInt();

                if (magicNumber != META_DATA_MAGIC_NUMBER)
                {
                    throw new IllegalStateException("Invalid magic number in metadata file: " + magicNumber);
                }

                if (fileVersion < READABLE_META_DATA_FILE_VERSION)
                {
                    throw new IllegalStateException("Unreadable metadata file version: " + fileVersion);
                }
            }
            return file;
        }
        catch (final IOException | IllegalStateException e)
        {
            if (file != null)
            {
                Exceptions.suppressingClose(file, e);
            }
            LangUtil.rethrowUnchecked(e);
        }

        return null;
    }

    private void writeMetaDataFileHeader(final RandomAccessFile file) throws IOException
    {
        file.writeLong(META_DATA_MAGIC_NUMBER);
        file.writeInt(META_DATA_FILE_VERSION);
        file.getFD().sync();
    }

    public void onFragment(
        final DirectBuffer buffer,
        final int srcOffset,
        final int length,
        final Header header)
    {
        final int streamId = header.streamId();
        final long endPosition = header.position();
        final int aeronSessionId = header.sessionId();

        if (streamId != this.streamId)
        {
            return;
        }

        if ((header.flags() & BEGIN_FLAG) == BEGIN_FLAG)
        {
            int offset = srcOffset;
            messageHeader.wrap(buffer, offset);

            offset += messageHeader.encodedLength();
            final int actingBlockLength = messageHeader.blockLength();
            final int version = messageHeader.version();

            switch (messageHeader.templateId())
            {
                case FixMessageEncoder.TEMPLATE_ID:
                {
                    if (!onFixMessage(buffer, offset, actingBlockLength, version))
                    {
                        return;
                    }
                    break;
                }

                case ResetSessionIdsDecoder.TEMPLATE_ID:
                {
                    resetSequenceNumbers();
                    break;
                }

                case ResetSequenceNumberDecoder.TEMPLATE_ID:
                {
                    resetSequenceNumber.wrap(buffer, offset, actingBlockLength, version);
                    saveRecord(0, resetSequenceNumber.session());
                    break;
                }

                case WriteMetaDataDecoder.TEMPLATE_ID:
                {
                    writeMetaData.wrap(buffer, offset, actingBlockLength, version);

                    final int libraryId = writeMetaData.libraryId();
                    final long sessionId = writeMetaData.session();
                    final long correlationId = writeMetaData.correlationId();

                    onWriteMetaData(libraryId, sessionId, correlationId);
                    break;
                }
            }
        }

        checkTermRoll(buffer, srcOffset, endPosition, length);

        final long recordingId = recordingIdLookup.getRecordingId(aeronSessionId);
        positions.indexedUpTo(aeronSessionId, recordingId, endPosition);
    }

    private boolean onFixMessage(
        final DirectBuffer buffer, final int start, final int actingBlockLength, final int version)
    {
        int offset = start;

        messageFrame.wrap(buffer, offset, actingBlockLength, version);

        if (messageFrame.status() != MessageStatus.OK)
        {
            return false;
        }

        offset += actingBlockLength;

        final int metaDataLength;
        if (version >= metaDataSinceVersion())
        {
            metaDataLength = messageFrame.metaDataLength();
            resizeMetaDataBuffer(metaDataLength);
            messageFrame.getMetaData(metaDataWriteBuffer, 0, metaDataLength);

            offset += FixMessageDecoder.metaDataHeaderLength() + metaDataLength;
        }
        else
        {
            metaDataLength = 0;
        }

        offset += FixMessageDecoder.bodyHeaderLength();
        final long sessionId = messageFrame.session();

        final int msgSeqNum = sequenceNumberExtractor.extract(
            buffer, offset, messageFrame.bodyLength());
        if (msgSeqNum != NO_SEQUENCE_NUMBER)
        {
            final int position = saveRecord(msgSeqNum, sessionId);
            if (metaDataLength > 0 && position > 0)
            {
                writeMetaDataToFile(position, metaDataWriteBuffer, metaDataLength);
            }
        }
        return true;
    }

    private void resizeMetaDataBuffer(final int metaDataLength)
    {
        if (metaDataWriteBuffer.length < metaDataLength)
        {
            metaDataWriteBuffer = new byte[metaDataLength];
        }
    }

    private void onWriteMetaData(final int libraryId, final long sessionId, final long correlationId)
    {
        if (framerContext == null || metaDataFile == null)
        {
            writeMetaDataResponse(libraryId, correlationId, MetaDataStatus.FILE_ERROR);

            return;
        }

        final int sequenceNumberIndexFilePosition = (int)recordOffsets.get(sessionId);
        if (sequenceNumberIndexFilePosition == MISSING_RECORD)
        {
            writeMetaDataResponse(libraryId, correlationId, MetaDataStatus.UNKNOWN_SESSION);

            return;
        }


        final int metaDataLength = writeMetaData.metaDataLength();
        resizeMetaDataBuffer(metaDataLength);
        writeMetaData.getMetaData(metaDataWriteBuffer, 0, metaDataLength);

        final MetaDataStatus status = writeMetaDataToFile(
            sequenceNumberIndexFilePosition, metaDataWriteBuffer, metaDataLength);
        writeMetaDataResponse(libraryId, correlationId, status);
    }

    private MetaDataStatus writeMetaDataToFile(
        final int sequenceNumberIndexFilePosition, final byte[] metaDataValue, final int metaDataLength)
    {
        checksum.update(metaDataValue, 0, metaDataLength);
        final long checksumValue = checksum.getValue();
        checksum.reset();

        final int oldMetaDataPosition = getMetaData(sequenceNumberIndexFilePosition);
        try
        {
            if (oldMetaDataPosition == NO_META_DATA)
            {
                allocateMetaDataSlot(sequenceNumberIndexFilePosition, metaDataValue, checksumValue);
            }
            else
            {
                metaDataFile.seek(oldMetaDataPosition + SIZE_OF_META_DATA_CHECKSUM);
                final int oldMetaDataLength = metaDataFile.readInt();
                // Is there space to replace?
                if (metaDataLength <= oldMetaDataLength)
                {
                    updateMetaDataFile(oldMetaDataPosition, checksumValue, metaDataValue);
                }
                else
                {
                    allocateMetaDataSlot(sequenceNumberIndexFilePosition, metaDataValue, checksumValue);
                }
            }

            return MetaDataStatus.OK;
        }
        catch (final IOException e)
        {
            errorHandler.onError(e);

            return MetaDataStatus.FILE_ERROR;
        }
    }

    private void allocateMetaDataSlot(
        final int sequenceNumberIndexFilePosition,
        final byte[] metaDataValue,
        final long checksumValue) throws IOException
    {
        final int metaDataFileInitialLength = (int)metaDataFile.length();
        updateMetaDataFile(metaDataFileInitialLength, checksumValue, metaDataValue);
        putMetaDataField(sequenceNumberIndexFilePosition, metaDataFileInitialLength);
        hasSavedRecordSinceFileUpdate = true;
    }

    private void updateMetaDataFile(
        final int position, final long checksumValue, final byte[] metaDataValue) throws IOException
    {
        metaDataFile.seek(position);
        metaDataFile.writeLong(checksumValue);
        metaDataFile.writeInt(metaDataValue.length);
        metaDataFile.write(metaDataValue);
        metaDataFile.getFD().sync();
    }

    private void writeMetaDataResponse(final int libraryId, final long correlationId, final MetaDataStatus status)
    {
        final WriteMetaDataResponse response = new WriteMetaDataResponse(libraryId, correlationId, status);
        if (!sendResponse(response))
        {
            responsesToResend.add(response);
        }
    }

    @Override
    public int doWork()
    {
        if (hasSavedRecordSinceFileUpdate)
        {
            final long requiredUpdateTimeInMs = lastUpdatedFileTimeInMs + indexFileStateFlushTimeoutInMs;
            if (requiredUpdateTimeInMs < clock.time())
            {
                updateFile();
                return 1;
            }
        }

        return CollectionUtil.removeIf(responsesToResend, sendResponseFunc);
    }

    private boolean sendResponse(final WriteMetaDataResponse response)
    {
        return framerContext.offer(response);
    }

    void resetSequenceNumbers()
    {
        inMemoryBuffer.setMemory(0, indexedPositionsOffset, (byte)0);
        initialiseBlankBuffer();
        recordOffsets.clear();
        resetMetaDataFile();
    }

    private void resetMetaDataFile()
    {
        if (metaDataLocation != null)
        {
            try
            {
                metaDataFile.seek(0);
                metaDataFile.setLength(META_DATA_FILE_HEADER_LENGTH);
                writeMetaDataFileHeader(metaDataFile);
            }
            catch (final IOException e)
            {
                errorHandler.onError(e);
            }
        }
    }

    private void checkTermRoll(final DirectBuffer buffer, final int offset, final long endPosition, final int length)
    {
        final long termBufferLength = buffer.capacity();
        if (nextRollPosition == UNINITIALISED)
        {
            final long startPosition = endPosition - (length + DataHeaderFlyweight.HEADER_LENGTH);
            nextRollPosition = startPosition + termBufferLength - offset;
        }
        else if (endPosition > nextRollPosition)
        {
            nextRollPosition += termBufferLength;
            updateFile();
        }
    }

    private void updateFile()
    {
        checksumFramer.updateChecksums();
        positions.updateChecksums();
        saveFile();
        flipFiles();
        hasSavedRecordSinceFileUpdate = false;
        lastUpdatedFileTimeInMs = clock.time();
    }

    private void saveFile()
    {
        writableFile.buffer().putBytes(0, inMemoryBuffer, 0, fileCapacity);
        writableFile.force();
    }

    private void flipFiles()
    {
        if (RUNNING_ON_WINDOWS)
        {
            writableFile.close();
            indexFile.close();
        }

        final boolean flipsFiles = rename(indexPath, passingPlacePath) &&
            rename(writablePath, indexPath) &&
            rename(passingPlacePath, writablePath);

        if (RUNNING_ON_WINDOWS)
        {
            // remapping flips the files here due to the rename
            writableFile.map();
            indexFile.map();
        }
        else if (flipsFiles)
        {
            final MappedFile file = this.writableFile;
            writableFile = indexFile;
            indexFile = file;
        }
    }

    private boolean rename(final Path src, final Path dest)
    {
        try
        {
            Files.move(src, dest, StandardCopyOption.ATOMIC_MOVE);
            return true;
        }
        catch (final IOException e)
        {
            errorHandler.onError(e);
            return false;
        }
    }

    public Path passingPlace()
    {
        return passingPlacePath;
    }

    public boolean isOpen()
    {
        return writableFile.isOpen();
    }

    public void close()
    {
        try
        {
            if (isOpen() && hasSavedRecordSinceFileUpdate)
            {
                updateFile();
            }
        }
        finally
        {
            indexFile.close();
            writableFile.close();

            if (metaDataFile != null)
            {
                try
                {
                    metaDataFile.close();
                }
                catch (final IOException e)
                {
                    errorHandler.onError(e);
                }
            }
        }
    }

    public void readLastPosition(final IndexedPositionConsumer consumer)
    {
        // Inefficient, but only run once on startup, so not a big deal.
        new IndexedPositionReader(positions.buffer()).readLastPosition(consumer);
    }

    private int saveRecord(final int newSequenceNumber, final long sessionId)
    {
        int position = (int)recordOffsets.get(sessionId);
        if (position == MISSING_RECORD)
        {
            position = SequenceNumberIndexDescriptor.HEADER_SIZE;
            while (true)
            {
                position = checksumFramer.claim(position, RECORD_SIZE);
                if (position == OUT_OF_SPACE)
                {
                    errorHandler.onError(new IllegalStateException(
                        "Sequence Number Index out of space, can't claim slot for " + sessionId));
                    return position;
                }

                lastKnownDecoder.wrap(inMemoryBuffer, position, RECORD_SIZE, SCHEMA_VERSION);
                if (lastKnownDecoder.sequenceNumber() == 0)
                {
                    createNewRecord(newSequenceNumber, sessionId, position);
                    hasSavedRecordSinceFileUpdate = true;
                    return position;
                }
                else if (lastKnownDecoder.sessionId() == sessionId)
                {
                    updateSequenceNumber(newSequenceNumber, position);
                    return position;
                }

                position += RECORD_SIZE;
            }
        }
        else
        {
            updateSequenceNumber(newSequenceNumber, position);
            return position;
        }
    }

    private void updateSequenceNumber(final int newSequenceNumber, final int position)
    {
        final int oldSequenceNumber = getSequenceNumber(position);
        putSequenceNumber(position, newSequenceNumber);
        // When sequence number resets then old metadata has expired
        if (oldSequenceNumber > newSequenceNumber)
        {
            final int oldMetaDataPosition = getMetaData(position);
            if (oldMetaDataPosition != NO_META_DATA)
            {
                putMetaDataField(position, NO_META_DATA);
                try
                {
                    metaDataFile.seek(oldMetaDataPosition + SIZE_OF_META_DATA_CHECKSUM);
                    final int metaDataLength = metaDataFile.readInt();
                    updateMetaDataFile(oldMetaDataPosition, 0, new byte[metaDataLength]);
                }
                catch (final IOException e)
                {
                    errorHandler.onError(e);
                }
            }
        }
        hasSavedRecordSinceFileUpdate = true;
    }

    private void createNewRecord(
        final int sequenceNumber,
        final long sessionId,
        final int position)
    {
        recordOffsets.put(sessionId, position);
        lastKnownEncoder
            .wrap(inMemoryBuffer, position)
            .sessionId(sessionId);
        putSequenceNumber(position, sequenceNumber);
        putMetaDataField(position, NO_META_DATA);
    }

    private void initialiseBuffer()
    {
        validateBufferSizes();
        final AtomicBuffer fileBuffer = indexFile.buffer();
        if (fileHasBeenInitialized(fileBuffer))
        {
            readFile(fileBuffer);
        }
        else if (Files.exists(passingPlacePath))
        {
            if (rename(passingPlacePath, indexPath))
            {
                // TODO: fsync parent directory
                indexFile.remap();
                initialiseBuffer();
            }
            else
            {
                errorHandler.onError(new IllegalStateException(String.format(
                    "Unable to recover index file from %s to %s due to rename failure",
                    passingPlacePath,
                    indexPath)));
            }
        }
        else
        {
            initialiseBlankBuffer();
        }
    }

    private void initialiseBlankBuffer()
    {
        LoggerUtil.initialiseBuffer(
            inMemoryBuffer,
            fileHeaderEncoder,
            fileHeaderDecoder,
            lastKnownEncoder.sbeSchemaId(),
            lastKnownEncoder.sbeTemplateId(),
            lastKnownEncoder.sbeSchemaVersion(),
            lastKnownEncoder.sbeBlockLength(),
            errorHandler);
    }

    private boolean fileHasBeenInitialized(final AtomicBuffer fileBuffer)
    {
        return fileBuffer.getShort(0) != 0 || fileBuffer.getInt(FIRST_CHECKSUM_LOCATION) != 0;
    }

    private void validateBufferSizes()
    {
        final int inMemoryCapacity = inMemoryBuffer.capacity();

        if (fileCapacity != inMemoryCapacity)
        {
            throw new IllegalStateException(String.format(
                "In memory buffer and disk file don't have the same size, disk: %d, memory: %d",
                fileCapacity,
                inMemoryCapacity
            ));
        }

        if (fileCapacity < SECTOR_SIZE)
        {
            throw new IllegalStateException(String.format(
                "Cannot create sequence number of size < 1 sector: %d",
                fileCapacity));
        }
    }

    private void readFile(final AtomicBuffer fileBuffer)
    {
        loadBuffer(fileBuffer);
        checksumFramer.validateCheckSums();
    }

    private void loadBuffer(final AtomicBuffer fileBuffer)
    {
        inMemoryBuffer.putBytes(0, fileBuffer, 0, fileCapacity);
    }

    private void putSequenceNumber(
        final int recordOffset,
        final int value)
    {
        inMemoryBuffer.putIntOrdered(recordOffset + SEQUENCE_NUMBER_OFFSET, value);
    }

    private int getSequenceNumber(final int recordOffset)
    {
        return inMemoryBuffer.getIntVolatile(recordOffset + SEQUENCE_NUMBER_OFFSET);
    }

    private void putMetaDataField(
        final int recordOffset,
        final int value)
    {
        inMemoryBuffer.putIntOrdered(recordOffset + META_DATA_OFFSET, value);
    }

    private int getMetaData(
        final int recordOffset)
    {
        return inMemoryBuffer.getIntVolatile(recordOffset + META_DATA_OFFSET);
    }

    public void framerContext(final FramerContext framerContext)
    {
        this.framerContext = framerContext;
    }
}
