package is.hail.expr

import is.hail.HailContext
import is.hail.annotations._
import is.hail.annotations.Annotation._
import is.hail.annotations.aggregators.RegionValueAggregator
import is.hail.expr.ir._
import is.hail.expr.types._
import is.hail.io._
import is.hail.io.gen.ExportGen
import is.hail.io.plink.ExportPlink
import is.hail.methods.Aggregators
import is.hail.rvd._
import is.hail.sparkextras.ContextRDD
import is.hail.table.TableSpec
import is.hail.variant._
import org.apache.spark.rdd.RDD
import org.apache.spark.sql.Row
import is.hail.utils._
import org.apache.spark.SparkContext
import org.json4s._
import org.json4s.jackson.JsonMethods
import org.json4s.jackson.JsonMethods.parse

abstract class BaseIR {
  def typ: BaseType

  def children: IndexedSeq[BaseIR]

  def copy(newChildren: IndexedSeq[BaseIR]): BaseIR

  def deepCopy(): this.type = copy(newChildren = children.map(_.deepCopy())).asInstanceOf[this.type]

  def mapChildren(f: (BaseIR) => BaseIR): BaseIR = {
    copy(children.map(f))
  }
}

case class MatrixValue(
  typ: MatrixType,
  globals: BroadcastRow,
  colValues: BroadcastIndexedSeq,
  rvd: OrderedRVD) {

  assert(rvd.typ == typ.orvdType)

  def sparkContext: SparkContext = rvd.sparkContext

  def nPartitions: Int = rvd.getNumPartitions

  def nCols: Int = colValues.value.length

  def sampleIds: IndexedSeq[Row] = {
    val queriers = typ.colKey.map(field => typ.colType.query(field))
    colValues.value.map(a => Row.fromSeq(queriers.map(_ (a))))
  }

  def colsTableValue: TableValue = TableValue(typ.colsTableType, globals, colsRVD())

  def rowsTableValue: TableValue = TableValue(typ.rowsTableType, globals, rowsRVD())

  def entriesTableValue: TableValue = TableValue(typ.entriesTableType, globals, entriesRVD())

  private def writeCols(path: String, codecSpec: CodecSpec) {
    val hc = HailContext.get
    val hadoopConf = hc.hadoopConf

    val partitionCounts = RVD.writeLocalUnpartitioned(hc, path + "/rows", typ.colType, codecSpec, colValues.value)

    val colsSpec = TableSpec(
      FileFormat.version.rep,
      hc.version,
      "../references",
      typ.colsTableType,
      Map("globals" -> RVDComponentSpec("../globals/rows"),
        "rows" -> RVDComponentSpec("rows"),
        "partition_counts" -> PartitionCountsComponentSpec(partitionCounts)))
    colsSpec.write(hc, path)

    hadoopConf.writeTextFile(path + "/_SUCCESS")(out => ())
  }

  private def writeGlobals(path: String, codecSpec: CodecSpec) {
    val hc = HailContext.get
    val hadoopConf = hc.hadoopConf

    val partitionCounts = RVD.writeLocalUnpartitioned(hc, path + "/rows", typ.globalType, codecSpec, Array(globals.value))

    RVD.writeLocalUnpartitioned(hc, path + "/globals", TStruct.empty(), codecSpec, Array[Annotation](Row()))

    val globalsSpec = TableSpec(
      FileFormat.version.rep,
      hc.version,
      "../references",
      TableType(typ.globalType, None, TStruct.empty()),
      Map("globals" -> RVDComponentSpec("globals"),
        "rows" -> RVDComponentSpec("rows"),
        "partition_counts" -> PartitionCountsComponentSpec(partitionCounts)))
    globalsSpec.write(hc, path)

    hadoopConf.writeTextFile(path + "/_SUCCESS")(out => ())
  }

  def write(path: String, overwrite: Boolean = false, codecSpecJSONStr: String = null): Unit = {
    val hc = HailContext.get
    val hadoopConf = hc.hadoopConf

    val codecSpec =
      if (codecSpecJSONStr != null) {
        implicit val formats = RVDSpec.formats
        val codecSpecJSON = parse(codecSpecJSONStr)
        codecSpecJSON.extract[CodecSpec]
      } else
        CodecSpec.default

    if (overwrite)
      hadoopConf.delete(path, recursive = true)
    else if (hadoopConf.exists(path))
      fatal(s"file already exists: $path")

    hc.hadoopConf.mkDir(path)

    val partitionCounts = rvd.writeRowsSplit(path, typ, codecSpec)

    val globalsPath = path + "/globals"
    hadoopConf.mkDir(globalsPath)
    writeGlobals(globalsPath, codecSpec)

    val rowsSpec = TableSpec(
      FileFormat.version.rep,
      hc.version,
      "../references",
      typ.rowsTableType,
      Map("globals" -> RVDComponentSpec("../globals/rows"),
        "rows" -> RVDComponentSpec("rows"),
        "partition_counts" -> PartitionCountsComponentSpec(partitionCounts)))
    rowsSpec.write(hc, path + "/rows")

    hadoopConf.writeTextFile(path + "/rows/_SUCCESS")(out => ())

    val entriesSpec = TableSpec(
      FileFormat.version.rep,
      hc.version,
      "../references",
      TableType(typ.entriesRVType, None, typ.globalType),
      Map("globals" -> RVDComponentSpec("../globals/rows"),
        "rows" -> RVDComponentSpec("rows"),
        "partition_counts" -> PartitionCountsComponentSpec(partitionCounts)))
    entriesSpec.write(hc, path + "/entries")

    hadoopConf.writeTextFile(path + "/entries/_SUCCESS")(out => ())

    hadoopConf.mkDir(path + "/cols")
    writeCols(path + "/cols", codecSpec)

    val refPath = path + "/references"
    hc.hadoopConf.mkDir(refPath)
    Array(typ.colType, typ.rowType, typ.entryType, typ.globalType).foreach { t =>
      ReferenceGenome.exportReferences(hc, refPath, t)
    }

    val spec = MatrixTableSpec(
      FileFormat.version.rep,
      hc.version,
      "references",
      typ,
      Map("globals" -> RVDComponentSpec("globals/rows"),
        "cols" -> RVDComponentSpec("cols/rows"),
        "rows" -> RVDComponentSpec("rows/rows"),
        "entries" -> RVDComponentSpec("entries/rows"),
        "partition_counts" -> PartitionCountsComponentSpec(partitionCounts)))
    spec.write(hc, path)

    hadoopConf.writeTextFile(path + "/_SUCCESS")(out => ())
  }

  def rowsRVD(): OrderedRVD = {
    val localRowType = typ.rowType
    val fullRowType = typ.rvRowType
    val localEntriesIndex = typ.entriesIdx
    rvd.mapPartitionsPreservesPartitioning(
      new OrderedRVDType(typ.rowPartitionKey.toArray, typ.rowKey.toArray, typ.rowType)
    ) { it =>
      val rv2b = new RegionValueBuilder()
      val rv2 = RegionValue()
      it.map { rv =>
        rv2b.set(rv.region)
        rv2b.start(localRowType)
        rv2b.startStruct()
        var i = 0
        while (i < fullRowType.size) {
          if (i != localEntriesIndex)
            rv2b.addField(fullRowType, rv, i)
          i += 1
        }
        rv2b.endStruct()
        rv2.set(rv.region, rv2b.end())
        rv2
      }
    }
  }

  def colsRVD(): RVD = {
    val hc = HailContext.get
    val signature = typ.colType

    new UnpartitionedRVD(
      signature,
      ContextRDD.parallelize(hc.sc, colValues.value.toArray.map(_.asInstanceOf[Row]))
        .cmapPartitions { (ctx, it) => it.toRegionValueIterator(ctx.region, signature) })
  }

  def entriesRVD(): RVD = {
    val resultStruct = typ.entriesTableType.rowType
    val fullRowType = typ.rvRowType
    val localEntriesIndex = typ.entriesIdx
    val localEntriesType = typ.entryArrayType
    val localColType = typ.colType
    val localEntryType = typ.entryType
    val localNCols = nCols

    val localColValues = colValues.broadcast.value

    rvd.boundary.mapPartitions(resultStruct, { (ctx, it) =>
      val rv2b = ctx.rvb
      val rv2 = RegionValue(ctx.region)

      it.flatMap { rv =>
        val gsOffset = fullRowType.loadField(rv, localEntriesIndex)
        (0 until localNCols).iterator
          .filter { i =>
            localEntriesType.isElementDefined(rv.region, gsOffset, i)
          }
          .map { i =>
            rv2b.clear()
            rv2b.start(resultStruct)
            rv2b.startStruct()

            var j = 0
            while (j < fullRowType.size) {
              if (j != localEntriesIndex)
                rv2b.addField(fullRowType, rv, j)
              j += 1
            }

            rv2b.addInlineRow(localColType, localColValues(i).asInstanceOf[Row])
            rv2b.addAllFields(localEntryType, rv.region, localEntriesType.elementOffsetInRegion(rv.region, gsOffset, i))
            rv2b.endStruct()
            rv2.setOffset(rv2b.end())
            rv2
          }
      }
    })
  }
}

object MatrixIR {
  def chooseColsWithArray(typ: MatrixType): (MatrixType, (MatrixValue, Array[Int]) => MatrixValue) = {
    val rowType = typ.rvRowType
    val keepType = TArray(+TInt32())
    val (rTyp, makeF) = ir.Compile[Long, Long, Long]("row", rowType,
      "keep", keepType,
      body = InsertFields(ir.Ref("row", rowType), Seq((MatrixType.entriesIdentifier,
        ir.ArrayMap(ir.Ref("keep", keepType), "i",
          ir.ArrayRef(ir.GetField(ir.In(0, rowType), MatrixType.entriesIdentifier),
            ir.Ref("i", TInt32())))))))
    assert(rTyp.isOfType(rowType))

    val newMatrixType = typ.copy(rvRowType = coerce[TStruct](rTyp))

    val keepF = { (mv: MatrixValue, keep: Array[Int]) =>
      val keepBc = mv.sparkContext.broadcast(keep)
      mv.copy(typ = newMatrixType,
        colValues = mv.colValues.copy(value = keep.map(mv.colValues.value)),
        rvd = mv.rvd.mapPartitionsPreservesPartitioning(newMatrixType.orvdType, { (ctx, it) =>
          val f = makeF()
          val keep = keepBc.value
          var rv2 = RegionValue()

          it.map { rv =>
            val region = ctx.region
            rv2.set(region,
              f(region, rv.offset, false, region.appendArrayInt(keep), false))
            rv2
          }
        }))
    }
    (newMatrixType, keepF)
  }

  def filterCols(typ: MatrixType): (MatrixType, (MatrixValue, (Annotation, Int) => Boolean) => MatrixValue) = {
    val (t, keepF) = chooseColsWithArray(typ)
    (t, { (mv: MatrixValue, p: (Annotation, Int) => Boolean) =>
      val keep = (0 until mv.nCols)
        .view
        .filter { i => p(mv.colValues.value(i), i) }
        .toArray
      keepF(mv, keep)
    })
  }

