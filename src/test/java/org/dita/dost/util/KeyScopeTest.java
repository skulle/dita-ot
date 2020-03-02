package org.dita.dost.util;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.io.InputStream;
import java.util.List;

import org.junit.Test;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.module.SimpleModule;
import com.fasterxml.jackson.databind.type.CollectionType;
import com.fasterxml.jackson.databind.type.TypeFactory;

public class KeyScopeTest {

    @Test
    public void deserialize() throws Exception {
        // given
        ObjectMapper objectMapper = new ObjectMapper();
        SimpleModule module = new SimpleModule();
        module.addDeserializer(KeyDef.class, new KeyDefDeserializer());
        objectMapper.registerModule(module);
        TypeFactory factory = objectMapper.getTypeFactory();
        
        CollectionType listType = 
        		factory.constructCollectionType(List.class, KeyDef.class);
        
        InputStream stream = KeyScopeTest.class.getResourceAsStream("/KeyScopeTest/keyscope.json");

       
        // when
        List<KeyDef> keyDefList = objectMapper.readValue(stream, listType);

        // then
        assertEquals(2,keyDefList.size());
        assertTrue(keyDefList.get(0).isFiltered());
        assertNotNull(keyDefList.get(0).element);
        assertFalse(keyDefList.get(1).isFiltered());
        assertNotNull(keyDefList.get(1).element);
    }

}
