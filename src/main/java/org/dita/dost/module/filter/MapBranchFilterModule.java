/*
 * This file is part of the DITA Open Toolkit project.
 *
 * Copyright 2016 Jarno Elovirta
 *
 *  See the accompanying LICENSE file for applicable license.
 */

package org.dita.dost.module.filter;

import org.dita.dost.exception.DITAOTException;
import org.dita.dost.log.MessageUtils;
import org.dita.dost.module.BranchFilterModule.Branch;
import org.dita.dost.pipeline.AbstractPipelineInput;
import org.dita.dost.pipeline.AbstractPipelineOutput;
import org.dita.dost.util.Job.FileInfo;
import org.dita.dost.util.XMLUtils;
import org.w3c.dom.*;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.transform.*;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import java.io.IOException;
import java.net.URI;
import java.util.*;

import static java.util.Collections.EMPTY_LIST;
import static org.dita.dost.util.Constants.*;
import static org.dita.dost.util.URLUtils.stripFragment;
import static org.dita.dost.util.URLUtils.toURI;
import static org.dita.dost.util.XMLUtils.close;
import static org.dita.dost.util.XMLUtils.getChildElements;

/**
 * Branch filter module for map processing.
 *
 * <p>Branch filtering is done with the following steps:</p>
 * <ol>
 *   <li>Split braches so that each branch will only contain a single ditavalref</li>
 *   <li>Generate copy-to attribute for each brach generated topicref</li>
 *   <li>Filter map based on branch filters</li>
 *   <li>Rewrite duplicate generated copy-to targets</li>
 * </ol>
 *
 * @since 2.5
 */
public class MapBranchFilterModule extends AbstractBranchFilterModule {

    private static final String BRANCH_COPY_TO = "filter-copy-to";

    private final DocumentBuilder builder;

    /** Current map being processed, relative to temporary directory */
    private URI map;

    public MapBranchFilterModule() {
        builder = XMLUtils.getDocumentBuilder();
    }

    @Override
    public AbstractPipelineOutput execute(final AbstractPipelineInput input) throws DITAOTException {
        final FileInfo fi = job.getFileInfo(f -> f.isInput).iterator().next();
        if (!ATTR_FORMAT_VALUE_DITAMAP.equals(fi.format)) {
            return null;
        }
        processMap(fi);

        try {
            job.write();
        } catch (final IOException e) {
            throw new DITAOTException("Failed to serialize job configuration: " + e.getMessage(), e);
        }

        return null;
    }

    /**
     * Process map for branch replication.
     */
    protected void processMap(final FileInfo fi) {
        map = fi.uri;
        currentFile = job.tempDirURI.resolve(map);

        logger.info("Processing " + currentFile);
        final Document doc;
        try {
            logger.debug("Reading " + currentFile);
            doc = builder.parse(new InputSource(currentFile.toString()));
        } catch (final SAXException | IOException e) {
            logger.error("Failed to parse " + currentFile, e);
            return;
        }

        splitBranches(doc);
        filterBranches(doc);
        rewriteDuplicates(doc);
        writeDocumentToDisk(doc);
    }

    private void splitBranches(Document doc) {
        logger.debug("Split branches and generate copy-to");
        splitBranches(doc.getDocumentElement(), Branch.EMPTY);
    }

    private void filterBranches(Document doc) {
        logger.debug("Filter map");
        new MapBranchFilter(logger, currentFile).filterBranches(doc.getDocumentElement());
    }

    private void rewriteDuplicates(Document doc) {
        logger.debug("Rewrite duplicate topic references");
        rewriteDuplicates(doc.getDocumentElement());
    }

    private void writeDocumentToDisk(Document doc) {
        logger.debug("Writing " + currentFile);
        Result result = null;
        try {
            Transformer serializer = TransformerFactory.newInstance().newTransformer();
            result = new StreamResult(currentFile.toString());
            serializer.transform(new DOMSource(doc), result);
        } catch (final TransformerConfigurationException | TransformerFactoryConfigurationError e) {
            throw new RuntimeException(e);
        } catch (final TransformerException e) {
            logger.error("Failed to serialize " + map.toString() + ": " + e.getMessage(), e);
        } finally {
            try {
                close(result);
            } catch (final IOException e) {
                logger.error("Failed to close result stream for " + map.toString() + ": " + e.getMessage(), e);
            }
        }
    }

