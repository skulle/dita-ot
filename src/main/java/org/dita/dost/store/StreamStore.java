/*
 * This file is part of the DITA Open Toolkit project.
 *
 * Copyright 2020 Jarno Elovirta
 *
 * See the accompanying LICENSE file for applicable license.
 */

package org.dita.dost.store;

import static org.apache.commons.io.FileUtils.copyFile;
import static org.apache.commons.io.FileUtils.forceDelete;
import static org.apache.commons.io.FileUtils.moveFile;
import static org.dita.dost.util.Constants.FILE_EXTENSION_TEMP;
import static org.dita.dost.util.URLUtils.toFile;
import static org.dita.dost.util.URLUtils.toURI;
import static org.dita.dost.util.Constants.*;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.List;

import javax.xml.transform.Source;
import javax.xml.transform.TransformerException;
import javax.xml.transform.stream.StreamSource;

import org.dita.dost.exception.DITAOTException;
import org.dita.dost.util.URLUtils;
import org.dita.dost.util.XMLUtils;
import org.w3c.dom.Document;
import org.w3c.dom.Node;
import org.xml.sax.ContentHandler;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;
import org.xml.sax.XMLFilter;
import org.xml.sax.XMLReader;

import com.google.common.annotations.VisibleForTesting;

import net.sf.saxon.event.PipelineConfiguration;
import net.sf.saxon.event.Receiver;
import net.sf.saxon.event.ReceivingContentHandler;
import net.sf.saxon.s9api.Destination;
import net.sf.saxon.s9api.SAXDestination;
import net.sf.saxon.s9api.SaxonApiException;
import net.sf.saxon.s9api.Serializer;
import net.sf.saxon.s9api.XdmNode;
import net.sf.saxon.s9api.XsltTransformer;
import net.sf.saxon.serialize.SerializationProperties;
import net.sf.saxon.trans.UncheckedXPathException;

/**
 * Stream based XML I/O
 *
 * @since 3.5
 */
public class StreamStore extends AbstractStore implements Store {

    public StreamStore(final File tempDir, final XMLUtils xmlUtils) {
        super(tempDir, xmlUtils);
    }

    @Override
    public Document getImmutableDocument(final URI path) throws IOException {
//        return (Document) NodeOverNodeInfo.wrap(getImmutableNode(path).getUnderlyingNode());
        return getDocument(path);
    }

    @Override
    public XdmNode getImmutableNode(final URI path) throws IOException {
        try {
            return xmlUtils.getProcessor().newDocumentBuilder().build(new StreamSource(path.toString()));
        } catch (SaxonApiException e) {
            throw new IOException(e);
        }
    }

    @Override
    public Document getDocument(final URI path) throws IOException {
        if (LOG) System.err.println("  getDocument:" + path);
        try {
            return XMLUtils.getDocumentBuilder().parse(path.toString());
        } catch (final Exception e) {
            throw new IOException("Failed to read document: " + e.getMessage(), e);
        }
    }

    @Override
    public void delete(final URI file) throws IOException {
        final URI f = getUri(file.normalize());
        if ("file".equals(f.getScheme())) {
            final File ff = new File(getUri(f.isAbsolute() ? f : tempDirUri.resolve(f)));
            if (ff.exists() && !ff.delete()) {
                throw new IOException("Deleting " + file + " failed");
            }
        } else {
            throw new UnsupportedOperationException();
        }
    }

    @Override
    public void writeDocument(final Document doc, final URI dst) throws IOException {
        final XdmNode source = xmlUtils.getProcessor().newDocumentBuilder().wrap(doc);
        writeDocument(source, dst);
    }

    @Override
    public void writeDocument(final XdmNode node, final URI dst) throws IOException {
        try {
            final Serializer serializer = getSerializer(dst);
            serializer.serializeNode(node);
        } catch (SaxonApiException e) {
            throw new IOException(e);
        }
    }

    @Override
    public void writeDocument(final Node doc, final ContentHandler dst) throws IOException {
        final XdmNode source = xmlUtils.getProcessor().newDocumentBuilder().wrap(doc);
        writeDocument(source, dst);
    }

    @Override
    public void writeDocument(final XdmNode source, final ContentHandler dst) throws IOException {
        try {
            final SAXDestination destination = new SAXDestination(dst);
            xmlUtils.getProcessor().writeXdmValue(source, destination);
        } catch (SaxonApiException e) {
            throw new IOException(e);
        }
    }
    