  def collectColsByKey(typ: MatrixType): (MatrixType, MatrixValue => MatrixValue) = {
    val oldRVRowType = typ.rvRowType
    val oldEntryArrayType = typ.entryArrayType
    val oldEntryType = typ.entryType

    val newColValueType = TStruct(typ.colValueStruct.fields.map(f => f.copy(typ = TArray(f.typ, required = true))))
    val newColType = typ.colKeyStruct ++ newColValueType
    val newEntryType = TStruct(typ.entryType.fields.map(f => f.copy(typ = TArray(f.typ, required = true))))
    val newMatrixType = typ.copyParts(colType = newColType, entryType = newEntryType)
    val newRVRowType = newMatrixType.rvRowType
    val localRowSize = newRVRowType.size

    (newMatrixType, { mv =>
      val colValMap: Map[Row, Array[Int]] = mv.colValues.value
        .map(_.asInstanceOf[Row])
        .zipWithIndex
        .groupBy[Row] { case (r, i) => typ.extractColKey(r) }
        .mapValues {
          _.map { case (r, i) => i }.toArray
        }
      val idxMap = colValMap.values.toArray

      val newColValues: BroadcastIndexedSeq = mv.colValues.copy(
        value = colValMap.map { case (key, idx) =>
          Row.fromSeq(key.toSeq ++ newColValueType.fields.map { f =>
            idx.map { i =>
              mv.colValues.value(i).asInstanceOf[Row]
                .get(typ.colValueFieldIdx(f.index))
            }.toIndexedSeq
          })
        }.toIndexedSeq)

      val newRVD = mv.rvd.mapPartitionsPreservesPartitioning(newMatrixType.orvdType) { it =>
        val rvb = new RegionValueBuilder()
        val rv2 = RegionValue()

        it.map { rv =>
          val entryArrayOffset = oldRVRowType.loadField(rv, oldRVRowType.fieldIdx(MatrixType.entriesIdentifier))

          rvb.set(rv.region)
          rvb.start(newRVRowType)
          rvb.startStruct()
          var i = 0
          while (i < localRowSize - 1) {
            rvb.addField(oldRVRowType, rv, i)
            i += 1
          }
          rvb.startArray(idxMap.length) // start entries array
          i = 0
          while (i < idxMap.length) {
            rvb.startStruct()
            var j = 0
            while (j < newEntryType.size) {
              rvb.startArray(idxMap(i).length)
              var k = 0
              while (k < idxMap(i).length) {
                rvb.addField(
                  oldEntryType,
                  rv.region,
                  oldEntryArrayType.loadElement(rv.region, entryArrayOffset, idxMap(i)(k)),
                  j
                )
                k += 1
              }
              rvb.endArray()
              j += 1
            }
            rvb.endStruct()
            i += 1
          }
          rvb.endArray()
          rvb.endStruct()
          rv2.set(rv.region, rvb.end())
          rv2
        }
      }

      mv.copy(
        typ = newMatrixType,
        colValues = newColValues,
        rvd = newRVD
      )
    })
  }
}

abstract sealed class MatrixIR extends BaseIR {
  def typ: MatrixType

  def partitionCounts: Option[Array[Long]] = None

  def execute(hc: HailContext): MatrixValue
}

case class MatrixLiteral(
  typ: MatrixType,
  value: MatrixValue) extends MatrixIR {

  def children: IndexedSeq[BaseIR] = Array.empty[BaseIR]

  def execute(hc: HailContext): MatrixValue = value

  def copy(newChildren: IndexedSeq[BaseIR]): MatrixLiteral = {
    assert(newChildren.isEmpty)
    MatrixLiteral(typ, value)
  }

  override def toString: String = "MatrixLiteral(...)"
}

abstract class MatrixReader {
  def apply(mr: MatrixRead): MatrixValue
}

case class MatrixNativeReader(path: String, spec: MatrixTableSpec) extends MatrixReader {
  def apply(mr: MatrixRead): MatrixValue = {
    val hc = HailContext.get

    val requestedType = mr.typ
    assert(PruneDeadFields.isSupertype(requestedType, spec.matrix_type))

    val globals = spec.globalsComponent.readLocal(hc, path, requestedType.globalType)(0).asInstanceOf[Row]

    val colAnnotations =
      if (mr.dropCols)
        FastIndexedSeq.empty[Annotation]
      else
        spec.colsComponent.readLocal(hc, path, requestedType.colType).asInstanceOf[IndexedSeq[Annotation]]

    val rvd =
      if (mr.dropRows)
        OrderedRVD.empty(hc.sc, requestedType.orvdType)
      else {
        val fullRowType = requestedType.rvRowType
        val rowType = requestedType.rowType
        val localEntriesIndex = requestedType.entriesIdx

        val rowsRVD = spec.rowsComponent.read(hc, path, requestedType.rowType).asInstanceOf[OrderedRVD]
        if (mr.dropCols) {
          val (t2, makeF) = ir.Compile[Long, Long](
            "row", requestedType.rowType,
            MakeStruct(
              fullRowType.fields.zipWithIndex.map { case (f, i) =>
                  val v: IR = if (i == localEntriesIndex)
                    MakeArray(FastSeq.empty, TArray(requestedType.entryType))
                  else
                    GetField(Ref("row", requestedType.rowType), f.name)
                  f.name -> v
              }))
          assert(t2 == fullRowType)

          rowsRVD.mapPartitionsPreservesPartitioning(requestedType.orvdType) { it =>
            val f = makeF()
            val rv2 = RegionValue()
            it.map { rv =>
              val off = f(rv.region, rv.offset, false)
              rv2.set(rv.region, off)
              rv2
            }
          }
        } else {
          val entriesRVD = spec.entriesComponent.read(hc, path, requestedType.entriesRVType)
          val entriesRowType = entriesRVD.rowType

          val (t2, makeF) = ir.Compile[Long, Long, Long](
            "row", requestedType.rowType,
            "entriesRow", entriesRowType,
            MakeStruct(
              fullRowType.fields.zipWithIndex.map { case (f, i) =>
                val v: IR = if (i == localEntriesIndex)
                  GetField(Ref("entriesRow", entriesRowType), MatrixType.entriesIdentifier)
                else
                  GetField(Ref("row", requestedType.rowType), f.name)
                f.name -> v
              }))
          assert(t2 == fullRowType)

          rowsRVD.zipPartitions(requestedType.orvdType, rowsRVD.partitioner, entriesRVD, preservesPartitioning = true) { (ctx, it1, it2) =>
            val f = makeF()
            val rvb = ctx.rvb
            val region = ctx.region
            val rv3 = RegionValue(region)
            new Iterator[RegionValue] {
              def hasNext = {
                val hn1 = it1.hasNext
                val hn2 = it2.hasNext
                assert(hn1 == hn2)
                hn1
              }

              def next(): RegionValue = {
                val rv1 = it1.next()
                val rv2 = it2.next()
                val off = f(region, rv1.offset, false, rv2.offset, false)
                rv3.set(region, off)
                rv3
              }
            }
          }
        }
      }

    MatrixValue(
      requestedType,
      BroadcastRow(globals, requestedType.globalType, hc.sc),
      BroadcastIndexedSeq(colAnnotations, TArray(requestedType.colType), hc.sc),
      rvd)
  }
}

case class MatrixRangeReader(typ: MatrixType, nRows: Int, nCols: Int, nPartitions: Option[Int]) extends MatrixReader {
  def apply(mr: MatrixRead): MatrixValue = {
    assert(mr.typ == typ)

    val partCounts = mr.partitionCounts.get.map(_.toInt)
    val nPartitionsAdj = mr.partitionCounts.get.length

    val hc = HailContext.get
    val localRVType = typ.rvRowType
    val partStarts = partCounts.scanLeft(0)(_ + _)
    val localNCols = if (mr.dropCols) 0 else nCols

    val rvd = if (mr.dropRows)
      OrderedRVD.empty(hc.sc, typ.orvdType)
    else {
      OrderedRVD(typ.orvdType,
        new OrderedRVDPartitioner(typ.rowPartitionKey.toArray,
          typ.rowKeyStruct,
          Array.tabulate(nPartitionsAdj) { i =>
            val start = partStarts(i)
            Interval(Row(start), Row(start + partCounts(i)), includesStart = true, includesEnd = false)
          }),
        ContextRDD.parallelize[RVDContext](hc.sc, Range(0, nPartitionsAdj), nPartitionsAdj)
          .cmapPartitionsWithIndex { (i, ctx, _) =>
            val region = ctx.region
            val rvb = ctx.rvb
            val rv = RegionValue(region)

            val start = partStarts(i)
            Iterator.range(start, start + partCounts(i))
              .map { j =>
                rvb.start(localRVType)
                rvb.startStruct()

                // row idx field
                rvb.addInt(j)

                // entries field
                rvb.startArray(localNCols)
                var i = 0
                while (i < localNCols) {
                  rvb.startStruct()
                  rvb.endStruct()
                  i += 1
                }
                rvb.endArray()

                rvb.endStruct()
                rv.setOffset(rvb.end())
                rv
              }
          })
    }

    MatrixValue(typ,
      BroadcastRow(Row(), typ.globalType, hc.sc),
      BroadcastIndexedSeq(
        Iterator.range(0, localNCols)
          .map(Row(_))
          .toFastIndexedSeq,
        TArray(typ.colType),
        hc.sc),
      rvd)
  }
}

case class MatrixRead(
  typ: MatrixType,
  override val partitionCounts: Option[Array[Long]],
  dropCols: Boolean,
  dropRows: Boolean,
  reader: MatrixReader) extends MatrixIR {

  def children: IndexedSeq[BaseIR] = Array.empty[BaseIR]

  def copy(newChildren: IndexedSeq[BaseIR]): MatrixRead = {
    assert(newChildren.isEmpty)
    MatrixRead(typ, partitionCounts, dropCols, dropRows, reader)
  }

  def execute(hc: HailContext): MatrixValue = reader(this)

  override def toString: String = s"MatrixRead($typ, partitionCounts = $partitionCounts, dropCols = $dropCols, dropRows = $dropRows)"
}

case class MatrixFilterCols(
  child: MatrixIR,
  pred: IR) extends MatrixIR {

  def children: IndexedSeq[BaseIR] = Array(child, pred)

  def copy(newChildren: IndexedSeq[BaseIR]): MatrixFilterCols = {
    assert(newChildren.length == 2)
    MatrixFilterCols(newChildren(0).asInstanceOf[MatrixIR], newChildren(1).asInstanceOf[IR])
  }

  val (typ, filterF) = MatrixIR.filterCols(child.typ)

  def execute(hc: HailContext): MatrixValue = {
    val prev = child.execute(hc)

    val localGlobals = prev.globals.broadcast
    val localColType = typ.colType

    val (rTyp, predCompiledFunc) = ir.Compile[Long, Long, Boolean](
      "global", typ.globalType,
      "sa", typ.colType,
      pred)

    val p = (sa: Annotation, i: Int) => {
      Region.scoped { colRegion =>
        // FIXME: it would be nice to only load the globals once per matrix
        val rvb = new RegionValueBuilder(colRegion)
        rvb.start(typ.globalType)
        rvb.addAnnotation(typ.globalType, localGlobals.value)
        val globalRVoffset = rvb.end()

        val colRVb = new RegionValueBuilder(colRegion)
        colRVb.start(localColType)
        colRVb.addAnnotation(localColType, sa)
        val colRVoffset = colRVb.end()
        predCompiledFunc()(colRegion, globalRVoffset, false, colRVoffset, false)
      }
    }

    filterF(prev, p)
  }
}

