/*
 * This file is part of the DITA Open Toolkit project.
 *
 * Copyright 2013 Jarno Elovirta
 *
 * See the accompanying LICENSE file for applicable license.
 */
package org.dita.dost.util;

import static org.junit.Assert.*;
import static org.dita.dost.util.URLUtils.*;

import java.net.URI;
import java.net.URISyntaxException;

import org.junit.Test;

public class KeyDefTest {

    @Test
    public void testKeyDefStringStringString() throws URISyntaxException {
        final KeyDef k = new KeyDef("foo", toURI("bar"), "scope", "dita", toURI("baz"), null,null);
        assertEquals("foo", k.keys);
        assertEquals(new URI("bar"), k.href);
        assertEquals("scope", k.scope);
        assertEquals(new URI("baz"), k.source);
        final KeyDef n = new KeyDef("foo", (URI) null, null, null, (URI) null, null, null);
        assertEquals("foo", n.keys);
        assertNull(n.href);
        assertEquals("local", n.scope);
        assertNull(n.source);
    }
    
    @Test
    public void testKeyDefToString() {
        final KeyDef k = new KeyDef("foo", toURI("bar"), "scope","dita", toURI("baz"), null, null);
        assertEquals("foo=bar(scope)(baz)", k.toString());
        final KeyDef n = new KeyDef("foo", (URI) null, null, null, (URI) null, null, null);
        assertEquals("foo=(local)", n.toString());
    }

}
