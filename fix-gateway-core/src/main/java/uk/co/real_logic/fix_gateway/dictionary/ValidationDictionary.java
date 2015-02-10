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
package uk.co.real_logic.fix_gateway.dictionary;

import uk.co.real_logic.agrona.collections.Int2ObjectHashMap;
import uk.co.real_logic.fix_gateway.dictionary.ir.DataDictionary;
import uk.co.real_logic.fix_gateway.dictionary.ir.Entry;
import uk.co.real_logic.fix_gateway.dictionary.ir.Field;
import uk.co.real_logic.fix_gateway.util.IntHashSet;

import java.util.function.Predicate;

/**
 * Dictionary for runtime validation by the generic parser.
 *
 * Essentially a map from ints to a set of ints.
 */
public final class ValidationDictionary
{
    private static final int MISSING_FIELD = -1;
    private static final int CAPACITY = 1024;

    private final Int2ObjectHashMap<IntHashSet> map;

    public static ValidationDictionary requiredFields(final DataDictionary dataDictionary)
    {
        return fields(dataDictionary, Entry::required);
    }

    public static ValidationDictionary allFields(final DataDictionary dataDictionary)
    {
        return fields(dataDictionary, entry -> true);
    }

    private static ValidationDictionary fields(final DataDictionary dataDictionary, final Predicate<Entry> entryPredicate)
    {
        final ValidationDictionary fields = new ValidationDictionary();

        dataDictionary.messages().forEach(message ->
        {
            final int type = message.type();
            message.entries()
                   .stream()
                   .filter(entryPredicate)
                   .filter(entry -> entry.element() instanceof Field)
                   .map(entry -> (Field) entry.element())
                   .forEach(field -> fields.put(type, field.number()));
        });

        return fields;
    }

    public ValidationDictionary()
    {
        map = new Int2ObjectHashMap<>();
    }

    public void put(final int messageType, final int fieldNumber)
    {
        final IntHashSet fields = map.getOrDefault(messageType, () -> new IntHashSet(CAPACITY, MISSING_FIELD));
        fields.add(fieldNumber);
    }

    public IntHashSet fields(final int messageType)
    {
        return map.get(messageType);
    }

    public boolean contains(final int messageType, final int fieldNumber)
    {
        final IntHashSet fields = fields(messageType);
        return fields != null && fields.contains(fieldNumber);
    }
}
