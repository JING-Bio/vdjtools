/**
 Copyright 2014 Mikhail Shugay (mikhail.shugay@gmail.com)

 Licensed under the Apache License, Version 2.0 (the "License");
 you may not use this file except in compliance with the License.
 You may obtain a copy of the License at

 http://www.apache.org/licenses/LICENSE-2.0

 Unless required by applicable law or agreed to in writing, software
 distributed under the License is distributed on an "AS IS" BASIS,
 WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 See the License for the specific language governing permissions and
 limitations under the License.
 */

package com.antigenomics.vdjtools.graph

import com.antigenomics.vdjtools.Util
import com.antigenomics.vdjtools.igblast.ClonotypeParsedData
import com.antigenomics.vdjtools.igblast.MutationParseData

def FREQ_THRESHOLD = "0.40", SPEC_THRESHOLD = "3"
def cli = new CliBuilder(usage: "IgBlastNet [options] igblast_output_level2 output_prefix")
cli.h("display help message")
cli.S(longOpt: "species", argName: "string",
        "Species for which partitioning info on IG regions (FWs and CDRs) will be loaded. " +
                "Possible values: human [default], mouse, rabbit and rat.")
cli._(longOpt: "allele-freq", argName: "[0, 1]", "Frequency threshold, used together with spectratype threshold. " +
        "Mutations with higher frequency are considered as allele candidates. [default=$FREQ_THRESHOLD]")
cli._(longOpt: "allele-spectra", argName: "int", "Spectratype threshold, used together with frequency threshold. " +
        "At least \$allele-spectra clonotypes with distinct CDR3 lengths must contain this mutation " +
        "for it to be considered as allele. [default=$SPEC_THRESHOLD]")
def opt = cli.parse(args)

if (opt.h || opt == null || opt.arguments().size() < 2) {
    cli.usage()
    System.exit(-1)
}

def scriptName = getClass().canonicalName.split("\\.")[-1]

def species = (opt.S ?: "human").toLowerCase(),
    freqThreshold = (opt.'allele-freq' ?: FREQ_THRESHOLD).toDouble(),
    spectraThreshold = (opt.'allele-spectra' ?: SPEC_THRESHOLD).toInteger()

String inputFileNameL2 = opt.arguments()[0],
       outputPrefix = opt.arguments()[1]

if (!new File(inputFileNameL2).exists()) {
    println "[ERROR] Input file does not exist: $inputFileNameL2"
}

//
// Allele statistics
//

class MutCounter {
    double freq = 0
    final Set<Integer> cdr3Len = new HashSet<>()
}

def freqByV = new HashMap<String, Double>(),
    freqByMutByV = new HashMap<String, Map<MutationParseData, MutCounter>>()

//
// Read and parse clonotypes
//

def clonotypeMap = new HashMap<String, ClonotypeParsedData>(),
    byCdr3Map = new HashMap<String, List<ClonotypeParsedData>>()

println "[${new Date()} $scriptName] Loading L2 clonotypes"

new File(inputFileNameL2).eachLine { line ->
    if (!line.startsWith("#")) {
        def clonotype = new ClonotypeParsedData(line)

        if (clonotypeMap.containsKey(clonotype.key)) {
            println "[WARNING] Duplicate clonotype (identical CDR3 + mutations) found: " +
                    "${clonotype.displayName}. Further considering only the one with highest count."

            clonotypeMap[clonotype.key].count += clonotype.count
            clonotypeMap[clonotype.key].freq += clonotype.freq
        } else {
            clonotypeMap.put(clonotype.key, clonotype)

            freqByV.put(clonotype.v, (freqByV[clonotype.v] ?: 0) + clonotype.freq)

            def freqByMut = freqByMutByV[clonotype.v]

            if (freqByMut == null)
                freqByMutByV.put(clonotype.v, freqByMut = new HashMap<MutationParseData, MutCounter>())

            clonotype.mutations.each { mpd ->
                def mc = freqByMut[mpd]
                if (mc == null)
                    freqByMut.put(mpd, mc = new MutCounter())
                mc.freq += clonotype.freq
                mc.cdr3Len.add(clonotype.cdr3nt.length())
            }

            def clonotypeList = byCdr3Map[clonotype.cdr3nt]
            if (clonotypeList == null)
                byCdr3Map.put(clonotype.cdr3nt, clonotypeList = new ArrayList<ClonotypeParsedData>())
            clonotypeList.add(clonotype)
        }
    }
}

