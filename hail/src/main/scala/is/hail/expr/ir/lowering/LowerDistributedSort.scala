package is.hail.expr.ir.lowering

import is.hail.HailContext
import is.hail.annotations.{Annotation, ExtendedOrdering, Region, RegionValueBuilder, SafeRow, UnsafeRow}
import is.hail.asm4s.{AsmFunction1RegionLong, LongInfo, classInfo}
import is.hail.backend.ExecuteContext
import is.hail.expr.ir._
import is.hail.expr.ir.functions.{ArrayFunctions, IRRandomness}
import is.hail.io.{BufferSpec, TypedCodecSpec}
import is.hail.types.physical.{PArray, PBaseStruct, PCanonicalArray, PStruct, PTuple, PType}
import is.hail.types.virtual.{TArray, TBoolean, TInt32, TIterable, TStream, TString, TStruct, TTuple, Type}
import is.hail.rvd.{PartitionBoundOrdering, RVDPartitioner}
import is.hail.types.RStruct
import is.hail.types.physical.stypes.PTypeReferenceSingleCodeType
import is.hail.utils._
import org.apache.spark.sql.Row
import scala.collection.mutable.ArrayBuffer

object LowerDistributedSort {
  def localSort(ctx: ExecuteContext, stage: TableStage, sortFields: IndexedSeq[SortField], relationalLetsAbove: Map[String, IR]): TableStage = {
    val numPartitions = stage.partitioner.numPartitions
    val collected = stage.collectWithGlobals(relationalLetsAbove)

    val (Some(PTypeReferenceSingleCodeType(resultPType: PStruct)), f) = ctx.timer.time("LowerDistributedSort.localSort.compile")(Compile[AsmFunction1RegionLong](ctx,
      FastIndexedSeq(),
      FastIndexedSeq(classInfo[Region]), LongInfo,
      collected,
      print = None,
      optimize = true))

    val fRunnable = ctx.timer.time("LowerDistributedSort.localSort.initialize")(f(ctx.fs, 0, ctx.r))
    val resultAddress = ctx.timer.time("LowerDistributedSort.localSort.run")(fRunnable(ctx.r))
    val rowsAndGlobal = ctx.timer.time("LowerDistributedSort.localSort.toJavaObject")(SafeRow.read(resultPType, resultAddress)).asInstanceOf[Row]

    val rowsType = resultPType.fieldType("rows").asInstanceOf[PArray]
    val rowType = rowsType.elementType.asInstanceOf[PStruct]
    val rows = rowsAndGlobal.getAs[IndexedSeq[Annotation]](0)
    val kType = TStruct(sortFields.map(f => (f.field, rowType.virtualType.fieldType(f.field))): _*)

    val sortedRows = localAnnotationSort(ctx, rows, sortFields, rowType.virtualType)

    val nPartitionsAdj = math.max(math.min(sortedRows.length, numPartitions), 1)
    val itemsPerPartition = (sortedRows.length.toDouble / nPartitionsAdj).ceil.toInt

    if (itemsPerPartition == 0)
      return TableStage(
        globals = Literal(resultPType.fieldType("global").virtualType, rowsAndGlobal.get(1)),
        partitioner = RVDPartitioner.empty(kType),
        TableStageDependency.none,
        MakeStream(FastSeq(), TStream(TStruct())),
        _ => MakeStream(FastSeq(), TStream(stage.rowType))
      )

    // partitioner needs keys to be ascending
    val partitionerKeyType = TStruct(sortFields.takeWhile(_.sortOrder == Ascending).map(f => (f.field, rowType.virtualType.fieldType(f.field))): _*)
    val partitionerKeyIndex = partitionerKeyType.fieldNames.map(f => rowType.fieldIdx(f))

    val partitioner = new RVDPartitioner(partitionerKeyType,
      sortedRows.grouped(itemsPerPartition).map { group =>
        val first = group.head.asInstanceOf[Row].select(partitionerKeyIndex)
        val last = group.last.asInstanceOf[Row].select(partitionerKeyIndex)
        Interval(first, last, includesStart = true, includesEnd = true)
      }.toIndexedSeq)

    TableStage(
      globals = Literal(resultPType.fieldType("global").virtualType, rowsAndGlobal.get(1)),
      partitioner = partitioner,
      TableStageDependency.none,
      contexts = mapIR(
        StreamGrouped(
          ToStream(Literal(rowsType.virtualType, sortedRows)),
          I32(itemsPerPartition))
        )(ToArray(_)),
      ctxRef => ToStream(ctxRef))
  }

