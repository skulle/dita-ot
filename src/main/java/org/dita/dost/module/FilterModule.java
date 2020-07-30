/*
 * This file is part of the DITA Open Toolkit project.
 *
 * Copyright 2013 Jarno Elovirta
 *
 * See the accompanying LICENSE file for applicable license.
 */
package org.dita.dost.module;

import org.apache.tools.ant.util.FileUtils;
import org.dita.dost.exception.DITAOTException;
import org.dita.dost.pipeline.AbstractPipelineInput;
import org.dita.dost.pipeline.AbstractPipelineOutput;
import org.dita.dost.reader.DitaValReader;
import org.dita.dost.reader.SubjectSchemeReader;
import org.dita.dost.util.FilterUtils;
import org.dita.dost.util.Job.FileInfo;
import org.dita.dost.writer.ProfilingFilter;

import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.dita.dost.util.Configuration.printTranstype;
import static org.dita.dost.util.Constants.*;
import static org.dita.dost.util.FilterUtils.SUBJECT_SCHEME_EXTENSION;

/**
 * Filter module class.
 */
final class FilterModule extends AbstractPipelineModuleImpl {

    @Override
    public AbstractPipelineOutput execute(final AbstractPipelineInput input) throws DITAOTException {
        if (logger == null) {
            throw new IllegalStateException("Logger not set");
        }

        FilterUtils filterUtils = newFilterUtils(input);
        Map<URI, Set<URI>> dictionary = readSubjectDictionary();

        job.getFileInfo(fileInfoFilter).stream().parallel().forEach(fileInfo -> {
            final ProfilingFilter writer = newProfilingFilter(filterUtils);
            final SubjectSchemeReader subjectSchemeReader = new SubjectSchemeReader(logger);
            final File file = new File(job.tempDir, fileInfo.file.getPath());
            logger.debug("Processing " + file.getAbsolutePath());
            loadSubjectSchemes(dictionary, fileInfo, subjectSchemeReader);
            filterFile(filterUtils, fileInfo, writer, subjectSchemeReader, file);
        });

        updateJobFile();
        return null;
    }

    private FilterUtils newFilterUtils(AbstractPipelineInput input) {
        final String transtype = input.getAttribute(ANT_INVOKER_EXT_PARAM_TRANSTYPE);
        final File ditavalFile = Optional.of(new File(job.tempDir, FILE_NAME_MERGED_DITAVAL))
                .filter(File::exists)
                .orElse(null);
        final DitaValReader ditaValReader = new DitaValReader();
        ditaValReader.setLogger(logger);
        ditaValReader.setJob(job);

        final FilterUtils filterUtils;
        if (ditavalFile != null) {
            ditaValReader.read(ditavalFile.toURI());
            filterUtils = new FilterUtils(printTranstype.contains(transtype), ditaValReader.getFilterMap(),
                    ditaValReader.getForegroundConflictColor(), ditaValReader.getBackgroundConflictColor());
        } else {
            filterUtils = new FilterUtils(printTranstype.contains(transtype));
        }
        filterUtils.setLogger(logger);
        return filterUtils;
    }

    private Map<URI, Set<URI>> readSubjectDictionary() throws DITAOTException {
        Map<URI, Set<URI>> dic;
        try {
            dic = SubjectSchemeReader.readMapFromXML(new File(job.tempDir, FILE_NAME_SUBJECT_DICTIONARY));
        } catch (final IOException e) {
            throw new DITAOTException(e);
        }
        return dic;
    }

    private ProfilingFilter newProfilingFilter(FilterUtils filterUtils) {
        final ProfilingFilter writer = new ProfilingFilter();
        writer.setLogger(logger);
        writer.setJob(job);
        writer.setFilterUtils(filterUtils);
        return writer;
    }

    private void loadSubjectSchemes(Map<URI, Set<URI>> dic, FileInfo f, SubjectSchemeReader subjectSchemeReader) {
        final Set<URI> schemaSet = dic.get(f.uri);
        if (schemaSet != null && !schemaSet.isEmpty()) {
            logger.info("Loading subject schemes");
            for (final URI schema : schemaSet) {
                final File scheme = new File(job.tempDirURI.resolve(schema.getPath() + SUBJECT_SCHEME_EXTENSION));
                if (scheme.exists()) {
                    subjectSchemeReader.loadSubjectScheme(scheme);
                }
            }
        }
    }

    private void filterFile(FilterUtils filterUtils, FileInfo f, ProfilingFilter writer, SubjectSchemeReader subjectSchemeReader, File file) {
        writer.setFilterUtils(filterUtils.refine(subjectSchemeReader.getSubjectSchemeMap()));
        writer.setCurrentFile(file.toURI());
        try {
            writer.write(file.getAbsoluteFile());
            if (!writer.hasElementOutput()) {
                logger.info("All content in " + file.getAbsolutePath() + " was filtered out");
                job.remove(f);
                FileUtils.delete(file);
            }
        } catch (final Exception e) {
            logger.error("Failed to profile " + file.getAbsolutePath() + ": " + e.getMessage());
        }
    }

    private void updateJobFile() throws DITAOTException {
        try {
            job.write();
        } catch (final IOException e) {
            throw new DITAOTException(e);
        }
    }

}