case class MatrixFilterRows(
  child: MatrixIR,
  pred: IR
) extends MatrixIR {

  def children: IndexedSeq[BaseIR] = Array(child, pred)

  def copy(newChildren: IndexedSeq[BaseIR]): MatrixFilterRows = {
    assert(newChildren.length == 2)
    MatrixFilterRows(newChildren(0).asInstanceOf[MatrixIR], newChildren(1).asInstanceOf[IR])
  }

  def typ: MatrixType = child.typ

  val tAggElt: Type = child.typ.entryType
  val aggSymTab = Map(
    "global" -> (0, child.typ.globalType),
    "va" -> (1, child.typ.rvRowType),
    "g" -> (2, child.typ.entryType),
    "sa" -> (3, child.typ.colType))

  val tAgg = TAggregable(tAggElt, aggSymTab)

  def execute(hc: HailContext): MatrixValue = {
    val prev = child.execute(hc)
    assert(child.typ == prev.typ)

    val localGlobalsType = prev.typ.globalType
    val localColsType = TArray(prev.typ.colType)
    val localNCols = prev.nCols
    val colValuesBc = prev.colValues.broadcast
    val globalsBc = prev.globals.broadcast

    val colValuesType = TArray(prev.typ.colType)
    val vaType = prev.typ.rvRowType
    val (rvAggs, makeInit, makeSeq, aggResultType, makePred, rTyp) = ir.CompileWithAggregators[Long, Long, Long, Long, Long, Boolean](
      "global", prev.typ.globalType,
      "va", vaType,
      "global", prev.typ.globalType,
      "colValues", colValuesType,
      "va", vaType,
      pred, { (nAggs: Int, initialize: IR) => initialize }, { (nAggs: Int, sequence: IR) =>
        ir.ArrayFor(
          ir.ArrayRange(ir.I32(0), ir.I32(localNCols), ir.I32(1)),
          "i",
          ir.Let("sa", ir.ArrayRef(ir.Ref("colValues", colValuesType), ir.Ref("i", TInt32())),
            ir.Let("g", ir.ArrayRef(
              ir.GetField(ir.Ref("va", vaType), MatrixType.entriesIdentifier),
              ir.Ref("i", TInt32())),
              sequence)))
      })

    val filteredRDD = prev.rvd.mapPartitionsPreservesPartitioning(prev.typ.orvdType, { (ctx, it) =>
      val rvb = new RegionValueBuilder()
      val initialize = makeInit()
      val sequence = makeSeq()
      val predicate = makePred()

      val partRegion = ctx.freshContext.region

      rvb.set(partRegion)
      rvb.start(localGlobalsType)
      rvb.addAnnotation(localGlobalsType, globalsBc.value)
      val globals = rvb.end()

      val cols = if (rvAggs.nonEmpty) {
        rvb.start(localColsType)
        rvb.addAnnotation(localColsType, colValuesBc.value)
        rvb.end()
      } else 0L

      it.filter { rv =>
        val region = rv.region
        val row = rv.offset

        val aggResultsOff = if (rvAggs.nonEmpty) {
          var j = 0
          while (j < rvAggs.length) {
            rvAggs(j).clear()
            j += 1
          }

          initialize(region, rvAggs, globals, false, row, false)
          sequence(region, rvAggs, globals, false, cols, false, row, false)

          rvb.start(aggResultType)
          rvb.startStruct()

          j = 0
          while (j < rvAggs.length) {
            rvAggs(j).result(rvb)
            j += 1
          }
          rvb.endStruct()
          val aggResultsOff = rvb.end()
          aggResultsOff
        } else 0L

        if (predicate(region, aggResultsOff, false, globals, false, row, false))
          true
        else {
          ctx.region.clear()
          false
        }
      }
    })

    prev.copy(rvd = filteredRDD)
  }
}

case class ChooseCols(child: MatrixIR, oldIndices: Array[Int]) extends MatrixIR {
  def children: IndexedSeq[BaseIR] = Array(child)

  def copy(newChildren: IndexedSeq[BaseIR]): ChooseCols = {
    assert(newChildren.length == 1)
    ChooseCols(newChildren(0).asInstanceOf[MatrixIR], oldIndices)
  }

  val (typ, colsF) = MatrixIR.chooseColsWithArray(child.typ)

  override def partitionCounts: Option[Array[Long]] = child.partitionCounts

  def execute(hc: HailContext): MatrixValue = {
    val prev = child.execute(hc)

    colsF(prev, oldIndices)
  }
}

case class CollectColsByKey(child: MatrixIR) extends MatrixIR {
  def children: IndexedSeq[BaseIR] = Array(child)

  def copy(newChildren: IndexedSeq[BaseIR]): CollectColsByKey = {
    assert(newChildren.length == 1)
    CollectColsByKey(newChildren(0).asInstanceOf[MatrixIR])
  }

  val (typ, groupF) = MatrixIR.collectColsByKey(child.typ)

  def execute(hc: HailContext): MatrixValue = {
    val prev = child.execute(hc)
    groupF(prev)
  }
}

case class MatrixAggregateRowsByKey(child: MatrixIR, expr: IR) extends MatrixIR {
  require(child.typ.rowKey.nonEmpty)

  def children: IndexedSeq[BaseIR] = Array(child, expr)

  def copy(newChildren: IndexedSeq[BaseIR]): MatrixAggregateRowsByKey = {
    assert(newChildren.length == 2)
    val IndexedSeq(newChild: MatrixIR, newExpr: IR) = newChildren
    MatrixAggregateRowsByKey(newChild, newExpr)
  }

  val typ: MatrixType = child.typ.copyParts(
    rowType = child.typ.orvdType.kType,
    entryType = coerce[TStruct](expr.typ)
  )

  def execute(hc: HailContext): MatrixValue = {
    val prev = child.execute(hc)

    val nCols = prev.nCols

    val (rvAggs, makeInit, makeSeq, aggResultType, makeAnnotate, rTyp) = ir.CompileWithAggregators[Long, Long, Long, Long, Long](
      "global", child.typ.globalType,
      "global", child.typ.globalType,
      "colValues", TArray(child.typ.colType),
      "va", child.typ.rvRowType,
      expr, { (nAggs, initializeIR) =>
        val colIdx = ir.genUID()

        def rewrite(x: IR): IR = {
          x match {
            case InitOp(i, args, aggSig) =>
              InitOp(
                ir.ApplyBinaryPrimOp(ir.Add(),
                  ir.ApplyBinaryPrimOp(ir.Multiply(), ir.Ref(colIdx, TInt32()), ir.I32(nAggs)),
                  i),
                args,
                aggSig)
            case _ =>
              ir.Recur(rewrite)(x)
          }
        }

        ir.ArrayFor(
          ir.ArrayRange(ir.I32(0), ir.I32(nCols), ir.I32(1)),
          colIdx,
          rewrite(initializeIR))
      }, { (nAggs, sequenceIR) =>
        val colIdx = ir.genUID()

        def rewrite(x: IR): IR = {
          x match {
            case SeqOp(a, i, agg, args) =>
              SeqOp(a,
                ir.ApplyBinaryPrimOp(ir.Add(),
                  ir.ApplyBinaryPrimOp(ir.Multiply(), ir.Ref(colIdx, TInt32()), ir.I32(nAggs)),
                  i),
                agg, args)
            case _ =>
              ir.Recur(rewrite)(x)
          }
        }

        ir.ArrayFor(
          ir.ArrayRange(ir.I32(0), ir.I32(nCols), ir.I32(1)),
          colIdx,
          ir.Let("sa", ir.ArrayRef(ir.Ref("colValues", TArray(prev.typ.colType)), ir.Ref(colIdx, TInt32())),
            ir.Let("g", ir.ArrayRef(
              ir.GetField(ir.Ref("va", prev.typ.rvRowType), MatrixType.entriesIdentifier),
              ir.Ref(colIdx, TInt32())),
              rewrite(sequenceIR))))
      })
    val nAggs = rvAggs.length

    assert(coerce[TStruct](rTyp) == typ.entryType, s"$rTyp, ${ typ.entryType }")

    val newRVType = typ.rvRowType
    val newRowType = typ.rowType
    val rvType = prev.typ.rvRowType
    val selectIdx = prev.typ.orvdType.kRowFieldIdx
    val keyOrd = prev.typ.orvdType.kRowOrd
    val localGlobalsType = prev.typ.globalType
    val localColsType = TArray(prev.typ.colType)
    val colValuesBc = prev.colValues.broadcast
    val globalsBc = prev.globals.broadcast
    val newRVD = prev.rvd.boundary.mapPartitionsPreservesPartitioning(typ.orvdType, { (ctx, it) =>
      val rvb = new RegionValueBuilder()
      val partRegion = ctx.freshContext.region

      rvb.set(partRegion)
      rvb.start(localGlobalsType)
      rvb.addAnnotation(localGlobalsType, globalsBc.value)
      val globals = rvb.end()

      rvb.start(localColsType)
      rvb.addAnnotation(localColsType, colValuesBc.value)
      val cols = rvb.end()

      val initialize = makeInit()
      val sequence = makeSeq()
      val annotate = makeAnnotate()

      new Iterator[RegionValue] {
        var isEnd = false
        var current: RegionValue = _
        val rvRowKey: WritableRegionValue = WritableRegionValue(newRowType, ctx.freshRegion)
        val consumerRegion = ctx.region
        val newRV = RegionValue(consumerRegion)

        val colRVAggs = new Array[RegionValueAggregator](nAggs * nCols)

        {
          var i = 0
          while (i < nCols) {
            var j = 0
            while (j < nAggs) {
              colRVAggs(i * nAggs + j) = rvAggs(j).newInstance()
              j += 1
            }
            i += 1
          }
        }

        def hasNext: Boolean = {
          if (isEnd || (current == null && !it.hasNext)) {
            isEnd = true
            return false
          }
          if (current == null)
            current = it.next()
          true
        }

        def next(): RegionValue = {
          if (!hasNext)
            throw new java.util.NoSuchElementException()

          rvRowKey.setSelect(rvType, selectIdx, current)

          colRVAggs.foreach(_.clear())

          initialize(current.region, colRVAggs, globals, false)

          do {
            sequence(current.region, colRVAggs,
              globals, false,
              cols, false,
              current.offset, false)
            current = null
          } while (hasNext && keyOrd.equiv(rvRowKey.value, current))

          rvb.set(consumerRegion)

          val aggResultsOffsets = Array.tabulate(nCols) { i =>
            rvb.start(aggResultType)
            rvb.startStruct()
            var j = 0
            while (j < nAggs) {
              colRVAggs(i * nAggs + j).result(rvb)
              j += 1
            }
            rvb.endStruct()
            rvb.end()
          }

          rvb.start(newRVType)
          rvb.startStruct()

          {
            var i = 0
            while (i < newRowType.size) {
              rvb.addField(newRowType, rvRowKey.value, i)
              i += 1
            }
          }

          rvb.startArray(nCols)

          {
            var i = 0
            while (i < nCols) {
              val newEntryOff = annotate(consumerRegion,
                aggResultsOffsets(i), false,
                globals, false)

              rvb.addRegionValue(rTyp, consumerRegion, newEntryOff)

              i += 1
            }
          }
          rvb.endArray()
          rvb.endStruct()
          newRV.setOffset(rvb.end())
          newRV
        }
      }
    })

    prev.copy(rvd = newRVD, typ = typ)
  }
}