//
// Deduce alleles
//

println "[${new Date()} $scriptName] Deducing alleles"

clonotypeMap.values().each { clonotype ->
    def vFreq = freqByV[clonotype.v],
        freqByMut = freqByMutByV[clonotype.v]

    clonotype.mutations.each { mpd ->
        def mutCounter = freqByMut[mpd]
        if (mutCounter.freq / vFreq < freqThreshold ||
                mutCounter.cdr3Len.size() < spectraThreshold)
            clonotype.shms.add(mpd)
        else
            clonotype.alleles.add(mpd)
    }
}

//
// Iterate through clonotypes with same CDR3nt and build hypermutation links
//

def cl2clShmMap = new HashMap<String, Map<String, List<MutationParseData>>>()
def edgeWeightMap = new HashMap<String, Integer>(),
    inDegreeMap = new HashMap<String, Integer>()

/**
 * This procedure puts key2 -> key1 link to mutation network and updates the degrees
 */
def putShm = { String key1, String key2, MutationParseData shm ->
    def clShmMap = cl2clShmMap[key2]
    if (clShmMap == null)
        cl2clShmMap.put(key2,
                clShmMap = new HashMap<String, List<MutationParseData>>())
    def shmList = clShmMap[key1]
    if (shmList == null)
        clShmMap.put(key1, shmList = new LinkedList<MutationParseData>())
    shmList.add(shm)

    // Compute incoming degrees
    inDegreeMap.put(key2, (inDegreeMap[key2] ?: 0))
    inDegreeMap.put(key1, (inDegreeMap[key1] ?: 0) + 1)

    // Weight (for visualization, undirected)
    def key = key1 + "\t" + key2
    edgeWeightMap.put(key, (edgeWeightMap[key] ?: 0) + 1)
    key = key2 + "\t" + key1
    edgeWeightMap.put(key, (edgeWeightMap[key] ?: 0) + 1)
}

/**
 * A simple algorithm to check if two nodes are connected in graph
 */
class Checker {
    def searchedNodes = new HashSet<String>()

    // Recursively scan graph
    boolean checkNodes(HashMap<String, List<String>> graph,
                       String start, String clonotype2) {
        searchedNodes.add(start)
        def subNetwork = graph[start]

        for (String subNode : subNetwork) {
            if (!searchedNodes.contains(subNode)) {
                if (subNode == clonotype2)
                    return true
                else if (checkNodes(graph, subNode, clonotype2))
                    return true
            }
        }

        return false
    }
}

println "[${new Date()} $scriptName] Creating SHM links for FW1-FW3"