    @Override
    public void transform(final URI input, final ContentHandler contentHandler) throws DITAOTException {
    	transform(input, contentHandler, true);
    }
    
    public void transform(final URI input, final ContentHandler contentHandler, boolean namespaceAware) throws DITAOTException {
        assert input.isAbsolute();
        if (!input.getScheme().equals("file")) {
            throw new IllegalArgumentException("Only file URI scheme supported: " + input);
        }

        try {
            final XMLReader xmlReader = XMLUtils.getXMLReader();
            xmlReader.setFeature(FEATURE_NAMESPACE_PREFIX, namespaceAware);
            xmlReader.setContentHandler(contentHandler);
            xmlReader.parse(input.toString());
        } catch (SAXException | IOException e) {
            throw new DITAOTException(e);
        }
    }

//    @Override
//    public void transform(final URI input, final List<XMLFilter> filters) throws DITAOTException {
//        assert input.isAbsolute();
//        if (!input.getScheme().equals("file")) {
//            throw new IllegalArgumentException("Only file URI scheme supported: " + input);
//        }
//
//        final File inputFile = new File(input);
//        final File outputFile = new File(inputFile.getAbsolutePath() + FILE_EXTENSION_TEMP);
//        transformURI(inputFile.toURI(), outputFile.toURI(), filters);
//        try {
//            deleteQuietly(inputFile);
//            moveFile(outputFile, inputFile);
//        } catch (final IOException e) {
//            throw new DITAOTException("Failed to replace " + inputFile + ": " + e.getMessage());
//        }
//    }
//
//    @Override
//    public void transform(final URI input, final URI output, final List<XMLFilter> filters) throws DITAOTException {
//        if (input.equals(output)) {
//            transform(input, filters);
//        } else {
//            transformURI(input, output, filters);
//        }
//    }

    @Override
    void transformURI(final URI input, final URI output, final List<XMLFilter> filters) throws DITAOTException {
        final File outputFile = new File(output);
        if (!outputFile.getParentFile().exists() && !outputFile.getParentFile().mkdirs()) {
            throw new DITAOTException("Failed to create output directory " + outputFile.getParentFile().getAbsolutePath());
        }

        try {
            XMLReader reader = xmlUtils.getXMLReader();
            for (final XMLFilter filter : filters) {
                // ContentHandler must be reset so e.g. Saxon 9.1 will reassign ContentHandler
                // when reusing filter with multiple Transformers.
                filter.setContentHandler(null);
                filter.setParent(reader);
                reader = filter;
            }

            final Serializer result = getSerializer(output);
            final ContentHandler serializer = result.getContentHandler();
            reader.setContentHandler(serializer);

            final InputSource inputSource = new InputSource(input.toString());

            reader.parse(inputSource);
        } catch (final RuntimeException e) {
            throw e;
        } catch (final Exception e) {
            throw new DITAOTException("Failed to transform " + input + ": " + e.getMessage(), e);
        }
    }

    @Override
    public void transform(final URI input, final URI output, final XsltTransformer transformer) throws DITAOTException {
        final URI src = input.normalize();
        final URI dst = output.normalize();
        if (src.equals(dst)) {
            transform(src, transformer);
        } else {
            transformUri(src, dst, transformer);
        }
    }

    @Override
    public void transform(final URI src, final XsltTransformer transformer) throws DITAOTException {
        final URI dst = toURI(src.toString() + FILE_EXTENSION_TEMP).normalize();
        transformUri(src, dst, transformer);
        try {
            move(dst, src);
        } catch (final IOException e) {
            throw new DITAOTException("Failed to replace " + src + ": " + e.getMessage());
        }
    }

    @Override
    void transformUri(final URI src, final URI dst, final XsltTransformer transformer) throws DITAOTException {
        try {
            final Source source = getSource(src);
            transformer.setSource(source);
            final Destination result = getDestination(dst);
            transformer.setDestination(result);
            transformer.transform();
        } catch (final UncheckedXPathException e) {
            throw new DITAOTException("Failed to transform document", e);
        } catch (final RuntimeException e) {
            throw e;
        } catch (final SaxonApiException e) {
            throw new DITAOTException("Failed to transform document: " + e.getMessage(), e);
        } catch (final Exception e) {
            throw new DITAOTException("Failed to transform document: " + e.getMessage(), e);
        }
    }