case class MatrixAggregateColsByKey(child: MatrixIR, aggIR: IR) extends MatrixIR {
  require(child.typ.colKey.nonEmpty)

  def children: IndexedSeq[BaseIR] = Array(child, aggIR)

  def copy(newChildren: IndexedSeq[BaseIR]): MatrixAggregateColsByKey = {
    assert(newChildren.length == 2)
    val IndexedSeq(newChild: MatrixIR, newExpr: IR) = newChildren
    MatrixAggregateColsByKey(newChild, newExpr)
  }

  val typ = {
    val newEntryType = aggIR.typ
    child.typ.copyParts(entryType = coerce[TStruct](newEntryType), colType = child.typ.colKeyStruct)
  }

  def execute(hc: HailContext): MatrixValue = {
    val mv = child.execute(hc)

    // local things for serialization
    val oldNCols = mv.nCols
    val oldRVRowType = mv.typ.rvRowType
    val oldColsType = TArray(mv.typ.colType)
    val oldColValues = mv.colValues
    val oldColValuesBc = mv.colValues.broadcast
    val oldGlobalsBc = mv.globals.broadcast
    val oldGlobalsType = mv.typ.globalType

    val newRVType = typ.rvRowType
    val newColType = typ.colType
    val newEntriesIndex = typ.entriesIdx

    val keyIndices = mv.typ.colKey.map(k => mv.typ.colType.field(k).index)
    val keys = oldColValuesBc.value.map { a => Row.fromSeq(keyIndices.map(a.asInstanceOf[Row].get)) }.toSet.toArray
    val nKeys = keys.length
    val newColValues = oldColValues.copy(value = keys, t = TArray(newColType))

    val keysByColumn = oldColValues.value.map { sa => Row.fromSeq(keyIndices.map(sa.asInstanceOf[Row].get)) }
    val keyMap = keys.zipWithIndex.toMap
    val newColumnIndices = keysByColumn.map { k => keyMap(k) }.toArray
    val newColumnIndicesType = TArray(TInt32())

    val transformInitOp: (Int, IR) => IR = { (nAggs, initOpIR) =>
      val colIdx = ir.genUID()

      def rewrite(x: IR): IR = {
        x match {
          case InitOp(i, args, aggSig) =>
            InitOp(ir.ApplyBinaryPrimOp(ir.Add(),
              ir.ApplyBinaryPrimOp(
                ir.Multiply(),
                ir.ArrayRef(ir.Ref("newColumnIndices", newColumnIndicesType), ir.Ref(colIdx, TInt32())),
                ir.I32(nAggs)),
              i),
              args,
              aggSig)
          case _ =>
            ir.Recur(rewrite)(x)
        }
      }

      ir.ArrayFor(
        ir.ArrayRange(ir.I32(0), ir.I32(oldNCols), ir.I32(1)),
        colIdx,
        rewrite(initOpIR))
    }

    val transformSeqOp: (Int, IR) => IR = { (nAggs, seqOpIR) =>
      val colIdx = ir.genUID()

      def rewrite(x: IR): IR = {
        x match {
          case SeqOp(a, i, aggSig, args) =>
            SeqOp(a,
              ir.ApplyBinaryPrimOp(ir.Add(),
                ir.ApplyBinaryPrimOp(
                  ir.Multiply(),
                  ir.ArrayRef(ir.Ref("newColumnIndices", newColumnIndicesType), ir.Ref(colIdx, TInt32())),
                  ir.I32(nAggs)),
                i),
              aggSig, args)
          case _ =>
            ir.Recur(rewrite)(x)
        }
      }

      ir.ArrayFor(
        ir.ArrayRange(ir.I32(0), ir.I32(oldNCols), ir.I32(1)),
        colIdx,
        ir.Let("sa", ir.ArrayRef(ir.Ref("colValues", oldColsType), ir.Ref(colIdx, TInt32())),
          ir.Let("g", ir.ArrayRef(
            ir.GetField(ir.Ref("va", oldRVRowType), MatrixType.entriesIdentifier),
            ir.Ref(colIdx, TInt32())),
            rewrite(seqOpIR)
          )))
    }

    val (rvAggs, initOps, seqOps, aggResultType, f, rTyp) = ir.CompileWithAggregators[Long, Long, Long, Long, Long, Long, Long, Long](
      "global", oldGlobalsType,
      "va", oldRVRowType,
      "newColumnIndices", newColumnIndicesType,
      "global", oldGlobalsType,
      "colValues", oldColsType,
      "va", oldRVRowType,
      "newColumnIndices", newColumnIndicesType,
      aggIR,
      transformInitOp,
      transformSeqOp)
    assert(rTyp == typ.entryType)

    val nAggs = rvAggs.length

    val colRVAggs = new Array[RegionValueAggregator](nAggs * nKeys)
    var i = 0
    while (i < nKeys) {
      var j = 0
      while (j < nAggs) {
        colRVAggs(i * nAggs + j) = rvAggs(j).newInstance()
        j += 1
      }
      i += 1
    }

    val mapPartitionF = { (ctx: RVDContext, it: Iterator[RegionValue]) =>
      val rvb = new RegionValueBuilder()
      val newRV = RegionValue()

      val partitionRegion = ctx.freshContext.region

      rvb.set(partitionRegion)
      rvb.start(oldGlobalsType)
      rvb.addAnnotation(oldGlobalsType, oldGlobalsBc.value)
      val partitionWideGlobalsOffset = rvb.end()

      rvb.start(oldColsType)
      rvb.addAnnotation(oldColsType, oldColValuesBc.value)
      val partitionWideColumnsOffset = rvb.end()

      rvb.start(newColumnIndicesType)
      rvb.startArray(newColumnIndices.length)
      var i = 0
      while (i < newColumnIndices.length) {
        rvb.addInt(newColumnIndices(i))
        i += 1
      }
      rvb.endArray()
      val partitionWideMapOffset = rvb.end()

      it.map { rv =>
        val oldRow = rv.offset

        rvb.set(rv.region)
        rvb.start(oldGlobalsType)
        rvb.addRegionValue(oldGlobalsType, partitionRegion, partitionWideGlobalsOffset)
        val globalsOffset = rvb.end()

        rvb.set(rv.region)
        rvb.start(oldColsType)
        rvb.addRegionValue(oldColsType, partitionRegion, partitionWideColumnsOffset)
        val columnsOffset = rvb.end()

        rvb.set(rv.region)
        rvb.start(newColumnIndicesType)
        rvb.addRegionValue(newColumnIndicesType, partitionRegion, partitionWideMapOffset)
        val mapOffset = rvb.end()

        var j = 0
        while (j < colRVAggs.length) {
          colRVAggs(j).clear()
          j += 1
        }

        initOps()(rv.region, colRVAggs, globalsOffset, false, oldRow, false, mapOffset, false)
        seqOps()(rv.region, colRVAggs, globalsOffset, false, columnsOffset, false, oldRow, false, mapOffset, false)

        val resultOffsets = Array.tabulate(nKeys) { i =>
          var j = 0
          rvb.start(aggResultType)
          rvb.startStruct()
          while (j < nAggs) {
            colRVAggs(i * nAggs + j).result(rvb)
            j += 1
          }
          rvb.endStruct()
          val aggResultOffset = rvb.end()
          f()(rv.region, aggResultOffset, false, globalsOffset, false, oldRow, false, mapOffset, false)
        }

        rvb.start(newRVType)
        rvb.startStruct()
        var k = 0
        while (k < newEntriesIndex) {
          rvb.addField(oldRVRowType, rv, k)
          k += 1
        }

        i = 0
        rvb.startArray(nKeys)
        while (i < nKeys) {
          rvb.addRegionValue(rTyp, rv.region, resultOffsets(i))
          i += 1
        }
        rvb.endArray()
        k += 1

        while (k < newRVType.fields.length) {
          rvb.addField(oldRVRowType, rv, k)
          k += 1
        }

        rvb.endStruct()
        rv.setOffset(rvb.end())
        rv
      }
    }

    val newRVD = mv.rvd.mapPartitionsPreservesPartitioning(typ.orvdType, mapPartitionF)
    mv.copy(typ = typ, colValues = newColValues, rvd = newRVD)
  }
}

case class MatrixMapEntries(child: MatrixIR, newEntries: IR) extends MatrixIR {

  def children: IndexedSeq[BaseIR] = Array(child, newEntries)

  def copy(newChildren: IndexedSeq[BaseIR]): MatrixMapEntries = {
    assert(newChildren.length == 2)
    MatrixMapEntries(newChildren(0).asInstanceOf[MatrixIR], newChildren(1).asInstanceOf[IR])
  }

  val newRow = {
    val vaType = child.typ.rvRowType
    val saType = TArray(child.typ.colType)

    val arrayLength = ArrayLen(GetField(Ref("va", vaType), MatrixType.entriesIdentifier))
    val idxEnv = new Env[IR]()
      .bind("g", ArrayRef(GetField(Ref("va", vaType), MatrixType.entriesIdentifier), Ref("i", TInt32())))
      .bind("sa", ArrayRef(Ref("sa", saType), Ref("i", TInt32())))
    val entries = ArrayMap(ArrayRange(I32(0), arrayLength, I32(1)), "i", Subst(newEntries, idxEnv))
    InsertFields(Ref("va", vaType), Seq((MatrixType.entriesIdentifier, entries)))
  }

  val typ: MatrixType =
    child.typ.copy(rvRowType = newRow.typ)

  override def partitionCounts: Option[Array[Long]] = child.partitionCounts

  def execute(hc: HailContext): MatrixValue = {
    val prev = child.execute(hc)

    val localGlobalsType = typ.globalType
    val localColsType = TArray(typ.colType)
    val colValuesBc = prev.colValues.broadcast
    val globalsBc = prev.globals.broadcast

    val (rTyp, f) = ir.Compile[Long, Long, Long, Long](
      "global", localGlobalsType,
      "va", prev.typ.rvRowType,
      "sa", localColsType,
      newRow)
    assert(rTyp == typ.rvRowType)

    val newRVD = prev.rvd.mapPartitionsPreservesPartitioning(typ.orvdType, { (ctx, it) =>
      val rvb = new RegionValueBuilder()
      val newRV = RegionValue()
      val rowF = f()
      val partitionRegion = ctx.freshRegion

      rvb.set(partitionRegion)
      rvb.start(localGlobalsType)
      rvb.addAnnotation(localGlobalsType, globalsBc.value)
      val globals = rvb.end()

      rvb.start(localColsType)
      rvb.addAnnotation(localColsType, colValuesBc.value)
      val cols = rvb.end()

      it.map { rv =>
        val region = rv.region
        val oldRow = rv.offset

        val off = rowF(region, globals, false, oldRow, false, cols, false)

        newRV.set(region, off)
        newRV
      }
    })
    prev.copy(typ = typ, rvd = newRVD)
  }
}