byCdr3Map.values().each { clonotypeList ->
    int maxLevel = 0

    clonotypeList.each { maxLevel = Math.max(maxLevel, it.shms.size()) }

    final def connectivityMap = new HashMap<String, List<String>>(),
              newConnectivityMap = new HashMap<String, List<String>>()

    def addClonotypePair = { String key1, String key2 ->
        def conList = newConnectivityMap[key1]
        if (conList == null)
            newConnectivityMap.put(key1, conList = new LinkedList<String>())
        conList.add(key2)
    }

    // Pre-compute lists of intersecting SHMs
    def shmIntersections = new Set[clonotypeList.size()][clonotypeList.size()]
    for (int i = 0; i < clonotypeList.size(); i++) {
        def clonotype1 = clonotypeList[i]
        for (int j = i + 1; j < clonotypeList.size(); j++) {
            def clonotype2 = clonotypeList[j]
            def shms1 = clonotype1.shms, shms2 = clonotype2.shms

            def shmCount1 = shms1.size(),
                shmCount2 = shms2.size()

            def shmIntersection = shmCount1 > shmCount2 ?
                    shms1.intersect(shms2) : shms2.intersect(shms1)

            shmIntersections[i][j] = shmIntersection
        }
    }

    // Iteratively scan for differences by 1,2,.. SHMs
    // Check connectivity graph at previous iteration to see if we're not creating cycles
    for (int level = 1; level < maxLevel; level++) {
        for (int i = 0; i < clonotypeList.size(); i++) {
            def clonotype1 = clonotypeList[i]
            for (int j = i + 1; j < clonotypeList.size(); j++) {
                def clonotype2 = clonotypeList[j]

                def shms1 = clonotype1.shms, shms2 = clonotype2.shms
                def shmCount1 = shms1.size(),
                    shmCount2 = shms2.size()
                def shmIntersection = shmIntersections[i][j]
                def shmDelta1 = shmCount1 - shmIntersection.size(),
                    shmDelta2 = shmCount2 - shmIntersection.size()

                def key1 = clonotype1.key, key2 = clonotype2.key

                if (Math.abs(shmDelta1) + Math.abs(shmDelta2) == level) {
                    boolean connected = false

                    def checker = new Checker()

                    if (!checker.checkNodes(connectivityMap, key1, key2)) {
                        connected = true
                        shms1.each { shm ->
                            if (!shmIntersection.contains(shm))
                                putShm(key1, key2, shm)
                        }
                        shms2.each { shm ->
                            if (!shmIntersection.contains(shm))
                                putShm(key2, key1, shm)
                        }
                    }

                    // update connectivity map
                    if (connected) {
                        addClonotypePair(key1, key2)
                        addClonotypePair(key2, key1)
                    }
                }
            }
        }

        // Merge connectivity map
        newConnectivityMap.each {
            def conList = connectivityMap[it.key]
            if (conList == null)
                connectivityMap.put(it.key, conList = new LinkedList<String>())
            conList.addAll(it.value)
        }
    }
}

//
// CDR3 hypermutations
//

def cl2clCdr3Map = new HashMap<String, String>()

println "[${new Date()} $scriptName] Creating SHM links for CDR3"

def linkedByCdr3 = new HashSet<String>()

clonotypeMap.values().each {
    def thisSeq = it.cdr3nt
    def ntChars = thisSeq.toCharArray()

    for (int i = 0; i < ntChars.length; i++) {
        oldNt = ntChars[i]
        // hash-based 1-loop single-mm search
        Util.NTS.each { char newNt ->
            if (newNt != oldNt) {
                ntChars[i] = newNt
                def otherSeq = new String(ntChars)
                if (byCdr3Map.containsKey(otherSeq)) {
                    if (!linkedByCdr3.contains(otherSeq + "\t" + thisSeq)) {
                        // Make sure there are no duplicates
                        linkedByCdr3.add(thisSeq + "\t" + otherSeq)

                        int codonStart = i - i % 3
                        if (thisSeq.length() >= codonStart + 3) {
                            String fromCodon = thisSeq.substring(codonStart, codonStart + 3),
                                   toCodon = otherSeq.substring(codonStart, codonStart + 3)

                            def fromAA = Util.codon2aa(fromCodon), toAA = Util.codon2aa(toCodon),
                                silent = fromAA == toAA

                            def toClonotypes = byCdr3Map[thisSeq], fromClonotypes = byCdr3Map[otherSeq]

                            // Decide which clonotypes to connect
                            // - select the one with lowest in-degree
                            def fromClonotype = toClonotypes.min { inDegreeMap[it.key] },
                                toClonotype = fromClonotypes.min { inDegreeMap[it.key] }

                            // Decide the ancestry of this SHM
                            // - the first factor is number of clonotypes,
                            // - the second one is number of SHMs
                            int toMutationsTotal = toClonotypes.sum { clonotypeMap[it.key].mutations.size() },
                                fromMutationsTotal = fromClonotypes.sum { clonotypeMap[it.key].mutations.size() }
                            if (toClonotypes.size() < fromClonotypes.size() ||
                                    toMutationsTotal < fromMutationsTotal) {
                                (oldNt, newNt) = [newNt, oldNt]
                                (fromAA, toAA) = [toAA, fromAA]
                                (fromClonotype, toClonotype) = [toClonotype, fromClonotype]
                            }

                            if (toClonotypes.size() == fromClonotypes.size() &&
                                    toMutationsTotal == fromMutationsTotal)
                            // Tie situation - no way to guess parent
                                cl2clCdr3Map.put(fromClonotype.key + " (CDR3) " + toClonotype.key,
                                        // display name
                                        "${silent ? "S" : "CDR3:$fromAA<>$toAA"}\t" +
                                                // tostring
                                                "$i:$oldNt<>$newNt\t${(int) (i / 3)}:$fromAA<>$toAA\tCDR3\t$silent\ttrue")
                            else
                                cl2clCdr3Map.put(fromClonotype.key + " (CDR3) " + toClonotype.key,
                                        "${silent ? "S" : "CDR3:$fromAA>$toAA"}\t" +
                                                "$i:$oldNt>$newNt\t${(int) (i / 3)}:$fromAA>$toAA\tCDR3\t$silent")
                        }
                    }
                }
            }
            ntChars[i] = oldNt
        }
    }
}