    /** Rewrite href or copy-to if duplicates exist. */
    private void rewriteDuplicates(final Element documentElement) {
        Map<URI, Map<Set<URI>, List<Attr>>> topicrefs = collectCopyToOrHref(documentElement);

        // check and rewrite
        topicrefs.entrySet().parallelStream().forEach(topicref -> {
            Map<Set<URI>, List<Attr>> branchFilterMap = topicref.getValue();

            if (branchFilterMap.size()<=1) {
                return;
            }

            if (branchFilterMap.containsKey(EMPTY_LIST)) {
                branchFilterMap.remove(EMPTY_LIST);
            } else {
                Set<URI> first = branchFilterMap.keySet().iterator().next();
                branchFilterMap.remove(first);
            }

            int i = 1;
            for (final Map.Entry<Set<URI>, List<Attr>> attrsMap: branchFilterMap.entrySet()) {
                String suffix = "-" + i;

                for (Attr attr: attrsMap.getValue()) {
                    String gen = addSuffix(attr.getValue(), suffix);
                    logger.info(MessageUtils.getMessage("DOTJ065I", attr.getValue(), gen).setLocation(attr.getOwnerElement()).toString());

                    if (attr.getName().equals(BRANCH_COPY_TO)) {
                        attr.setValue(gen);
                    } else {
                        attr.getOwnerElement().setAttribute(BRANCH_COPY_TO, gen);
                    }

                    URI dstUri = map.resolve(gen);
                    if (dstUri != null) {
                        final FileInfo hrefFileInfo = job.getFileInfo(currentFile.resolve(attr.getValue()));
                        if (hrefFileInfo != null) {
                            URI newResult = addSuffix(hrefFileInfo.result, suffix);
                            FileInfo.Builder dstBuilder = new FileInfo.Builder(hrefFileInfo).uri(dstUri).result(newResult);
                            if (hrefFileInfo.format == null) {
                                dstBuilder.format(ATTR_FORMAT_VALUE_DITA);
                            }
                            job.add(dstBuilder.build());
                        }
                    }
                }
                i++;
            }
        });
    }

    /**
     * key: topicref's link (either filter-copy-to or copy-to or href)
     * value: map: set of ditaval hrefs -> topicref's link attribute node
     */
    private Map<URI, Map<Set<URI>, List<Attr>>> collectCopyToOrHref(Element documentElement) {
        Map<URI, Map<Set<URI>, List<Attr>>> refs = new HashMap<>();

        for (Element topicRef: getTopicrefs(documentElement)) {
            Attr attr = topicRef.getAttributeNode(BRANCH_COPY_TO);
            if (attr == null) {
                attr = topicRef.getAttributeNode(ATTRIBUTE_NAME_COPY_TO);
                if (attr == null) {
                    attr = topicRef.getAttributeNode(ATTRIBUTE_NAME_HREF);
                }
            }
            if (attr != null) {
                URI href = stripFragment(map.resolve(attr.getValue()));
                Map<Set<URI>, List<Attr>> attrsMap = refs.computeIfAbsent(href, k -> new HashMap<>());
                Set<URI> currentFilter = getBranchFilters(topicRef);
                List<Attr> attrs = attrsMap.computeIfAbsent(currentFilter, k -> new ArrayList<>());
                attrs.add(attr);
            }
        }
        return refs;
    }

    /**
     * Collects ditavalref's hrefs for current element up to the root element, i.e. ancestor-or-self axis
     * @param e current element
     */
    private Set<URI> getBranchFilters(final Element e) {
        final Set<URI> res = new HashSet<>();
        Element current = e;
        while (current != null) {
            final List<Element> ditavalref = getChildElements(current, DITAVAREF_D_DITAVALREF);
            if (!ditavalref.isEmpty()) {
                res.add(toURI(ditavalref.get(0).getAttribute(ATTRIBUTE_NAME_HREF)));
            }
            final Node parent = current.getParentNode();
            if (parent != null && parent.getNodeType() == Node.ELEMENT_NODE) {
                current = (Element) parent;
            } else {
                break;
            }
        }
        return res;
    }

    /** Add suffix to file name */
    private static String addSuffix(final String href, final String suffix) {
        final int idx = href.lastIndexOf(".");
        return idx != -1
                ? (href.substring(0, idx) + suffix + href.substring(idx))
                : (href + suffix);
    }

    /** Add suffix to file name */
    private static URI addSuffix(final URI href, final String suffix) {
        return URI.create(addSuffix(href.toString(), suffix));
    }

    /** Get all topicrefs */
    private List<Element> getTopicrefs(final Element root) {
        final List<Element> res = new ArrayList<>();
        final NodeList all = root.getElementsByTagName("*");
        for (int i = 0; i < all.getLength(); i++) {
            final Element elem = (Element) all.item(i);
            if (MAP_TOPICREF.matches(elem)
                    && isDitaFormat(elem.getAttributeNode(ATTRIBUTE_NAME_FORMAT))
                    && !elem.getAttribute(ATTRIBUTE_NAME_SCOPE).equals(ATTR_SCOPE_VALUE_EXTERNAL)) {
                res.add(elem);
            }
        }
        return res;
    }

    private boolean isDitaFormat(final Attr formatAttr) {
        return formatAttr == null ||
                ATTR_FORMAT_VALUE_DITA.equals(formatAttr.getNodeValue()) ||
                ATTR_FORMAT_VALUE_DITAMAP.equals(formatAttr.getNodeValue());
    }