case class MatrixMapRows(child: MatrixIR, newRow: IR, newKey: Option[(IndexedSeq[String], IndexedSeq[String])]) extends MatrixIR {

  def children: IndexedSeq[BaseIR] = Array(child, newRow)

  def copy(newChildren: IndexedSeq[BaseIR]): MatrixMapRows = {
    assert(newChildren.length == 2)
    MatrixMapRows(newChildren(0).asInstanceOf[MatrixIR], newChildren(1).asInstanceOf[IR], newKey)
  }

  val newRVRow = InsertFields(newRow, Seq(
    MatrixType.entriesIdentifier -> GetField(Ref("va", child.typ.rvRowType), MatrixType.entriesIdentifier)))

  val typ: MatrixType = {
    val newRowKey = newKey.map { case (pk, k) => pk ++ k }.getOrElse(child.typ.rowKey)
    val newPartitionKey = newKey.map { case (pk, _) => pk }.getOrElse(child.typ.rowPartitionKey)
    child.typ.copy(rvRowType = newRVRow.typ, rowKey = newRowKey, rowPartitionKey = newPartitionKey)
  }

  override def partitionCounts: Option[Array[Long]] = child.partitionCounts

  def execute(hc: HailContext): MatrixValue = {
    val prev = child.execute(hc)
    assert(prev.typ == child.typ)

    val localGlobalsType = prev.typ.globalType
    val localColsType = TArray(prev.typ.colType)
    val localNCols = prev.nCols
    val colValuesBc = prev.colValues.broadcast
    val globalsBc = prev.globals.broadcast

    val colValuesType = TArray(prev.typ.colType)
    val vaType = prev.typ.rvRowType
    val (rvAggs, initOps, seqOps, aggResultType, f, rTyp) = ir.CompileWithAggregators[Long, Long, Long, Long, Long, Long](
      "global", prev.typ.globalType,
      "va", vaType,
      "global", prev.typ.globalType,
      "colValues", colValuesType,
      "va", vaType,
      newRVRow,
      (nAggs: Int, initOpIR: IR) => initOpIR, { (nAggs: Int, seqOpIR: IR) =>
        ir.ArrayFor(
          ir.ArrayRange(ir.I32(0), ir.I32(localNCols), ir.I32(1)),
          "i",
          ir.Let("sa", ir.ArrayRef(ir.Ref("colValues", colValuesType), ir.Ref("i", TInt32())),
            ir.Let("g", ir.ArrayRef(
              ir.GetField(ir.Ref("va", vaType), MatrixType.entriesIdentifier),
              ir.Ref("i", TInt32())),
              seqOpIR)))
      })
    assert(rTyp == typ.rvRowType, s"$rTyp, ${ typ.rvRowType }")

    val mapPartitionF = { (ctx: RVDContext, it: Iterator[RegionValue]) =>
      val rvb = new RegionValueBuilder()
      val newRV = RegionValue()
      val rowF = f()

      val partRegion = ctx.freshContext.region

      rvb.set(partRegion)
      rvb.start(localGlobalsType)
      rvb.addAnnotation(localGlobalsType, globalsBc.value)
      val globals = rvb.end()

      val cols = if (rvAggs.nonEmpty) {
        rvb.start(localColsType)
        rvb.addAnnotation(localColsType, colValuesBc.value)
        rvb.end()
      } else 0L

      it.map { rv =>
        val region = rv.region
        val oldRow = rv.offset

        val aggResultsOff = if (rvAggs.nonEmpty) {
          var j = 0
          while (j < rvAggs.length) {
            rvAggs(j).clear()
            j += 1
          }

          initOps()(region, rvAggs, globals, false, oldRow, false)
          seqOps()(region, rvAggs, globals, false, cols, false, oldRow, false)

          rvb.start(aggResultType)
          rvb.startStruct()

          j = 0
          while (j < rvAggs.length) {
            rvAggs(j).result(rvb)
            j += 1
          }
          rvb.endStruct()
          val aggResultsOff = rvb.end()
          aggResultsOff
        } else
          0

        val off = rowF(region, aggResultsOff, false, globals, false, oldRow, false)

        newRV.set(region, off)
        newRV
      }
    }

    if (newKey.isDefined) {
      prev.copy(typ = typ,
        rvd = OrderedRVD.coerce(
          typ.orvdType,
          prev.rvd.mapPartitions(typ.rvRowType, mapPartitionF)))
    } else {
      val newRVD = prev.rvd.mapPartitionsPreservesPartitioning(typ.orvdType, mapPartitionF)
      prev.copy(typ = typ, rvd = newRVD)
    }
  }
}

case class MatrixMapCols(child: MatrixIR, newCol: IR, newKey: Option[IndexedSeq[String]]) extends MatrixIR {
  def children: IndexedSeq[BaseIR] = Array(child, newCol)

  def copy(newChildren: IndexedSeq[BaseIR]): MatrixMapCols = {
    assert(newChildren.length == 2)
    MatrixMapCols(newChildren(0).asInstanceOf[MatrixIR], newChildren(1).asInstanceOf[IR], newKey)
  }

  val tAggElt: Type = child.typ.entryType
  val aggSymTab = Map(
    "global" -> (0, child.typ.globalType),
    "va" -> (1, child.typ.rvRowType),
    "g" -> (2, child.typ.entryType),
    "sa" -> (3, child.typ.colType))

  val tAgg = TAggregable(tAggElt, aggSymTab)

  val typ: MatrixType = {
    val newColType = newCol.typ.asInstanceOf[TStruct]
    val newColKey = newKey.getOrElse(child.typ.colKey)
    child.typ.copy(colKey = newColKey, colType = newColType)
  }

  override def partitionCounts: Option[Array[Long]] = child.partitionCounts

  def execute(hc: HailContext): MatrixValue = {
    val prev = child.execute(hc)
    assert(prev.typ == child.typ)

    val localGlobalsType = prev.typ.globalType
    val localColsType = TArray(prev.typ.colType)
    val localNCols = prev.nCols
    val colValuesBc = prev.colValues.broadcast
    val globalsBc = prev.globals.broadcast

    val colValuesType = TArray(prev.typ.colType)
    val vaType = prev.typ.rvRowType

    val (rvAggs,
    (preInitOp, initOpCompiler),
    (preSeqOp, seqOpCompiler),
    aggResultType,
    (postAgg, postAggCompiler)
      ) = ir.CompileWithAggregators.toIRs[
      Long, Long, Long, Long, Long, Long
      ]("global", localGlobalsType,
      "sa", prev.typ.colType,
      "global", localGlobalsType,
      "colValues", colValuesType,
      "va", vaType,
      newCol)

    val nAggs = rvAggs.length
    val initOpNeedsSA = Mentions(preInitOp, "sa")
    val initOpNeedsGlobals = Mentions(preInitOp, "global")
    val seqOpNeedsSA = Mentions(preSeqOp, "sa")
    val seqOpNeedsGlobals = Mentions(preSeqOp, "global")

    val (_, initOps) = initOpCompiler({
      val colIdx = ir.genUID()

      def rewrite(x: IR): IR = {
        x match {
          case InitOp(i, args, aggSig) =>
            InitOp(
              ir.ApplyBinaryPrimOp(ir.Add(),
                ir.ApplyBinaryPrimOp(ir.Multiply(), ir.Ref(colIdx, TInt32()), ir.I32(nAggs)),
                i),
              args,
              aggSig)
          case _ =>
            ir.Recur(rewrite)(x)
        }
      }

      ir.ArrayFor(
        ir.ArrayRange(ir.I32(0), ir.I32(localNCols), ir.I32(1)),
        colIdx,
        rewrite(preInitOp))
    })

    val (_, seqOps) = seqOpCompiler({
      val colIdx = ir.genUID()

      def rewrite(x: IR): IR = {
        x match {
          case SeqOp(a, i, aggSig, args) =>
            SeqOp(a,
              ir.ApplyBinaryPrimOp(ir.Add(),
                ir.ApplyBinaryPrimOp(ir.Multiply(), ir.Ref(colIdx, TInt32()), ir.I32(nAggs)),
                i),
              aggSig, args)
          case _ =>
            ir.Recur(rewrite)(x)
        }
      }

      var oneSampleSeqOp = ir.Let("g", ir.ArrayRef(
        ir.GetField(ir.Ref("va", vaType), MatrixType.entriesIdentifier),
        ir.Ref(colIdx, TInt32())),
        rewrite(preSeqOp)
      )

      if (seqOpNeedsSA)
        oneSampleSeqOp = ir.Let(
          "sa", ir.ArrayRef(ir.Ref("colValues", colValuesType), ir.Ref(colIdx, TInt32())),
          oneSampleSeqOp)

      ir.ArrayFor(
        ir.ArrayRange(ir.I32(0), ir.I32(localNCols), ir.I32(1)),
        colIdx,
        oneSampleSeqOp)
    })

    val (rTyp, f) = postAggCompiler(postAgg)

    assert(rTyp == typ.colType, s"$rTyp, ${ typ.colType }")

    log.info(s"""MatrixMapCols: initOp ${ initOpNeedsGlobals } ${ initOpNeedsSA };
                |seqOp ${ seqOpNeedsGlobals } ${ seqOpNeedsSA }""".stripMargin)

    val depth = treeAggDepth(hc, prev.nPartitions)

    val colRVAggs = new Array[RegionValueAggregator](nAggs * localNCols)
    var i = 0
    while (i < localNCols) {
      var j = 0
      while (j < nAggs) {
        colRVAggs(i * nAggs + j) = rvAggs(j).newInstance()
        j += 1
      }
      i += 1
    }

    val aggResults = if (nAggs > 0) {
      Region.scoped { region =>
        val rvb: RegionValueBuilder = new RegionValueBuilder()
        rvb.set(region)

        val globals = if (initOpNeedsGlobals) {
          rvb.start(localGlobalsType)
          rvb.addAnnotation(localGlobalsType, globalsBc.value)
          rvb.end()
        } else 0L

        val cols = if (initOpNeedsSA) {
          rvb.start(localColsType)
          rvb.addAnnotation(localColsType, colValuesBc.value)
          rvb.end()
        } else 0L

        initOps()(region, colRVAggs, globals, false, cols, false)
      }

      prev.rvd.treeAggregate[Array[RegionValueAggregator]](colRVAggs)({ (colRVAggs, rv) =>
        val rvb = new RegionValueBuilder()
        val region = rv.region
        val oldRow = rv.offset

        val globals = if (seqOpNeedsGlobals) {
          rvb.set(region)
          rvb.start(localGlobalsType)
          rvb.addAnnotation(localGlobalsType, globalsBc.value)
          rvb.end()
        } else 0L

        val cols = if (seqOpNeedsSA) {
          rvb.start(localColsType)
          rvb.addAnnotation(localColsType, colValuesBc.value)
          rvb.end()
        } else 0L

        seqOps()(region, colRVAggs, globals, false, cols, false, oldRow, false)

        colRVAggs
      }, { (rvAggs1, rvAggs2) =>
        var i = 0
        while (i < rvAggs1.length) {
          rvAggs1(i).combOp(rvAggs2(i))
          i += 1
        }
        rvAggs1
      }, depth = depth)
    } else
      Array.empty[RegionValueAggregator]

    val prevColType = prev.typ.colType
    val rvb = new RegionValueBuilder()

    val mapF = (a: Annotation, i: Int) => {
      Region.scoped { region =>
        rvb.set(region)

        rvb.start(aggResultType)
        rvb.startStruct()
        var j = 0
        while (j < nAggs) {
          aggResults(i * nAggs + j).result(rvb)
          j += 1
        }
        rvb.endStruct()
        val aggResultsOffset = rvb.end()

        rvb.start(localGlobalsType)
        rvb.addAnnotation(localGlobalsType, globalsBc.value)
        val globalRVoffset = rvb.end()

        val colRVb = new RegionValueBuilder(region)
        colRVb.start(prevColType)
        colRVb.addAnnotation(prevColType, a)
        val colRVoffset = colRVb.end()

        val resultOffset = f()(region, aggResultsOffset, false, globalRVoffset, false, colRVoffset, false)

        SafeRow(coerce[TStruct](rTyp), region, resultOffset)
      }
    }

    val newColValues = BroadcastIndexedSeq(colValuesBc.value.zipWithIndex.map { case (a, i) => mapF(a, i) }, TArray(typ.colType), hc.sc)
    prev.copy(typ = typ, colValues = newColValues)
  }
}