  private def localAnnotationSort(
    ctx: ExecuteContext,
    annotations: IndexedSeq[Annotation],
    sortFields: IndexedSeq[SortField],
    rowType: TStruct
  ): IndexedSeq[Annotation] = {
    val sortColIndexOrd = sortFields.map { case SortField(n, so) =>
      val i = rowType.fieldIdx(n)
      val f = rowType.fields(i)
      val fo = f.typ.ordering
      if (so == Ascending) fo else fo.reverse
    }.toArray

    val ord: Ordering[Annotation] = ExtendedOrdering.rowOrdering(sortColIndexOrd).toOrdering

    val kType = TStruct(sortFields.map(f => (f.field, rowType.fieldType(f.field))): _*)
    val kIndex = kType.fieldNames.map(f => rowType.fieldIdx(f))
    ctx.timer.time("LowerDistributedSort.localSort.sort")(annotations.sortBy{ a: Annotation =>
      a.asInstanceOf[Row].select(kIndex).asInstanceOf[Annotation]
    }(ord))
  }


  def distributedSort(
    ctx: ExecuteContext,
    inputStage: TableStage,
    sortFields: IndexedSeq[SortField],
    relationalLetsAbove: Map[String, IR],
    rowTypeRequiredness: RStruct
  ): TableStage = {

    val oversamplingNum = 3
    val seed = 7L
    val branchingFactor = 4
    val sizeCutoff = HailContext.getFlag("shuffle_cutoff_to_local_sort").toInt

    val (keyToSortBy, _) = inputStage.rowType.select(sortFields.map(sf => sf.field))

    val spec = TypedCodecSpec(rowTypeRequiredness.canonicalPType(inputStage.rowType), BufferSpec.default)
    val reader = PartitionNativeReader(spec)
    val initialTmpPath = ctx.createTmpPath("hail_shuffle_temp_initial")
    val writer = PartitionNativeWriter(spec, keyToSortBy.fieldNames, initialTmpPath, None, None)

    val initialStageDataRow = CompileAndEvaluate[Annotation](ctx, inputStage.mapCollectWithGlobals(relationalLetsAbove) { part =>
      WritePartition(part, UUID4(), writer)
    }{ case (part, globals) => MakeTuple.ordered(Seq(part, globals))}).asInstanceOf[Row]
    val (initialPartInfo, initialGlobals) = (initialStageDataRow(0).asInstanceOf[IndexedSeq[Row]], initialStageDataRow(1).asInstanceOf[Row])
    val initialGlobalsLiteral = Literal(inputStage.globalType, initialGlobals)
    val initialChunks = initialPartInfo.map(row => Chunk(initialTmpPath + row(0).asInstanceOf[String], row(1).asInstanceOf[Long].toInt))
    val initialSegment = SegmentResult(IndexedSeq(0), inputStage.partitioner.range.get, initialChunks)

    val totalNumberOfRows = initialChunks.map(_.size).sum
    val idealNumberOfRowsPerPart = Math.max(1, totalNumberOfRows / inputStage.numPartitions)

    var loopState = LoopState(IndexedSeq(initialSegment), IndexedSeq.empty[SegmentResult], IndexedSeq.empty[SegmentResult])

    var i = 0
    val rand = new IRRandomness(seed)

    /*
    There are three categories of segments. largeUnsortedSegments are too big to sort locally so have to broken up.
    largeSortedSegments were identified as being already sorted, so no reason to recur on them. smallSegments are small
    enough to be sorted locally.
     */

    while (!loopState.largeUnsortedSegments.isEmpty) {
      val partitionDataPerSegment = segmentsToPartitionData(loopState.largeUnsortedSegments, idealNumberOfRowsPerPart)

      val partitionCountsPerSegment = partitionDataPerSegment.map(oneSegment => oneSegment.map(_.currentPartSize))
      assert(partitionCountsPerSegment.size == loopState.largeUnsortedSegments.size)

      val numSamplesPerPartitionPerSegment = partitionCountsPerSegment.map { partitionCountsForOneSegment =>
        val recordsInSegment = partitionCountsForOneSegment.sum
        howManySamplesPerPartition(rand, recordsInSegment, Math.min(recordsInSegment, (branchingFactor * oversamplingNum) - 1), partitionCountsForOneSegment)
      }

      val numSamplesPerPartition = numSamplesPerPartitionPerSegment.flatten

      val perPartStatsCDAContextData = partitionDataPerSegment.flatten.zip(numSamplesPerPartition).map { case (partData, numSamples) => Row(partData.indices.last, partData.files, partData.currentPartSize, numSamples)}
      val perPartStatsCDAContexts = ToStream(Literal(TArray(TStruct("segmentIdx" -> TInt32, "files" -> TArray(TString), "sizeOfPartition" -> TInt32, "numSamples" -> TInt32)), perPartStatsCDAContextData))
      val perPartStatsIR = cdaIR(perPartStatsCDAContexts, MakeStruct(Seq())){ (ctxRef, _) =>
        val filenames = GetField(ctxRef, "files")
        val samples = SeqSample(GetField(ctxRef, "sizeOfPartition"), GetField(ctxRef, "numSamples"), false)
        val partitionStream = flatMapIR(ToStream(filenames)) { fileName =>
          mapIR(ReadPartition(fileName, spec._vType, reader)){ partitionElement =>
            SelectFields(partitionElement, keyToSortBy.fields.map(_.name))
          }
        }
        MakeStruct(IndexedSeq("segmentIdx" -> GetField(ctxRef, "segmentIdx"), "partData" -> samplePartition(partitionStream, samples, sortFields)))
      }


      /*
      Aggregate over the segments, to compute the pivots, whether it's already sorted, and what key interval is contained in that segment.
       */
      val pivotsPerSegmentAndSortedCheck = ToArray(bindIR(perPartStatsIR) { perPartStats =>
        mapIR(StreamGroupByKey(ToStream(perPartStats), IndexedSeq("segmentIdx"))) { oneGroup =>
          val streamElementRef = Ref(genUID(), oneGroup.typ.asInstanceOf[TIterable].elementType)
          val dataRef = Ref(genUID(), streamElementRef.typ.asInstanceOf[TStruct].fieldType("partData"))
          bindIR(StreamAgg(oneGroup, streamElementRef.name, {
            AggLet(dataRef.name, GetField(streamElementRef, "partData"),
              MakeStruct(Seq(
                ("min", AggFold.min(GetField(dataRef, "min"), sortFields)),
                ("max", AggFold.max(GetField(dataRef, "max"), sortFields)),
                ("samples", ApplyAggOp(Collect())(GetField(dataRef, "samples"))),
                ("eachPartSorted", AggFold.all(GetField(dataRef, "isSorted"))),
                ("perPartIntervalTuples", ApplyAggOp(Collect())(MakeTuple.ordered(Seq(GetField(dataRef, "min"), GetField(dataRef, "max")))))
              )), false)
          })) { aggResults =>
            val sortedOversampling = sortIR(flatMapIR(ToStream(GetField(aggResults, "samples"))) { onePartCollectedArray => ToStream(onePartCollectedArray)}) { case (left, right) =>
              ApplyComparisonOp(StructLT(keyToSortBy, sortFields), left, right)
            }
            val minArray = MakeArray(GetField(aggResults, "min"))
            val maxArray = MakeArray(GetField(aggResults, "max"))
            val tuplesInSortedOrder = tuplesAreSorted(GetField(aggResults, "perPartIntervalTuples"), sortFields)
            bindIR(sortedOversampling) { sortedOversampling =>
              val sortedSampling = ToArray(mapIR(StreamRange(I32(oversamplingNum - 1), ArrayLen(sortedOversampling), I32(oversamplingNum))) { idx =>
                ArrayRef(sortedOversampling, idx)
              })
              MakeStruct(Seq(
                "pivotsWithEndpoints" -> ArrayFunctions.extend(ArrayFunctions.extend(minArray, sortedSampling), maxArray),
                "isSorted" -> ApplySpecial("land", Seq.empty[Type], Seq(GetField(aggResults, "eachPartSorted"), tuplesInSortedOrder), TBoolean, ErrorIDs.NO_ERROR),
                "intervalTuple" -> MakeTuple.ordered(Seq(GetField(aggResults, "min"), GetField(aggResults, "max")))
              ))
            }
          }
        }
      })

      // Going to check now if it's fully sorted, as well as collect and sort all the samples.
      val pivotsWithEndpointsAndInfoGroupedBySegmentNumber = CompileAndEvaluate[Annotation](ctx, pivotsPerSegmentAndSortedCheck)
        .asInstanceOf[IndexedSeq[Row]].map(x => (x(0).asInstanceOf[IndexedSeq[Row]], x(1).asInstanceOf[Boolean], x(2).asInstanceOf[Row]))

      val (sortedSegmentsTuples, unsortedPivotsWithEndpointsAndInfoGroupedBySegmentNumber) = pivotsWithEndpointsAndInfoGroupedBySegmentNumber.zipWithIndex.partition { case ((_, isSorted, _), _) => isSorted}

      val sortedSegments = sortedSegmentsTuples.map { case (_, idx) => loopState.largeUnsortedSegments(idx)}
      val remainingUnsortedSegments = unsortedPivotsWithEndpointsAndInfoGroupedBySegmentNumber.map {case (_, idx) => loopState.largeUnsortedSegments(idx)}

      val (newBigUnsortedSegments, newSmallSegments) = if (unsortedPivotsWithEndpointsAndInfoGroupedBySegmentNumber.size > 0) {

        val pivotsWithEndpointsGroupedBySegmentNumber = unsortedPivotsWithEndpointsAndInfoGroupedBySegmentNumber.map{ case (r, _) => r._1 }

        val pivotsWithEndpointsGroupedBySegmentNumberLiteral = Literal(TArray(TArray(keyToSortBy)), pivotsWithEndpointsGroupedBySegmentNumber)

        val tmpPath = ctx.createTmpPath("hail_shuffle_temp")
        val unsortedPartitionDataPerSegment = unsortedPivotsWithEndpointsAndInfoGroupedBySegmentNumber.map { case (_, idx) => partitionDataPerSegment(idx)}

        val partitionDataPerSegmentWithPivotIndex = unsortedPartitionDataPerSegment.zipWithIndex.map { case (partitionDataForOneSegment, indexIntoPivotsArray) =>
          partitionDataForOneSegment.map(x => (x.indices, x.files, x.currentPartSize, indexIntoPivotsArray))
        }

        val distributeContextsData = partitionDataPerSegmentWithPivotIndex.flatten.zipWithIndex.map { case (part, partIdx) => Row(part._1.last, part._2, partIdx, part._4) }
        val distributeContexts = ToStream(Literal(TArray(TStruct("segmentIdx" -> TInt32, "files" -> TArray(TString), "partIdx" -> TInt32, "indexIntoPivotsArray" -> TInt32)), distributeContextsData))
        val distributeGlobals = MakeStruct(IndexedSeq("pivotsWithEndpointsGroupedBySegmentIdx" -> pivotsWithEndpointsGroupedBySegmentNumberLiteral))

        val distribute = cdaIR(distributeContexts, distributeGlobals) { (ctxRef, globalsRef) =>
          val segmentIdx = GetField(ctxRef, "segmentIdx")
          val indexIntoPivotsArray = GetField(ctxRef, "indexIntoPivotsArray")
          val pivotsWithEndpointsGroupedBySegmentIdx = GetField(globalsRef, "pivotsWithEndpointsGroupedBySegmentIdx")
          val path = invoke("concat", TString, Str(tmpPath + "_"), invoke("str", TString, GetField(ctxRef, "partIdx")))
          val filenames = GetField(ctxRef, "files")
          val partitionStream = flatMapIR(ToStream(filenames)) { fileName =>
            ReadPartition(fileName, spec._vType, reader)
          }
          MakeTuple.ordered(IndexedSeq(segmentIdx, StreamDistribute(partitionStream, ArrayRef(pivotsWithEndpointsGroupedBySegmentIdx, indexIntoPivotsArray), path, StructCompare(keyToSortBy, keyToSortBy, sortFields.toArray), spec)))
        }

        val distributeResult = CompileAndEvaluate[Annotation](ctx, distribute)
          .asInstanceOf[IndexedSeq[Row]].map(row => (
          row(0).asInstanceOf[Int],
          row(1).asInstanceOf[IndexedSeq[Row]].map(innerRow => (
            innerRow(0).asInstanceOf[Interval],
            innerRow(1).asInstanceOf[String],
            innerRow(2).asInstanceOf[Int]))))

        // distributeResult is a numPartitions length array of arrays, where each inner array tells me what
        // files were written to for each partition, as well as the number of entries in that file.
        val protoDataPerSegment = orderedGroupBy[(Int, IndexedSeq[(Interval, String, Int)]), Int](distributeResult, x => x._1).map { case (_, seqOfChunkData) => seqOfChunkData.map(_._2) }

        val transposedIntoNewSegments = protoDataPerSegment.zip(remainingUnsortedSegments.map(_.indices)).flatMap { case (oneOldSegment, priorIndices) =>
          val headLen = oneOldSegment.head.length
          assert(oneOldSegment.forall(x => x.length == headLen))
          (0 until headLen).map(colIdx => (oneOldSegment.map(row => row(colIdx)), priorIndices))
        }

        val dataPerSegment = transposedIntoNewSegments.zipWithIndex.map { case ((chunksWithSameInterval, priorIndices), newIndex) =>
          val interval = chunksWithSameInterval.head._1
          val chunks = chunksWithSameInterval.map(chunk => Chunk(chunk._2, chunk._3))
          val newSegmentIndices = priorIndices :+ newIndex
          SegmentResult(newSegmentIndices, interval, chunks)
        }

        // Now I need to figure out how many partitions to allocate to each segment.
        dataPerSegment.partition { sr =>
          sr.chunks.map(_.size).sum > sizeCutoff && (sr.interval.left.point != sr.interval.right.point)
        }
      } else { (IndexedSeq.empty[SegmentResult], IndexedSeq.empty[SegmentResult]) }
      loopState = LoopState(newBigUnsortedSegments, loopState.largeSortedSegments ++ sortedSegments, loopState.smallSegments ++ newSmallSegments)

      i = i + 1
    }

    val needSortingFilenames = loopState.smallSegments.map(_.chunks.map(_.filename))
    val needSortingFilenamesContext = Literal(TArray(TArray(TString)), needSortingFilenames)

    val sortedFilenamesIR = cdaIR(ToStream(needSortingFilenamesContext), MakeStruct(Seq())) { case (ctxRef, _) =>
      val filenames = ctxRef
      val partitionInputStream = flatMapIR(ToStream(filenames)) { fileName =>
        ReadPartition(fileName, spec._vType, reader)
      }
      val newKeyFieldNames = keyToSortBy.fields.map(_.name)
      val sortedStream = ToStream(sortIR(partitionInputStream) { (refLeft, refRight) =>
        ApplyComparisonOp(StructLT(keyToSortBy, sortFields), SelectFields(refLeft, newKeyFieldNames), SelectFields(refRight, newKeyFieldNames))
      })
      WritePartition(sortedStream, UUID4(), writer)
    }

    val sortedFilenames = CompileAndEvaluate[Annotation](ctx, sortedFilenamesIR).asInstanceOf[IndexedSeq[Row]].map(_(0).asInstanceOf[String])
    val newlySortedSegments = loopState.smallSegments.zip(sortedFilenames).map { case (sr, newFilename) =>
      SegmentResult(sr.indices, sr.interval, IndexedSeq(Chunk(initialTmpPath + newFilename, sr.chunks.foldLeft(0)((size, chunk) => size + chunk.size))))
    }

    val unorderedSegments = newlySortedSegments ++ loopState.largeSortedSegments
    val orderedSegments = unorderedSegments.sortWith{ (srt1, srt2) => lessThanForSegmentIndices(srt1.indices, srt2.indices)}

    // Now let's treat the whole thing as one segment that can be partitioned by the segmentToPartitionData method.
    val megaSegment = SegmentResult(IndexedSeq(), null, orderedSegments.flatMap(sr => sr.chunks))
    val partitioned = segmentsToPartitionData(IndexedSeq(megaSegment), idealNumberOfRowsPerPart).flatten

    val contextData = partitioned.map { part => Row(part.files) }
    val contexts = ToStream(Literal(TArray(TStruct("files" -> TArray(TString))), contextData))

    // Note: If all of the sort fields are not ascending, the the resulting table is sorted, but not keyed.
    val keyed = sortFields.forall(sf => sf.sortOrder == Ascending)
    val (partitionerKey, intervals) = if (keyed) {
      (keyToSortBy, orderedSegments.map{ segment => segment.interval})
    } else {
      (TStruct(), orderedSegments.map{ _ => Interval(Row(), Row(), true, false)})
    }

    val partitioner = new RVDPartitioner(partitionerKey, intervals)
    val finalTs = TableStage(initialGlobalsLiteral, partitioner, TableStageDependency.none, contexts, { ctxRef =>
      val filenames = GetField(ctxRef, "files")
      val partitionInputStream = flatMapIR(ToStream(filenames)) { fileName =>
        ReadPartition(fileName, spec._vType, reader)
      }
      partitionInputStream
    })

    finalTs
  }

