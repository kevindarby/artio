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

import org.junit.Before;
import org.junit.Test;
import uk.co.real_logic.fix_gateway.dictionary.ir.DataDictionary;
import uk.co.real_logic.fix_gateway.dictionary.ir.Entry;
import uk.co.real_logic.fix_gateway.dictionary.ir.Field;
import uk.co.real_logic.fix_gateway.dictionary.ir.Field.Type;
import uk.co.real_logic.fix_gateway.dictionary.ir.Message;
import uk.co.real_logic.fix_gateway.util.IntHashSet;

import java.util.Arrays;
import java.util.List;

import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.assertTrue;
import static uk.co.real_logic.fix_gateway.dictionary.ir.Category.ADMIN;

public class ValidationDictionaryTest
{

    private DataDictionary data;

    @Before
    public void createDataDictionary()
    {
        final Message heartbeat = new Message("Hearbeat", '0', ADMIN);
        heartbeat.entries().add(Entry.required(new Field(115, "OnBehalfOfCompID", Type.STRING)));
        heartbeat.entries().add(Entry.optional(new Field(112, "TestReqID", Type.STRING)));

        final List<Message> messages = Arrays.asList(heartbeat);
        data = new DataDictionary(messages, null, null);
    }

    @Test
    public void buildsValidationDictionaryForRequiredFields()
    {
        final ValidationDictionary validationDictionary = ValidationDictionary.requiredFields(data);
        final IntHashSet heartbeat = validationDictionary.fields('0');

        assertThat(heartbeat, hasItem(115));
        assertThat(heartbeat, hasSize(1));
        assertTrue(validationDictionary.contains('0', 115));
    }

    @Test
    public void buildsValidationDictionaryForAllFields()
    {
        final ValidationDictionary validationDictionary = ValidationDictionary.allFields(data);
        final IntHashSet heartbeat = validationDictionary.fields('0');

        assertThat(heartbeat, hasItem(115));
        assertThat(heartbeat, hasItem(112));
        assertThat(heartbeat, hasSize(2));

        assertTrue(validationDictionary.contains('0', 115));
        assertTrue(validationDictionary.contains('0', 112));
    }

}