case class MatrixMapGlobals(child: MatrixIR, newRow: IR, value: BroadcastRow) extends MatrixIR {
  val children: IndexedSeq[BaseIR] = Array(child, newRow)

  val typ: MatrixType =
    child.typ.copy(globalType = newRow.typ.asInstanceOf[TStruct])

  def copy(newChildren: IndexedSeq[BaseIR]): MatrixMapGlobals = {
    assert(newChildren.length == 2)
    MatrixMapGlobals(newChildren(0).asInstanceOf[MatrixIR], newChildren(1).asInstanceOf[IR], value)
  }

  override def partitionCounts: Option[Array[Long]] = child.partitionCounts

  def execute(hc: HailContext): MatrixValue = {
    val prev = child.execute(hc)

    val (rTyp, f) = ir.Compile[Long, Long, Long](
      "global", child.typ.globalType,
      "value", value.t,
      newRow)
    assert(rTyp == typ.globalType)

    val newGlobals = Region.scoped { globalRegion =>
      val globalOff = prev.globals.toRegion(globalRegion)
      val valueOff = value.toRegion(globalRegion)
      val newOff = f()(globalRegion, globalOff, false, valueOff, false)

      prev.globals.copy(
        value = SafeRow(rTyp.asInstanceOf[TStruct], globalRegion, newOff),
        t = rTyp.asInstanceOf[TStruct])
    }

    prev.copy(typ = typ, globals = newGlobals)
  }
}

case class TableValue(typ: TableType, globals: BroadcastRow, rvd: RVD) {
  require(typ.rowType == rvd.rowType)
  require(typ.key.isDefined || rvd.isInstanceOf[UnpartitionedRVD])

  def rdd: RDD[Row] =
    rvd.toRows

  def filter(p: (RegionValue, RegionValue) => Boolean): TableValue = {
    val globalType = typ.globalType
    val localGlobals = globals.broadcast
    copy(rvd = rvd.mapPartitions(typ.rowType, { (ctx, it) =>
      val globalRV = RegionValue()
      val globalRVb = new RegionValueBuilder()
      it.filter { rv =>
        globalRVb.set(rv.region)
        globalRVb.start(globalType)
        globalRVb.addAnnotation(globalType, localGlobals.value)
        globalRV.set(rv.region, globalRVb.end())
        if (p(rv, globalRV)) {
          true
        } else {
          ctx.region.clear()
          false
        }
      }
    }))
  }

  def write(path: String, overwrite: Boolean, codecSpecJSONStr: String) {
    val hc = HailContext.get

    val codecSpec =
      if (codecSpecJSONStr != null) {
        implicit val formats = RVDSpec.formats
        val codecSpecJSON = JsonMethods.parse(codecSpecJSONStr)
        codecSpecJSON.extract[CodecSpec]
      } else
        CodecSpec.default

    if (overwrite)
      hc.hadoopConf.delete(path, recursive = true)
    else if (hc.hadoopConf.exists(path))
      fatal(s"file already exists: $path")

    hc.hadoopConf.mkDir(path)

    val globalsPath = path + "/globals"
    hc.hadoopConf.mkDir(globalsPath)
    RVD.writeLocalUnpartitioned(hc, globalsPath, typ.globalType, codecSpec, Array(globals.value))

    val partitionCounts = rvd.write(path + "/rows", codecSpec)

    val referencesPath = path + "/references"
    hc.hadoopConf.mkDir(referencesPath)
    ReferenceGenome.exportReferences(hc, referencesPath, typ.rowType)
    ReferenceGenome.exportReferences(hc, referencesPath, typ.globalType)

    val spec = TableSpec(
      FileFormat.version.rep,
      hc.version,
      "references",
      typ,
      Map("globals" -> RVDComponentSpec("globals"),
        "rows" -> RVDComponentSpec("rows"),
        "partition_counts" -> PartitionCountsComponentSpec(partitionCounts)))
    spec.write(hc, path)

    hc.hadoopConf.writeTextFile(path + "/_SUCCESS")(out => ())
  }

  def export(path: String, typesFile: String = null, header: Boolean = true, exportType: Int = ExportType.CONCATENATED) {
    val hc = HailContext.get
    hc.hadoopConf.delete(path, recursive = true)

    val fields = typ.rowType.fields

    Option(typesFile).foreach { file =>
      exportTypes(file, hc.hadoopConf, fields.map(f => (f.name, f.typ)).toArray)
    }

    val localSignature = typ.rowType
    val localTypes = fields.map(_.typ)

    rvd.mapPartitions { it =>
      val sb = new StringBuilder()

      it.map { rv =>
        val ur = new UnsafeRow(localSignature, rv)
        sb.clear()
        localTypes.indices.foreachBetween { i =>
          sb.append(TableAnnotationImpex.exportAnnotation(ur.get(i), localTypes(i)))
        }(sb += '\t')

        sb.result()
      }
    }.writeTable(path, hc.tmpDir, Some(fields.map(_.name).mkString("\t")).filter(_ => header), exportType = exportType)
  }
}

abstract sealed class TableIR extends BaseIR {
  def typ: TableType

  def partitionCounts: Option[Array[Long]] = None

  def execute(hc: HailContext): TableValue
}

case class TableLiteral(value: TableValue) extends TableIR {
  val typ: TableType = value.typ

  val children: IndexedSeq[BaseIR] = Array.empty[BaseIR]

  def copy(newChildren: IndexedSeq[BaseIR]): TableLiteral = {
    assert(newChildren.isEmpty)
    TableLiteral(value)
  }

  def execute(hc: HailContext): TableValue = value
}

case class TableRead(path: String, spec: TableSpec, typ: TableType, dropRows: Boolean) extends TableIR {
  assert(PruneDeadFields.isSupertype(typ, spec.table_type))

  override def partitionCounts: Option[Array[Long]] = Some(spec.partitionCounts)

  val children: IndexedSeq[BaseIR] = Array.empty[BaseIR]

  def copy(newChildren: IndexedSeq[BaseIR]): TableRead = {
    assert(newChildren.isEmpty)
    TableRead(path, spec, typ, dropRows)
  }

  def execute(hc: HailContext): TableValue = {
    val globals = spec.globalsComponent.readLocal(hc, path, typ.globalType)(0)
    TableValue(typ,
      BroadcastRow(globals, typ.globalType, hc.sc),
      if (dropRows)
        UnpartitionedRVD.empty(hc.sc, typ.rowType)
      else
        spec.rowsComponent.read(hc, path, typ.rowType))
  }
}

case class TableParallelize(typ: TableType, rows: IndexedSeq[Row], nPartitions: Option[Int] = None) extends TableIR {
  assert(typ.globalType.size == 0)
  val children: IndexedSeq[BaseIR] = Array.empty[BaseIR]

  def copy(newChildren: IndexedSeq[BaseIR]): TableParallelize = {
    assert(newChildren.isEmpty)
    TableParallelize(typ, rows, nPartitions)
  }

  def execute(hc: HailContext): TableValue = {
    val rowTyp = typ.rowType
    val rvd = ContextRDD.parallelize[RVDContext](hc.sc, rows, nPartitions)
      .cmapPartitions((ctx, it) => it.toRegionValueIterator(ctx.region, rowTyp))
    TableValue(typ, BroadcastRow(Row(), typ.globalType, hc.sc), new UnpartitionedRVD(rowTyp, rvd))
  }
}

case class TableImport(paths: Array[String], typ: TableType, readerOpts: TableReaderOptions) extends TableIR {
  assert(typ.globalType.size == 0)
  assert(typ.rowType.size == readerOpts.useColIndices.length)

  val children: IndexedSeq[BaseIR] = Array.empty[BaseIR]

  def copy(newChildren: IndexedSeq[BaseIR]): TableImport = {
    assert(newChildren.isEmpty)
    TableImport(paths, typ, readerOpts)
  }

  def execute(hc: HailContext): TableValue = {
    val rowTyp = typ.rowType
    val nFieldOrig = readerOpts.originalType.size
    val rowFields = rowTyp.fields

    val useColIndices = readerOpts.useColIndices


    val rvd = ContextRDD.textFilesLines[RVDContext](hc.sc, paths, readerOpts.nPartitions)
      .filter { line =>
        !readerOpts.isComment(line.value) &&
          (readerOpts.noHeader || readerOpts.header != line.value) &&
          !(readerOpts.skipBlankLines && line.value.isEmpty)
      }.cmapPartitions { (ctx, it) =>
      val region = ctx.region
      val rvb = ctx.rvb
      val rv = RegionValue(region)

      val ab = new ArrayBuilder[String]
      val sb = new StringBuilder
      it.map {
        _.map { line =>
          val sp = TextTableReader.splitLine(line, readerOpts.separator, readerOpts.quote, ab, sb)
          if (sp.length != nFieldOrig)
            fatal(s"expected $nFieldOrig fields, but found ${ sp.length } fields")

          rvb.set(region)
          rvb.start(rowTyp)
          rvb.startStruct()

          var i = 0
          while (i < useColIndices.length) {
            val f = rowFields(i)
            val name = f.name
            val typ = f.typ
            val field = sp(useColIndices(i))
            try {
              if (field == readerOpts.missing)
                rvb.setMissing()
              else
                rvb.addAnnotation(typ, TableAnnotationImpex.importAnnotation(field, typ))
            } catch {
              case e: Exception =>
                fatal(s"""${ e.getClass.getName }: could not convert "$field" to $typ in column "$name" """)
            }
            i += 1
          }

          rvb.endStruct()
          rv.setOffset(rvb.end())
          rv
        }.value
      }
    }

    TableValue(typ, BroadcastRow(Row.empty, typ.globalType, hc.sc), new UnpartitionedRVD(rowTyp, rvd))
  }
}

case class TableKeyBy(child: TableIR, keys: Array[String], nPartitionKeys: Option[Int], sort: Boolean = true) extends TableIR {
  private val fields = child.typ.rowType.fieldNames.toSet
  assert(keys.forall(fields.contains), s"${ keys.filter(k => !fields.contains(k)).mkString(", ") }")
  assert(nPartitionKeys.forall(_ <= keys.length))

  val children: IndexedSeq[BaseIR] = Array(child)

  val typ: TableType = child.typ.copy(key = Some(keys))

  def copy(newChildren: IndexedSeq[BaseIR]): TableKeyBy = {
    assert(newChildren.length == 1)
    TableKeyBy(newChildren(0).asInstanceOf[TableIR], keys, nPartitionKeys, sort)
  }

  def execute(hc: HailContext): TableValue = {
    val tv = child.execute(hc)
    val rvd = if (sort) {
      def resort: OrderedRVD = {
        val orvdType = new OrderedRVDType(nPartitionKeys.map(keys.take).getOrElse(keys), keys, typ.rowType)
        OrderedRVD.coerce(orvdType, tv.rvd, None, None)
      }

      tv.rvd match {
        case ordered: OrderedRVD =>
          if (ordered.typ.key.startsWith(keys) &&
            nPartitionKeys.getOrElse(keys.length) == ordered.typ.partitionKey.length)
            ordered.copy(typ = ordered.typ.copy(key = keys))
          else resort
        case _: UnpartitionedRVD =>
          resort
      }
    } else {
      tv.rvd match {
        case ordered: OrderedRVD => ordered.toUnpartitionedRVD
        case unordered: UnpartitionedRVD => unordered
      }
    }
    tv.copy(typ = typ, rvd = rvd)
  }
}

case class TableUnkey(child: TableIR) extends TableIR {
  val children: IndexedSeq[BaseIR] = Array(child)

  val typ: TableType = child.typ.copy(key = None)

  def copy(newChildren: IndexedSeq[BaseIR]): TableUnkey = {
    assert(newChildren.length == 1)
    TableUnkey(newChildren(0).asInstanceOf[TableIR])
  }

  def execute(hc: HailContext): TableValue = {
    val tv = child.execute(hc)
    val rvd = tv.rvd match {
      case ordered: OrderedRVD => ordered.toUnpartitionedRVD
      case unordered: UnpartitionedRVD => unordered
    }
    tv.copy(typ = typ, rvd = rvd)
  }
}