  def orderedGroupBy[T, U](is: IndexedSeq[T], func: T => U): IndexedSeq[(U, IndexedSeq[T])] = {
    val result = new ArrayBuffer[(U, IndexedSeq[T])](is.size)
    val currentGroup = new ArrayBuffer[T]()
    var lastKeySeen: Option[U] = None
    is.foreach { element =>
      val key = func(element)
      if (currentGroup.isEmpty) {
        currentGroup.append(element)
        lastKeySeen = Some(key)
      } else if (lastKeySeen.map(lastKey => lastKey == key).getOrElse(false)) {
        currentGroup.append(element)
      } else {
        result.append((lastKeySeen.get, currentGroup.result().toIndexedSeq))
        currentGroup.clear()
        currentGroup.append(element)
        lastKeySeen = Some(key)
      }
    }
    if (!currentGroup.isEmpty) {
      result.append((lastKeySeen.get, currentGroup))
    }
    result.result().toIndexedSeq
  }

  def lessThanForSegmentIndices(i1: IndexedSeq[Int], i2: IndexedSeq[Int]): Boolean = {
    var idx = 0
    val minLength = math.min(i1.length, i2.length)
    while (idx < minLength) {
      if (i1(idx) != i2(idx)) {
        return i1(idx) < i2(idx)
      }
      idx += 1
    }
    // For there to be no difference at this point, they had to be equal whole way. Assert that they're same length.
    assert(i1.length == i2.length)
    false
  }