//
// Recording region size - we will need this for building tables
//

final Map<String, List<Integer>> regionSizesBySegment = new HashMap<>()
final Map<String, Integer> ambigousRegionCounters = new HashMap<>()

println "[${new Date()} $scriptName] Loading some segment data that we'll need further"

Util.loadRes("regions.${species}.txt").splitEachLine("\t") { List<String> splitLine ->
    def regionSizes = new IntRange(1, 10).step(2).collect {
        splitLine[it + 1].toInteger() -
                splitLine[it].toInteger() + 1
    }

    def fullName = splitLine[0],
        chain = fullName.length() > 2 ? fullName[0..2] : "other",
        gene = fullName.length() > 1 ? chain[0..1] : "other"

    regionSizesBySegment.put(splitLine[0], regionSizes)

    // Those will be used to fill region sizes for segments with missing data

    if (!ambigousRegionCounters.containsKey(gene)) {
        ambigousRegionCounters.put(gene, 0)
        regionSizesBySegment.put(gene, (1..5).collect { 0 })
    }

    if (!ambigousRegionCounters.containsKey(chain)) {
        ambigousRegionCounters.put(chain, 0)
        regionSizesBySegment.put(chain, (1..5).collect { 0 })
    }

    ambigousRegionCounters.put(gene, ambigousRegionCounters[gene] + 1)
    ambigousRegionCounters.put(chain, ambigousRegionCounters[chain] + 1)

    regionSizesBySegment[gene] = [regionSizesBySegment[gene], regionSizes].transpose()*.sum()
    regionSizesBySegment[chain] = [regionSizesBySegment[chain], regionSizes].transpose()*.sum()
}

ambigousRegionCounters.each {
    regionSizesBySegment[it.key] = regionSizesBySegment[it.key]*.intdiv(it.value)
}

def getRegionSize = { String segmentId, String region ->
    def regionId = Util.regionName2Id(region)
    def chain = segmentId.length() > 2 ? segmentId[0..2] : "other",
        gene = segmentId.length() > 1 ? segmentId[0..1] : "other"
    def regionSizes = regionSizesBySegment[segmentId] ?:
            regionSizesBySegment[chain] ?:
                    regionSizesBySegment[gene] ?:
                            [-1, -1, -1, -1, -1]

    regionSizes[regionId]
}

//
// Write output
//

println "[${new Date()} $scriptName] Writing output"

def of = new File(outputPrefix).absoluteFile
if (of.parentFile != null)
    of.parentFile.mkdirs()

// Mutation lists

def LIST_HEADER = "#count\tfreq\tv_segment" + MutationParseData.EDGE_HEADER + "\tundirected\tregion_length"