case class TableRange(n: Int, nPartitions: Int) extends TableIR {
  private val nPartitionsAdj = math.min(n, nPartitions)
  val children: IndexedSeq[BaseIR] = Array.empty[BaseIR]

  def copy(newChildren: IndexedSeq[BaseIR]): TableRange = {
    assert(newChildren.isEmpty)
    TableRange(n, nPartitions)
  }

  private val partCounts = partition(n, nPartitionsAdj)

  override val partitionCounts = Some(partCounts.map(_.toLong))

  val typ: TableType = TableType(
    TStruct("idx" -> TInt32()),
    Some(Array("idx")),
    TStruct.empty())

  def execute(hc: HailContext): TableValue = {
    val localRowType = typ.rowType
    val localPartCounts = partCounts
    val partStarts = partCounts.scanLeft(0)(_ + _)

    TableValue(typ,
      BroadcastRow(Row(), typ.globalType, hc.sc),
      new OrderedRVD(
        new OrderedRVDType(Array("idx"), Array("idx"), typ.rowType),
        new OrderedRVDPartitioner(Array("idx"), typ.rowType,
          Array.tabulate(nPartitionsAdj) { i =>
            val start = partStarts(i)
            val end = partStarts(i + 1)
            Interval(Row(start), Row(end), includesStart = true, includesEnd = false)
          }),
        ContextRDD.parallelize(hc.sc, Range(0, nPartitionsAdj), nPartitionsAdj)
          .cmapPartitionsWithIndex { case (i, ctx, _) =>
            val region = ctx.region
            val rvb = ctx.rvb
            val rv = RegionValue(region)

            val start = partStarts(i)
            Iterator.range(start, start + localPartCounts(i))
              .map { j =>
                rvb.start(localRowType)
                rvb.startStruct()
                rvb.addInt(j)
                rvb.endStruct()
                rv.setOffset(rvb.end())
                rv
              }
          }))
  }
}

case class TableFilter(child: TableIR, pred: IR) extends TableIR {
  val children: IndexedSeq[BaseIR] = Array(child, pred)

  val typ: TableType = child.typ

  def copy(newChildren: IndexedSeq[BaseIR]): TableFilter = {
    assert(newChildren.length == 2)
    TableFilter(newChildren(0).asInstanceOf[TableIR], newChildren(1).asInstanceOf[IR])
  }

  def execute(hc: HailContext): TableValue = {
    val ktv = child.execute(hc)
    val (rTyp, f) = ir.Compile[Long, Long, Boolean](
      "row", child.typ.rowType,
      "global", child.typ.globalType,
      pred)
    assert(rTyp == TBoolean())
    ktv.filter((rv, globalRV) => f()(rv.region, rv.offset, false, globalRV.offset, false))
  }
}

case class TableJoin(left: TableIR, right: TableIR, joinType: String) extends TableIR {
  require(left.typ.keyType.zip(right.typ.keyType).exists { case (leftKey, rightKey) =>
    leftKey isIsomorphicTo rightKey
  })

  val children: IndexedSeq[BaseIR] = Array(left, right)

  private val joinedFields = left.typ.keyType.get.fields ++
    left.typ.valueType.fields ++
    right.typ.valueType.fields
  private val preNames = joinedFields.map(_.name).toArray
  private val (finalColumnNames, remapped) = mangle(preNames)

  val rightFieldMapping: Map[String, String] = {
    val remapMap = remapped.toMap
    (right.typ.key.get.iterator.zip(left.typ.key.get.iterator) ++
      right.typ.valueType.fieldNames.iterator.map(f => f -> remapMap.getOrElse(f, f))).toMap
  }

  val newRowType = TStruct(joinedFields.zipWithIndex.map {
    case (fd, i) => (finalColumnNames(i), fd.typ)
  }: _*)

  val typ: TableType = left.typ.copy(rowType = newRowType)

  def copy(newChildren: IndexedSeq[BaseIR]): TableJoin = {
    assert(newChildren.length == 2)
    TableJoin(
      newChildren(0).asInstanceOf[TableIR],
      newChildren(1).asInstanceOf[TableIR],
      joinType)
  }

  def execute(hc: HailContext): TableValue = {
    val leftTV = left.execute(hc)
    val rightTV = right.execute(hc)
    val leftRowType = left.typ.rowType
    val rightRowType = right.typ.rowType
    val leftKeyFieldIdx = left.typ.keyFieldIdx.get
    val rightKeyFieldIdx = right.typ.keyFieldIdx.get
    val leftValueFieldIdx = left.typ.valueFieldIdx
    val rightValueFieldIdx = right.typ.valueFieldIdx
    val localNewRowType = newRowType
    val rvMerger = { (ctx: RVDContext, it: Iterator[JoinedRegionValue]) =>
      val rvb = new RegionValueBuilder()
      val rv = RegionValue()
      it.map { joined =>
        val lrv = joined._1
        val rrv = joined._2

        if (lrv != null)
          rvb.set(lrv.region)
        else {
          assert(rrv != null)
          rvb.set(rrv.region)
        }

        rvb.start(localNewRowType)
        rvb.startStruct()

        if (lrv != null)
          rvb.addFields(leftRowType, lrv, leftKeyFieldIdx)
        else {
          assert(rrv != null)
          rvb.addFields(rightRowType, rrv, rightKeyFieldIdx)
        }

        if (lrv != null)
          rvb.addFields(leftRowType, lrv, leftValueFieldIdx)
        else
          rvb.skipFields(leftValueFieldIdx.length)

        if (rrv != null)
          rvb.addFields(rightRowType, rrv, rightValueFieldIdx)
        else
          rvb.skipFields(rightValueFieldIdx.length)

        rvb.endStruct()
        rv.set(rvb.region, rvb.end())
        rv
      }
    }
    val leftORVD = leftTV.rvd match {
      case ordered: OrderedRVD => ordered
      case unordered =>
        OrderedRVD.coerce(
          new OrderedRVDType(left.typ.key.get.toArray, left.typ.key.get.toArray, leftRowType),
          unordered)
    }
    val rightORVD = rightTV.rvd match {
      case ordered: OrderedRVD => ordered
      case unordered =>
        val ordType =
          new OrderedRVDType(right.typ.key.get.toArray, right.typ.key.get.toArray, rightRowType)
        if (joinType == "left" || joinType == "inner")
          unordered.constrainToOrderedPartitioner(ordType, leftORVD.partitioner)
        else
          OrderedRVD.coerce(ordType, unordered, leftORVD.partitioner)
    }
    val joinedRVD = leftORVD.orderedJoin(
      rightORVD,
      joinType,
      rvMerger,
      new OrderedRVDType(leftORVD.typ.partitionKey, leftORVD.typ.key, newRowType))

    TableValue(typ, leftTV.globals, joinedRVD)
  }
}

// Must not modify key ordering.
// newKey is key of resulting Table, if newKey=None then result is unkeyed.
// preservedKeyFields is length of initial sequence of key fields whose values are unchanged.
// Thus if number of partition keys of underlying OrderedRVD is <= preservedKeyFields,
// partition bounds will remain valid.
case class TableMapRows(child: TableIR, newRow: IR, newKey: Option[IndexedSeq[String]], preservedKeyFields: Option[Int]) extends TableIR {
  require(!(newKey.isDefined ^ preservedKeyFields.isDefined))
  val children: IndexedSeq[BaseIR] = Array(child, newRow)

  val typ: TableType = {
    val newRowType = newRow.typ.asInstanceOf[TStruct]
    child.typ.copy(rowType = newRowType, key = newKey)
  }

  def copy(newChildren: IndexedSeq[BaseIR]): TableMapRows = {
    assert(newChildren.length == 2)
    TableMapRows(newChildren(0).asInstanceOf[TableIR], newChildren(1).asInstanceOf[IR], newKey, preservedKeyFields)
  }

  override def partitionCounts: Option[Array[Long]] = child.partitionCounts

  def execute(hc: HailContext): TableValue = {
    val tv = child.execute(hc)
    val (rTyp, f) = ir.Compile[Long, Long, Long](
      "row", child.typ.rowType,
      "global", child.typ.globalType,
      newRow)
    assert(rTyp == typ.rowType)
    val globalsBc = tv.globals.broadcast
    val gType = typ.globalType
    val itF = { (ctx: RVDContext, it: Iterator[RegionValue]) =>
      val rvb = new RegionValueBuilder(ctx.freshRegion)
      rvb.start(gType)
      rvb.addAnnotation(gType, globalsBc.value)
      val globals = rvb.end()
      val rv2 = RegionValue()
      val newRow = f()
      it.map { rv =>
        rv2.set(rv.region, newRow(rv.region, rv.offset, false, globals, false))
        rv2
      }
    }
    val newRVD = tv.rvd match {
      case ordered: OrderedRVD =>
        typ.key match {
          case Some(key) =>
            val pkLength = ordered.typ.partitionKey.length
            if (pkLength <= preservedKeyFields.get) {
              val newType = ordered.typ.copy(
                partitionKey = key.take(pkLength).toArray,
                key = key.toArray,
                rowType = typ.rowType)
              ordered.mapPartitionsPreservesPartitioning(newType, itF)
            } else {
              val newType = ordered.typ.copy(
                partitionKey = key.toArray,
                key = key.toArray,
                rowType = typ.rowType)
              OrderedRVD.coerce(newType, ordered.mapPartitions(typ.rowType, itF))
            }
          case None =>
            ordered.mapPartitions(typ.rowType, itF)
        }
      case unordered: UnpartitionedRVD =>
        unordered.mapPartitions(typ.rowType, itF)
    }

    TableValue(typ, tv.globals, newRVD)
  }
}

case class TableMapGlobals(child: TableIR, newRow: IR, value: BroadcastRow) extends TableIR {
  val children: IndexedSeq[BaseIR] = Array(child, newRow)

  val typ: TableType =
    child.typ.copy(globalType = newRow.typ.asInstanceOf[TStruct])

  def copy(newChildren: IndexedSeq[BaseIR]): TableMapGlobals = {
    assert(newChildren.length == 2)
    TableMapGlobals(newChildren(0).asInstanceOf[TableIR], newChildren(1).asInstanceOf[IR], value)
  }

  override def partitionCounts: Option[Array[Long]] = child.partitionCounts

  def execute(hc: HailContext): TableValue = {
    val tv = child.execute(hc)
    val gType = typ.globalType

    val (rTyp, f) = ir.Compile[Long, Long, Long](
      "global", child.typ.globalType,
      "value", value.t,
      newRow)
    assert(rTyp == gType)

    val newGlobals = Region.scoped { globalRegion =>
      val globalOff = tv.globals.toRegion(globalRegion)
      val valueOff = value.toRegion(globalRegion)
      val newOff = f()(globalRegion, globalOff, false, valueOff, false)

      tv.globals.copy(
        value = SafeRow(rTyp.asInstanceOf[TStruct], globalRegion, newOff),
        t = rTyp.asInstanceOf[TStruct])
    }

    TableValue(typ, newGlobals, tv.rvd)
  }
}


