/*
 * This file is part of the DITA Open Toolkit project.
 *
 * Copyright 2015 Jarno Elovirta
 *
 * See the accompanying LICENSE file for applicable license.
 */
// Dummy class to allow compiling against RenderX

package com.renderx.xep;

import com.renderx.xep.lib.ConfigurationException;
import com.renderx.xep.lib.Logger;
import org.xml.sax.ContentHandler;
import org.xml.sax.SAXException;

import javax.xml.transform.Source;
import java.io.IOException;

public interface Formatter {
    void render(Source var1, FOTarget var2) throws SAXException, IOException;

    void render(Source var1, FOTarget var2, Logger var3) throws SAXException, IOException;

    ContentHandler createContentHandler(String var1, FOTarget var2) throws ConfigurationException;

    ContentHandler createContentHandler(String var1, FOTarget var2, Logger var3) throws SAXException, IOException;
}