  case class PartitionInfo(indices: IndexedSeq[Int], files: IndexedSeq[String], currentPartSize: Int)

  def segmentsToPartitionData(segments: IndexedSeq[SegmentResult], idealNumberOfRowsPerPart: Int): IndexedSeq[IndexedSeq[PartitionInfo]] = {
    segments.map { sr =>
      val chunkDataSizes = sr.chunks.map(_.size)
      val segmentSize = chunkDataSizes.sum
      val numParts = (segmentSize + idealNumberOfRowsPerPart - 1) / idealNumberOfRowsPerPart
      var currentPartSize = 0
      val groupedIntoParts = new ArrayBuffer[PartitionInfo](numParts)
      val currentFiles = new ArrayBuffer[String]()
      sr.chunks.foreach { chunk =>
        if (chunk.size > 0) {
          currentFiles.append(chunk.filename)
          currentPartSize += chunk.size
          if (currentPartSize >= idealNumberOfRowsPerPart) {
            groupedIntoParts.append(PartitionInfo(sr.indices, currentFiles.result().toIndexedSeq, currentPartSize))
            currentFiles.clear()
            currentPartSize = 0
          }
        }
      }
      if (!currentFiles.isEmpty) {
        groupedIntoParts.append(PartitionInfo(sr.indices, currentFiles.result().toIndexedSeq, currentPartSize))
      }
      groupedIntoParts.result()
    }
  }