new File(outputPrefix + ".alleles.txt").withPrintWriter { pw ->
    pw.println(LIST_HEADER)
    clonotypeMap.values().each { clonotype ->
        clonotype.alleles.each { allele ->
            pw.println(clonotype.count + "\t" + clonotype.freq + "\t" + clonotype.v + "\t" +
                    allele + "\tfalse\t" +
                    getRegionSize(clonotype.v, allele.region))
        }
    }
}

new File(outputPrefix + ".germline_total.txt").withPrintWriter { pw ->
    pw.println(LIST_HEADER)
    clonotypeMap.values().each { clonotype ->
        clonotype.shms.each { shm ->
            pw.println(clonotype.count + "\t" + clonotype.freq + "\t" + clonotype.v + "\t" +
                    shm + "\tfalse\t" +
                    getRegionSize(clonotype.v, shm.region))
        }
    }
}

new File(outputPrefix + ".germline_directed.txt").withPrintWriter { pw ->
    pw.println(LIST_HEADER)
    cl2clShmMap.values().each {
        it.each { to ->
            def toKey = to.key, toClonotype = clonotypeMap[toKey]

            to.value.each { shm ->
                pw.println(toClonotype.count + "\t" + toClonotype.freq + "\t" + toClonotype.v + "\t" +
                        shm + "\tfalse\t" +
                        getRegionSize(toClonotype.v, shm.region))
            }
        }
    }
}

new File(outputPrefix + ".cdr3_total.txt").withPrintWriter { pw1 ->
    pw1.println(LIST_HEADER)
    new File(outputPrefix + ".cdr3_directed.txt").withPrintWriter { pw2 ->
        pw2.println(LIST_HEADER)
        cl2clCdr3Map.each {
            def splitKey = it.key.split(" \\(CDR3\\) ")
            def fromClonotype = clonotypeMap[splitKey[0]],
                toClonotype = clonotypeMap[splitKey[1]]
            def unDirected = it.key.split("\t")[-1] == "true"

            if (unDirected) {
                pw1.println((fromClonotype.count + toClonotype.count) / 2 + "\t" +
                        (fromClonotype.freq + toClonotype.freq) / 2 + "\t" + toClonotype.v + "\t" +
                        it.value + "\t" +
                        fromClonotype.cdr3nt.length())
            } else {
                def line = toClonotype.count + "\t" + toClonotype.freq + "\t" + toClonotype.v + "\t" +
                        it.value + "\tfalse\t" +
                        fromClonotype.cdr3nt.length()
                pw1.println(line)
                pw2.println(line)
            }
        }
    }
}

// Cytoscape files

new File(outputPrefix + ".nodes.txt").withPrintWriter { pw ->
    pw.println("key\t" + ClonotypeParsedData.NODE_HEADER)
    clonotypeMap.each {
        pw.println("$it.key\t$it.value")
    }
}

new File(outputPrefix + ".edges.txt").withPrintWriter { pw ->
    pw.println("key\tweight\t" + MutationParseData.EDGE_HEADER + "\tundirected")
    cl2clShmMap.each { from ->
        def fromKey = from.key
        from.value.each { to ->
            def toKey = to.key

            double weight = 1.0 / edgeWeightMap[toKey + "\t" + fromKey]

            to.value.each { shm ->
                pw.println(fromKey + " (" + shm.key + ") " + toKey + "\t" + weight + "\t" + shm)
            }
        }
    }
    cl2clCdr3Map.each {
        pw.println("$it.key\t1.0\t$it.value")
    }
}

new File(outputPrefix + ".net.txt").withPrintWriter { pw ->
    pw.println("source\tshm\ttarget")
    cl2clShmMap.each { from ->
        def fromKey = from.key
        from.value.each { to ->
            def toKey = to.key
            to.value.each { shm ->
                pw.println("$fromKey\t$shm.key\t$toKey")
            }
        }
    }
    cl2clCdr3Map.each {
        pw.println(it.key.replace(" (", "\t").replace(") ", "\t"))
    }
}

println "[${new Date()} $scriptName] Finished"