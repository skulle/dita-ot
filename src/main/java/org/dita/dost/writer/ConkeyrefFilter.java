/*
 * This file is part of the DITA Open Toolkit project.
 *
 * Copyright 2013 Jarno Elovirta
 *
 * See the accompanying LICENSE file for applicable license.
 */
package org.dita.dost.writer;

import static org.dita.dost.util.Constants.*;
import static org.dita.dost.util.URLUtils.*;

import java.net.URI;
import java.util.List;

import org.dita.dost.log.MessageUtils;
import org.dita.dost.util.*;
import org.xml.sax.Attributes;
import org.xml.sax.SAXException;
import org.xml.sax.helpers.AttributesImpl;

/**
 * Filter for processing content key reference elements in DITA files. Instances are
 * reusable but not thread-safe.
 */
public final class ConkeyrefFilter extends AbstractXMLFilter {

    private KeyScope keys;
    /** Delayed conref utils, may be {@code null} */
    private DelayConrefUtils delayConrefUtils;

    public void setKeyDefinitions(final KeyScope keys) {
        this.keys = keys;
    }

    public void setDelayConrefUtils(final DelayConrefUtils delayConrefUtils) {
        this.delayConrefUtils = delayConrefUtils;
    }

    // XML filter methods ------------------------------------------------------

    @Override
    public void startElement(final String uri, final String localName, final String name, final Attributes atts)
            throws SAXException {
        AttributesImpl resAtts = null;
        final String conkeyref = atts.getValue(ATTRIBUTE_NAME_CONKEYREF);
        conkeyref: if (conkeyref != null) {
            final int keyIndex = conkeyref.indexOf(SLASH);
            final String key = keyIndex != -1 ? conkeyref.substring(0, keyIndex) : conkeyref;
            final KeyDef keyDef = keys.get(key);
//Comment out for Raffy to work on keyscope merging            
//            KeyDef filteredKeyDef = retrieveFilteredKey(key);
//            if(filteredKeyDef!=null) {
//            	keyDef = filteredKeyDef;
//            }else {
//            	keyDef = keys.get(key);
//            }
            
            if (keyDef != null) {
                final String id = keyIndex != -1 ? conkeyref.substring(keyIndex + 1) : null;
                if (delayConrefUtils != null) {
                    final List<Boolean> list = delayConrefUtils.checkExport(stripFragment(keyDef.href).toString(), id, key, job.tempDir);
                    final boolean idExported = list.get(0);
                    final boolean keyrefExported = list.get(1);
                    //both id and key are exported and transtype is eclipsehelp
                    if (idExported && keyrefExported) {
                        break conkeyref;
                    }
                }
                resAtts = new AttributesImpl(atts);
                XMLUtils.removeAttribute(resAtts, ATTRIBUTE_NAME_CONKEYREF);
                if (keyDef.href != null && (keyDef.scope.equals(ATTR_SCOPE_VALUE_LOCAL))) {
                    URI target = getRelativePath(keyDef.href);
                    final String keyFragment = keyDef.href.getFragment();
                    if (id != null && keyFragment != null) {
                        target = setFragment(target, keyFragment + SLASH + id);
                    } else if (id != null) {
                        target = setFragment(target, id);
                    } else if (keyFragment != null) {
                        target = setFragment(target, keyFragment);
                    }
                    XMLUtils.addOrSetAttribute(resAtts, ATTRIBUTE_NAME_CONREF, target.toString());
                } else {
                    logger.warn(MessageUtils.getMessage("DOTJ060W", key, conkeyref).toString());
                }
            } else {
                logger.error(MessageUtils.getMessage("DOTJ046E", conkeyref).toString());
            }
        }
        getContentHandler().startElement(uri, localName, name, resAtts != null ? resAtts : atts);
    }

    /**
     * Update href URI.
     *
     * @param href href URI
     * @return updated href URI
     */
    private URI getRelativePath(final URI href) {
        final URI keyValue;
        final URI inputMap = job.getFileInfo(fi -> fi.isInput).stream()
                .map(fi -> fi.uri)
                .findFirst()
                .orElse(null);
        if (inputMap != null) {
            final URI tmpMap = job.tempDirURI.resolve(inputMap);
            keyValue = tmpMap.resolve(stripFragment(href));
        } else {
            keyValue = job.tempDirURI.resolve(stripFragment(href));
        }
        return URLUtils.getRelativePath(currentFile, keyValue);
    }

}