case class TableExplode(child: TableIR, fieldName: String) extends TableIR {
  def children: IndexedSeq[BaseIR] = Array(child)

  private val fieldIdx = child.typ.rowType.fieldIdx(fieldName)
  private val fieldType = child.typ.rowType.types(fieldIdx)
  private val rowType = child.typ.rowType.updateKey(fieldName, fieldIdx, fieldType.asInstanceOf[TContainer].elementType)

  val typ: TableType = child.typ.copy(rowType = rowType)

  def copy(newChildren: IndexedSeq[BaseIR]): TableExplode = {
    assert(newChildren.length == 1)
    TableExplode(newChildren(0).asInstanceOf[TableIR], fieldName)
  }

  def execute(hc: HailContext): TableValue = {
    val prev = child.execute(hc)

    val childRowType = child.typ.rowType

    val field = fieldType match {
      case TArray(_, _) =>
        GetField(Ref("row", childRowType), fieldName)
      case TSet(_, _) =>
        ToArray(GetField(Ref("row", childRowType), fieldName))
      case _ =>
        fatal(s"expected field to explode to be an array or set, found ${ fieldType }")
    }

    val (_, isMissingF) = ir.Compile[Long, Boolean]("row", childRowType,
      ir.IsNA(field))

    val (_, lengthF) = ir.Compile[Long, Int]("row", childRowType,
      ir.ArrayLen(field))

    val (resultType, explodeF) = ir.Compile[Long, Int, Long]("row", childRowType,
      "i", TInt32(),
      ir.InsertFields(Ref("row", childRowType),
        Array(fieldName -> ir.ArrayRef(
          field,
          ir.Ref("i", TInt32())))))
    assert(resultType == typ.rowType)

    val itF: (RVDContext, Iterator[RegionValue]) => Iterator[RegionValue] = { (ctx, it) =>
      val rv2 = RegionValue()
      it.flatMap { rv =>
        val isMissing = isMissingF()(rv.region, rv.offset, false)
        if (isMissing)
          Iterator.empty
        else {
          val n = lengthF()(rv.region, rv.offset, false)
          Iterator.range(0, n)
            .map { i =>
              val off = explodeF()(ctx.region, rv.offset, false, i, false)
              rv2.set(ctx.region, off)
              rv2
            }
        }
      }
    }

    val newRVD: RVD = prev.rvd.boundary match {
      case rvd: UnpartitionedRVD =>
        rvd.mapPartitions(typ.rowType, itF)
      case orvd: OrderedRVD =>
        if (orvd.typ.key.contains(fieldName))
          orvd.mapPartitions(typ.rowType, itF)
        else
          orvd.mapPartitionsPreservesPartitioning(
            orvd.typ.copy(rowType = rowType),
            itF)
    }

    TableValue(typ, prev.globals, newRVD)
  }
}

case class TableUnion(children: IndexedSeq[TableIR]) extends TableIR {
  assert(children.nonEmpty)
  assert(children.tail.forall(_.typ.rowType == children(0).typ.rowType))
  assert(children.tail.forall(_.typ.key == children(0).typ.key))

  def copy(newChildren: IndexedSeq[BaseIR]): TableUnion = {
    TableUnion(newChildren.map(_.asInstanceOf[TableIR]))
  }

  val typ: TableType = children(0).typ

  def execute(hc: HailContext): TableValue = {
    val tvs = children.map(_.execute(hc))
    tvs(0).copy(
      rvd = RVD.union(tvs.map(_.rvd)))
  }
}

case class MatrixRowsTable(child: MatrixIR) extends TableIR {
  val children: IndexedSeq[BaseIR] = Array(child)

  def copy(newChildren: IndexedSeq[BaseIR]): MatrixRowsTable = {
    assert(newChildren.length == 1)
    MatrixRowsTable(newChildren(0).asInstanceOf[MatrixIR])
  }

  val typ: TableType = child.typ.rowsTableType

  def execute(hc: HailContext): TableValue = {
    val mv = child.execute(hc)
    val rtv = mv.rowsTableValue
    assert(rtv.typ == typ)
    rtv
  }
}

case class MatrixColsTable(child: MatrixIR) extends TableIR {
  val children: IndexedSeq[BaseIR] = Array(child)

  def copy(newChildren: IndexedSeq[BaseIR]): MatrixColsTable = {
    assert(newChildren.length == 1)
    MatrixColsTable(newChildren(0).asInstanceOf[MatrixIR])
  }

  val typ: TableType = child.typ.colsTableType

  def execute(hc: HailContext): TableValue = {
    val mv = child.execute(hc)
    val ctv = mv.colsTableValue
    assert(ctv.typ == typ)
    ctv
  }
}

case class MatrixEntriesTable(child: MatrixIR) extends TableIR {
  val children: IndexedSeq[BaseIR] = Array(child)

  def copy(newChildren: IndexedSeq[BaseIR]): MatrixEntriesTable = {
    assert(newChildren.length == 1)
    MatrixEntriesTable(newChildren(0).asInstanceOf[MatrixIR])
  }

  val typ: TableType = child.typ.entriesTableType

  def execute(hc: HailContext): TableValue = {
    val mv = child.execute(hc)
    val etv = mv.entriesTableValue
    assert(etv.typ == typ)
    etv
  }
}

case class MatrixFilterEntries(child: MatrixIR, pred: IR) extends MatrixIR {
  val children: IndexedSeq[BaseIR] = Array(child, pred)

  def copy(newChildren: IndexedSeq[BaseIR]): MatrixFilterEntries = {
    assert(newChildren.length == 2)
    MatrixFilterEntries(newChildren(0).asInstanceOf[MatrixIR], newChildren(1).asInstanceOf[IR])
  }

  val typ: MatrixType = child.typ

  def execute(hc: HailContext): MatrixValue = {
    val mv = child.execute(hc)

    val localGlobalType = child.typ.globalType
    val globalsBc = mv.globals.broadcast
    val localColValuesType = TArray(child.typ.colType)
    val colValuesBc = mv.colValues.broadcast

    val colValuesType = TArray(child.typ.colType)

    val x = ir.InsertFields(ir.Ref("va", child.typ.rvRowType),
      FastSeq(MatrixType.entriesIdentifier ->
        ir.ArrayMap(ir.ArrayRange(ir.I32(0), ir.I32(mv.nCols), ir.I32(1)),
          "i",
          ir.Let("g",
            ir.ArrayRef(
              ir.GetField(ir.Ref("va", child.typ.rvRowType), MatrixType.entriesIdentifier),
              ir.Ref("i", TInt32())),
            ir.If(
              ir.Let("sa", ir.ArrayRef(ir.Ref("colValues", colValuesType), ir.Ref("i", TInt32())),
                pred),
              ir.Ref("g", child.typ.entryType),
              ir.NA(child.typ.entryType))))))

    val (t, f) = ir.Compile[Long, Long, Long, Long](
      "global", child.typ.globalType,
      "colValues", colValuesType,
      "va", child.typ.rvRowType,
      x)
    assert(t == typ.rvRowType)

    val mapPartitionF = { (ctx: RVDContext, it: Iterator[RegionValue]) =>
      val rvb = new RegionValueBuilder(ctx.freshRegion)
      rvb.start(localGlobalType)
      rvb.addAnnotation(localGlobalType, globalsBc.value)
      val globals = rvb.end()

      rvb.start(localColValuesType)
      rvb.addAnnotation(localColValuesType, colValuesBc.value)
      val cols = rvb.end()
      val rowF = f()

      val newRV = RegionValue()
      it.map { rv =>
        val off = rowF(rv.region, globals, false, cols, false, rv.offset, false)
        newRV.set(rv.region, off)
        newRV
      }
    }

    val newRVD = mv.rvd.mapPartitionsPreservesPartitioning(typ.orvdType, mapPartitionF)
    mv.copy(rvd = newRVD)
  }
}

// follows key_by non-empty key
case class TableAggregateByKey(child: TableIR, expr: IR) extends TableIR {
  require(child.typ.keyOrEmpty.nonEmpty)

  def children: IndexedSeq[BaseIR] = Array(child, expr)

  def copy(newChildren: IndexedSeq[BaseIR]): TableAggregateByKey = {
    assert(newChildren.length == 2)
    val IndexedSeq(newChild: TableIR, newExpr: IR) = newChildren
    TableAggregateByKey(newChild, newExpr)
  }

  val typ: TableType = child.typ.copy(rowType = child.typ.keyType.get.merge(coerce[TStruct](expr.typ))._1)

  def execute(hc: HailContext): TableValue = {
    val prev = child.execute(hc)
    val prevRVD = prev.rvd.asInstanceOf[OrderedRVD]

    val (rvAggs, makeInit, makeSeq, aggResultType, makeAnnotate, rTyp) = ir.CompileWithAggregators[Long, Long, Long, Long](
      "global", child.typ.globalType,
      "global", child.typ.globalType,
      "row", child.typ.rowType,
      expr,
      (nAggs, initializeIR) => initializeIR,
      (nAggs, sequenceIR) => sequenceIR)

    val nAggs = rvAggs.length

    assert(coerce[TStruct](rTyp) == typ.valueType, s"$rTyp, ${ typ.valueType }")

    val rowType = prev.typ.rowType
    val keyType = prev.typ.keyType.get
    val keyIndices = prev.typ.keyFieldIdx.get
    val keyOrd = prevRVD.typ.kRowOrd
    val globalsType = prev.typ.globalType
    val globalsBc = prev.globals.broadcast

    val newValueType = typ.valueType
    val newRowType = typ.rowType
    val newOrvdType = prevRVD.typ.copy(rowType = newRowType)

    val newRVD = prevRVD.boundary.mapPartitionsPreservesPartitioning(newOrvdType, { (ctx, it) =>
      val rvb = new RegionValueBuilder()
      val partRegion = ctx.freshContext.region

      rvb.set(partRegion)
      rvb.start(globalsType)
      rvb.addAnnotation(globalsType, globalsBc.value)
      val partGlobalsOff = rvb.end()

      val initialize = makeInit()
      val sequence = makeSeq()
      val annotate = makeAnnotate()

      new Iterator[RegionValue] {
        var isEnd = false
        var current: RegionValue = _
        val rowKey: WritableRegionValue = WritableRegionValue(keyType, ctx.freshRegion)
        val consumerRegion: Region = ctx.region
        val newRV = RegionValue(consumerRegion)

        def hasNext: Boolean = {
          if (isEnd || (current == null && !it.hasNext)) {
            isEnd = true
            return false
          }
          if (current == null)
            current = it.next()
          true
        }

        def next(): RegionValue = {
          if (!hasNext)
            throw new java.util.NoSuchElementException()

          rowKey.setSelect(rowType, keyIndices, current)

          rvAggs.foreach(_.clear())

          val region = current.region
          rvb.set(region)
          rvb.start(globalsType)
          rvb.addRegionValue(globalsType, partRegion, partGlobalsOff)
          val globals = rvb.end()

          initialize(region, rvAggs, globals, false)

          do {
            val region = current.region
            rvb.set(region)
            rvb.start(globalsType)
            rvb.addRegionValue(globalsType, partRegion, partGlobalsOff)
            val globals = rvb.end()

            sequence(region, rvAggs,
              globals, false,
              current.offset, false)
            current = null
          } while (hasNext && keyOrd.equiv(rowKey.value, current))

          rvb.set(consumerRegion)

          rvb.start(globalsType)
          rvb.addRegionValue(globalsType, partRegion, partGlobalsOff)
          val globalOff = rvb.end()

          rvb.start(aggResultType)
          rvb.startStruct()
          var j = 0
          while (j < nAggs) {
            rvAggs(j).result(rvb)
            j += 1
          }
          rvb.endStruct()
          val aggResultOff = rvb.end()

          rvb.start(newRowType)
          rvb.startStruct()
          var i = 0
          while (i < keyType.size) {
            rvb.addField(keyType, rowKey.value, i)
            i += 1
          }

          val newValueOff = annotate(consumerRegion,
            aggResultOff, false,
            globalOff, false)

          rvb.addAllFields(newValueType, consumerRegion, newValueOff)

          rvb.endStruct()
          newRV.setOffset(rvb.end())
          newRV
        }
      }
    })

    prev.copy(rvd = newRVD, typ = typ)
  }
}
