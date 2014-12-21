/*
 * Copyright 2013-2014 Mikhail Shugay (mikhail.shugay@gmail.com)
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
 *
 * Last modified on 15.11.2014 by mikesh
 */

package com.antigenomics.vdjtools.io

import com.antigenomics.vdjtools.Software
import com.antigenomics.vdjtools.sample.Sample
import com.antigenomics.vdjtools.sample.metadata.SampleMetadata
import com.antigenomics.vdjtools.util.CommonUtil
import com.antigenomics.vdjtools.util.ExecUtil

import static com.antigenomics.vdjtools.sample.metadata.MetadataUtil.createSampleMetadata
import static com.antigenomics.vdjtools.sample.metadata.MetadataUtil.fileName2id

/**
 A semi-internal class to provide lazy-loading support for SampleCollection
 */
class SampleFileConnection implements SampleConnection {
    private final String fileName
    private final SampleMetadata sampleMetadata
    private final Software software
    private Sample _sample = null
    private final boolean lazy, store

    public static Sample load(String fileName, Software software) {
        new SampleFileConnection(fileName, software)._load()
    }

    public static Sample load(String fileName, Software software, SampleMetadata sampleMetadata) {
        new SampleFileConnection(fileName, software, sampleMetadata)._load()
    }

    SampleFileConnection(String fileName, Software software) {
        this(fileName, software, createSampleMetadata(fileName2id(fileName)))
    }

    SampleFileConnection(String fileName, Software software, SampleMetadata sampleMetadata) {
        this(fileName, software, sampleMetadata, false, true)
    }

    SampleFileConnection(String fileName, Software software, SampleMetadata sampleMetadata,
                         boolean lazy, boolean store) {
        this.fileName = fileName
        // todo: make access to metadata as explicit as possible??
        this.sampleMetadata = sampleMetadata
        this.software = software
        this.lazy = lazy
        this.store = store

        if (!lazy)
            _sample = _load()
    }

    private Sample _load() {
        println "[${new Date()} SampleStreamConnection] Loading sample $sampleMetadata.sampleId"
        def inputStream = CommonUtil.getFileStream(fileName)
        def sample = Sample.fromInputStream(inputStream, sampleMetadata, software)
        println "[${new Date()} SampleStreamConnection] Loaded sample $sampleMetadata.sampleId with " +
                "$sample.diversity clonotypes and $sample.count cells. " + ExecUtil.memoryFootprint()
        sample
    }

    @Override
    public Sample getSample() {
        _sample ?: (store ? (_sample = _load()) : _load())
    }

    @Override
    public Sample haveAGlance() {
        _sample ?: Sample.fromInputStream(CommonUtil.getFileStream(fileName), null, software, -1, false)
    }
}