  def howManySamplesPerPartition(rand: IRRandomness, totalNumberOfRecords: Int, initialNumSamplesToSelect: Int, partitionCounts: IndexedSeq[Int]): IndexedSeq[Int] = {
    var successStatesRemaining = initialNumSamplesToSelect
    var failureStatesRemaining = totalNumberOfRecords - successStatesRemaining

    val ans = new Array[Int](partitionCounts.size)

    var i = 0
    while (i < partitionCounts.size) {
      val numSuccesses = rand.rhyper(successStatesRemaining, failureStatesRemaining, partitionCounts(i)).toInt
      successStatesRemaining -= numSuccesses
      failureStatesRemaining -= (partitionCounts(i) - numSuccesses)
      ans(i) = numSuccesses
      i += 1
    }

    ans
  }

  def samplePartition(dataStream: IR, sampleIndices: IR, sortFields: IndexedSeq[SortField]): IR = {
    // Step 1: Join the dataStream zippedWithIdx on sampleIndices?
    // That requires sampleIndices to be a stream of structs
    val samplingIndexName = "samplingPartitionIndex"
    val structSampleIndices = mapIR(sampleIndices)(sampleIndex => MakeStruct(Seq((samplingIndexName, sampleIndex))))
    val dataWithIdx = zipWithIndex(dataStream)

    val leftName = genUID()
    val rightName = genUID()
    val leftRef = Ref(leftName, dataWithIdx.typ.asInstanceOf[TStream].elementType)
    val rightRef = Ref(rightName, structSampleIndices.typ.asInstanceOf[TStream].elementType)

    val joined = StreamJoin(dataWithIdx, structSampleIndices, IndexedSeq("idx"), IndexedSeq(samplingIndexName), leftName, rightName,
      MakeStruct(Seq(("elt", GetField(leftRef, "elt")), ("shouldKeep", ApplyUnaryPrimOp(Bang(), IsNA(rightRef))))),
      "left")

    // Step 2: Aggregate over joined, figure out how to collect only the rows that are marked "shouldKeep"
    val streamElementType = joined.typ.asInstanceOf[TStream].elementType.asInstanceOf[TStruct]
    val streamElementName = genUID()
    val streamElementRef = Ref(streamElementName, streamElementType)
    val eltName = genUID()
    val eltType = dataStream.typ.asInstanceOf[TStream].elementType.asInstanceOf[TStruct]
    val eltRef = Ref(eltName, eltType)

    // Folding for isInternallySorted
    val aggFoldSortedZero = MakeStruct(Seq("lastKeySeen" -> NA(eltType), "sortedSoFar" -> true, "haveSeenAny" -> false))
    val aggFoldSortedAccumName1 = genUID()
    val aggFoldSortedAccumName2 = genUID()
    val isSortedStateType = TStruct("lastKeySeen" -> eltType, "sortedSoFar" -> TBoolean, "haveSeenAny" -> TBoolean)
    val aggFoldSortedAccumRef1 = Ref(aggFoldSortedAccumName1, isSortedStateType)
    val isSortedSeq =
      bindIR(GetField(aggFoldSortedAccumRef1, "lastKeySeen")) { lastKeySeenRef =>
        If(!GetField(aggFoldSortedAccumRef1, "haveSeenAny"),
          MakeStruct(Seq("lastKeySeen" -> eltRef, "sortedSoFar" -> true, "haveSeenAny" -> true)),
          If (ApplyComparisonOp(StructLTEQ(eltType, sortFields), lastKeySeenRef, eltRef),
            MakeStruct(Seq("lastKeySeen" -> eltRef, "sortedSoFar" -> GetField(aggFoldSortedAccumRef1, "sortedSoFar"), "haveSeenAny" -> true)),
            MakeStruct(Seq("lastKeySeen" -> eltRef, "sortedSoFar" -> false, "haveSeenAny" -> true))
          )
        )
      }
    val isSortedComb = aggFoldSortedAccumRef1 // Do nothing, as this will never be called in a StreamAgg


    StreamAgg(joined, streamElementName, {
      AggLet(eltName, GetField(streamElementRef, "elt"),
        MakeStruct(Seq(
          ("min", AggFold.min(eltRef, sortFields)),
          ("max", AggFold.max(eltRef, sortFields)),
          ("samples", AggFilter(GetField(streamElementRef, "shouldKeep"), ApplyAggOp(Collect())(eltRef), false)),
          ("isSorted", GetField(AggFold(aggFoldSortedZero, isSortedSeq, isSortedComb, aggFoldSortedAccumName1, aggFoldSortedAccumName2, false), "sortedSoFar"))
        )), false)
    })
  }