    @VisibleForTesting
    Serializer getSerializer(final URI dst) throws IOException {
        final File outputFile = new File(dst);
        final File dir = outputFile.getParentFile();
        if (!dir.exists() && !dir.mkdirs()) {
            throw new IOException("Failed to create directory " + dir.getAbsolutePath());
        }
        return xmlUtils.getProcessor().newSerializer(outputFile);
    }

    @Override
    public Source getSource(final URI path) {
        final URI f = getUri(path);
        if (isTempFile(f)) {
            if (LOG) System.err.println("  getSource:" + f);
            final Source s = new StreamSource(f.toString());
            s.setSystemId(f.toString());
            return s;
        } else {
            if (LOG) System.err.println("  getSource:" + path);
            return new StreamSource(path.toString());
        }
    }

    @Override
    public Destination getDestination(URI path) throws IOException {
        return getSerializer(path);
    }

    @Override
    public ContentHandler getContentHandler(final URI outputFile) throws SaxonApiException, IOException {
        final net.sf.saxon.Configuration configuration = xmlUtils.getProcessor().getUnderlyingConfiguration();
//        final SerializerFactory sf = configuration.getSerializerFactory();
        final PipelineConfiguration pipelineConfiguration = configuration.makePipelineConfiguration();

        final Destination dst = getDestination(outputFile);
        final Receiver receiver = dst.getReceiver(pipelineConfiguration, new SerializationProperties());
//        final Result out = job.getStore().getResult(outputFile);
//        final Receiver receiver = sf.getReceiver(out, new SerializationProperties());

        final ReceivingContentHandler receivingContentHandler = new ReceivingContentHandler();
        receivingContentHandler.setPipelineConfiguration(pipelineConfiguration);
        receivingContentHandler.setReceiver(receiver);

        return receivingContentHandler;
    }

    public void copy(final URI src, final URI dst) throws IOException {
        final File s = new File(getUri((src.isAbsolute() ? src : tempDirUri.resolve(src)).normalize()));
        final File d = new File(getUri((dst.isAbsolute() ? dst : tempDirUri.resolve(dst)).normalize()));
        copyFile(s, d);
    }

    @Override
    public void move(final URI src, final URI dst) throws IOException {
        final File s = new File(getUri((src.isAbsolute() ? src : tempDirUri.resolve(src)).normalize()));
        final File d = new File(getUri((dst.isAbsolute() ? dst : tempDirUri.resolve(dst)).normalize()));
        if (d.exists()) {
            forceDelete(d);
        }
        moveFile(s, d);
    }

    @Override
    public boolean exists(final URI path) {
        final File d = new File(getUri(URLUtils.setFragment(path, null)));
        return d.exists();
    }

    @Override
    public long getLastModified(final URI path) {
        final File d = new File(getUri(URLUtils.setFragment(path, null)));
        return d.lastModified();
    }

    @Override
    public Source resolve(final String href, final String base) throws TransformerException {
        final URI h = toURI(href);
        final URI f = h.isAbsolute() ? h : toURI(base).resolve(h);
        if (LOG) System.err.println("  resolve: " + f);
        if (isTempFile(f)) {
            return new StreamSource(f.toString());
        }
        return null;
    }

    @Override
    public InputStream getInputStream(final URI path) throws IOException {
        final URI f = getUri(path);
        if (isTempFile(f)) {
            if (LOG) System.err.println("  getInputStream:" + f);
            return new FileInputStream(toFile(f));
        } else if ("file".equals(path.getScheme())) {
            if (LOG) System.err.println("  getInputStream:" + path);
            return new FileInputStream(toFile(path));
        } else {
            if (LOG) System.err.println("  getInputStream:" + f);
            return f.toURL().openStream();
        }
    }

    @Override
    public OutputStream getOutputStream(final URI path) throws IOException {
        final URI f = getUri(path);
        if (isTempFile(f)) {
            if (LOG) System.err.println("  getOutputStream:" + f);
            return Files.newOutputStream(Paths.get(f));
        } else if ("file".equals(path.getScheme())) {
            if (LOG) System.err.println("  getOutputStream:" + path);
            return Files.newOutputStream(Paths.get(path));
        } else {
            if (LOG) System.err.println("  getOutputStream:" + f);
            throw new UnsupportedOperationException("Unable to write to " + f);
//            return f.toURL().openStream();
        }
    }
}