    /**
     * Duplicate branches so that each {@code ditavalref} will in a separate branch.
     */
    void splitBranches(final Element elem, final Branch filter) {
        final List<Element> ditavalRefs = getChildElements(elem, DITAVAREF_D_DITAVALREF);
        if (ditavalRefs.size() > 0) {
            // remove ditavalrefs
            for (final Element branch: ditavalRefs) {
                elem.removeChild(branch);
            }
            // create additional branches after current element
            final List<Element> branches = new ArrayList<>(ditavalRefs.size());
            branches.add(elem);
            final Node next = elem.getNextSibling();
            for (int i = 1; i < ditavalRefs.size(); i++) {
                final Element clone = (Element) elem.cloneNode(true);
                if (next != null) {
                    elem.getParentNode().insertBefore(clone, next);
                } else {
                    elem.getParentNode().appendChild(clone);
                }
                branches.add(clone);
            }
            // insert ditavalrefs
            for (int i = 0; i < branches.size(); i++) {
                final Element branch = branches.get(i);
                final Element ditavalref = ditavalRefs.get(i);
                branch.insertBefore(ditavalref, branch.getFirstChild());
                final Branch currentFilter = filter.merge(ditavalref);
                processAttributes(branch, currentFilter);
                final Branch childFilter = new Branch(currentFilter.resourcePrefix, currentFilter.resourceSuffix, Optional.empty(), Optional.empty());
                // process children of all branches
                for (final Element child: getChildElements(branch, MAP_TOPICREF)) {
                    if (DITAVAREF_D_DITAVALREF.matches(child)) {
                        continue;
                    }
                    splitBranches(child, childFilter);
                }
            }
        } else {
            processAttributes(elem, filter);
            for (final Element child: getChildElements(elem, MAP_TOPICREF)) {
                splitBranches(child, filter);
            }
        }
    }

    private void processAttributes(final Element elem, final Branch filter) {
        if (filter.resourcePrefix.isPresent() || filter.resourceSuffix.isPresent()) {
            final String href = elem.getAttribute(ATTRIBUTE_NAME_HREF);
            final String copyTo = elem.getAttribute(ATTRIBUTE_NAME_COPY_TO);
            final String scope = elem.getAttribute(ATTRIBUTE_NAME_SCOPE);
            if ((!href.isEmpty() || !copyTo.isEmpty()) && !scope.equals(ATTR_SCOPE_VALUE_EXTERNAL)) {
                final FileInfo hrefFileInfo = job.getFileInfo(currentFile.resolve(href));

                final FileInfo copyToFileInfo = !copyTo.isEmpty() ? job.getFileInfo(currentFile.resolve(copyTo)) : null;

                final URI dstSource;
                dstSource = generateCopyTo((copyToFileInfo != null ? copyToFileInfo : hrefFileInfo).result, filter);
                final URI dstTemp = tempFileNameScheme.generateTempFileName(dstSource);
                final FileInfo.Builder dstBuilder = new FileInfo.Builder(hrefFileInfo)
                        .result(dstSource)
                        .uri(dstTemp);
                if (dstBuilder.build().format == null) {
                    dstBuilder.format(ATTR_FORMAT_VALUE_DITA);
                }
                if (hrefFileInfo.src == null && href != null) {
                    if (copyToFileInfo != null) {
                        dstBuilder.src(copyToFileInfo.src);
                    }
                }
                final FileInfo dstFileInfo = dstBuilder
                        .build();

                elem.setAttribute(BRANCH_COPY_TO, dstTemp.toString());
                if (!copyTo.isEmpty()) {
                    elem.removeAttribute(ATTRIBUTE_NAME_COPY_TO);
                }

                job.add(dstFileInfo);
            }
        }

        if (filter.keyscopePrefix.isPresent() || filter.keyscopeSuffix.isPresent()) {
            final StringBuilder buf = new StringBuilder();
            final String keyscope = elem.getAttribute(ATTRIBUTE_NAME_KEYSCOPE);
            if (!keyscope.isEmpty()) {
                for (final String key : keyscope.trim().split("\\s+")) {
                    filter.keyscopePrefix.ifPresent(buf::append);
                    buf.append(key);
                    filter.keyscopeSuffix.ifPresent(buf::append);
                    buf.append(' ');
                }
            } else {
                filter.keyscopePrefix.ifPresent(buf::append);
                filter.keyscopeSuffix.ifPresent(buf::append);
            }
            elem.setAttribute(ATTRIBUTE_NAME_KEYSCOPE, buf.toString().trim());
        }
    }

    static URI generateCopyTo(final URI href, final Branch filter) {
        final StringBuilder buf = new StringBuilder(href.toString());
        final Optional<String> suffix = filter.resourceSuffix;
        suffix.ifPresent(s -> {
            final int sep = buf.lastIndexOf(URI_SEPARATOR);
            final int i = buf.lastIndexOf(".");
            if (i != -1 && (sep == -1 || i > sep)) {
                buf.insert(i, s);
            } else {
                buf.append(s);
            }
        });
        final Optional<String> prefix = filter.resourcePrefix;
        prefix.ifPresent(s -> {
            final int i = buf.lastIndexOf(URI_SEPARATOR);
            if (i != -1) {
                buf.insert(i + 1, s);
            } else {
                buf.insert(0, s);
            }
        });
        return toURI(buf.toString());
    }

}