  // Given an IR of type TArray(TTuple(minKey, maxKey)), determine if there's any overlap between these closed intervals.
  def tuplesAreSorted(arrayOfTuples: IR, sortFields: IndexedSeq[SortField]): IR = {
    val intervalElementType = arrayOfTuples.typ.asInstanceOf[TArray].elementType.asInstanceOf[TTuple].types(0)

    foldIR(mapIR(rangeIR(1, ArrayLen(arrayOfTuples))) { idxOfTuple =>
      ApplyComparisonOp(StructLTEQ(intervalElementType, sortFields), GetTupleElement(ArrayRef(arrayOfTuples, idxOfTuple - 1), 1), GetTupleElement(ArrayRef(arrayOfTuples, idxOfTuple), 0))
    }, True()) { case (accum, elt) =>
      ApplySpecial("land", Seq.empty[Type], Seq(accum, elt), TBoolean, ErrorIDs.NO_ERROR)
    }
  }

}

case class Chunk(filename: String, size: Int)
case class SegmentResult(indices: IndexedSeq[Int], interval: Interval, chunks: IndexedSeq[Chunk])
case class LoopState(largeUnsortedSegments: IndexedSeq[SegmentResult], largeSortedSegments: IndexedSeq[SegmentResult], smallSegments: IndexedSeq[SegmentResult])