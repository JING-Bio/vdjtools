package com.antigenomics.vdjtools.annotate

import com.antigenomics.vdjtools.graph.DegreeStatistics
import com.antigenomics.vdjtools.graph.DegreeStatisticsCalculator
import com.antigenomics.vdjtools.misc.ExecUtil
import com.antigenomics.vdjtools.misc.StatUtil
import com.antigenomics.vdjtools.sample.Clonotype
import com.antigenomics.vdjtools.sample.Sample
import groovyx.gpars.GParsPool

class DegreeStatisticsAnnotator {
    final DegreeStatisticsCalculator sampleStatistics, controlStatistics

    static final String HEADER = "count.sample\ttotal.sample\tcount.control\ttotal.control\tp.value"

    DegreeStatisticsAnnotator(DegreeStatisticsCalculator sampleStatistics, DegreeStatisticsCalculator controlStatistics) {
        this.sampleStatistics = sampleStatistics
        this.controlStatistics = controlStatistics
    }

    void annotate(Sample sample) {
        if (sample.annotationHeader) {
            sample.annotationHeader += "\t" + HEADER
            // todo: FIX THREAD COUNT as parameter (?)
            GParsPool.withPool ExecUtil.THREADS, {
                sample.eachParallel { Clonotype clonotype ->
                    def s = sampleStatistics.compute(clonotype),
                        b = controlStatistics.compute(clonotype)
                    clonotype.annotation += "\t" + s + "\t" + b + "\t" + computePValue(s, b)

                }
            }
        } else {
            sample.annotationHeader = HEADER
            GParsPool.withPool ExecUtil.THREADS, {
                sample.eachParallel { Clonotype clonotype ->
                    def s = sampleStatistics.compute(clonotype),
                        b = controlStatistics.compute(clonotype)
                    clonotype.annotation = s.toString() + "\t" + b + "\t" + computePValue(s, b)
                }
            }
        }
    }

    static double computePValue(DegreeStatistics sample, DegreeStatistics background) {
        if (sample == DegreeStatistics.UNDEF || background == DegreeStatistics.UNDEF)
            return 1.0

        // todo: int conversion (?)
        StatUtil.binomialPValue(sample.degree, (int) sample.groupCount,
                ((double) background.degree + 1.0d) / ((double) background.groupCount + 1.0d))
    }
}
