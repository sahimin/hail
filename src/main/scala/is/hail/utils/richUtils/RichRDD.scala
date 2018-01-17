package is.hail.utils.richUtils

import java.io.OutputStream

import is.hail.sparkextras.ReorderedPartitionsRDD
import is.hail.utils._
import org.apache.commons.lang3.StringUtils
import org.apache.hadoop
import org.apache.hadoop.io.compress.CompressionCodecFactory
import org.apache.spark.{NarrowDependency, Partition, TaskContext}
import org.apache.spark.rdd.RDD

import scala.reflect.ClassTag
import scala.collection.mutable

case class SubsetRDDPartition(index: Int, parentPartition: Partition) extends Partition


class RichRDD[T](val r: RDD[T]) extends AnyVal {
  def countByValueRDD()(implicit tct: ClassTag[T]): RDD[(T, Int)] = r.map((_, 1)).reduceByKey(_ + _)

  def reorderPartitions(oldIndices: Array[Int])(implicit tct: ClassTag[T]): RDD[T] =
    new ReorderedPartitionsRDD[T](r, oldIndices)

  def forall(p: T => Boolean)(implicit tct: ClassTag[T]): Boolean = !exists(x => !p(x))

  def exists(p: T => Boolean)(implicit tct: ClassTag[T]): Boolean = r.mapPartitions { it =>
    Iterator(it.exists(p))
  }.fold(false)(_ || _)

  def writeTable(filename: String, tmpDir: String, header: Option[String] = None, parallelWrite: Boolean = false) {
    val exportType =
      if (parallelWrite)
        ExportType.PARALLEL_HEADER_IN_SHARD
      else
        ExportType.CONCATENATED

    writeTable(filename, tmpDir, header, exportType)
  }

  def writeTable(filename: String, tmpDir: String, header: Option[String], exportType: Int) {
    val hConf = r.sparkContext.hadoopConfiguration

    val codecFactory = new CompressionCodecFactory(hConf)
    val codec = Option(codecFactory.getCodec(new hadoop.fs.Path(filename)))

    hConf.delete(filename, recursive = true) // overwriting by default

    val parallelOutputPath =
      if (exportType == ExportType.CONCATENATED)
        hConf.getTemporaryFile(tmpDir)
      else
        filename

    val rWithHeader = header.map { h =>
      if (r.getNumPartitions == 0 && exportType != ExportType.PARALLEL_SEPARATE_HEADER)
        r.sparkContext.parallelize(List(h), numSlices = 1)
      else {
        exportType match {
          case ExportType.CONCATENATED =>
            r.mapPartitionsWithIndex { case (i, it) =>
              if (i == 0)
                Iterator(h) ++ it
              else
                it
            }
          case ExportType.PARALLEL_SEPARATE_HEADER =>
            r
          case ExportType.PARALLEL_HEADER_IN_SHARD =>
            r.mapPartitions { it => Iterator(h) ++ it }
          case _ => fatal(s"Unknown export type: $exportType")
        }
      }
    }.getOrElse(r)

    codec match {
      case Some(x) => rWithHeader.saveAsTextFile(parallelOutputPath, x.getClass)
      case None => rWithHeader.saveAsTextFile(parallelOutputPath)
    }

    if (exportType == ExportType.PARALLEL_SEPARATE_HEADER) {
      val headerExt = hConf.getCodec(filename)
      hConf.writeTextFile(parallelOutputPath + "/header" + headerExt) { out =>
        header.foreach { h =>
          out.write(h)
          out.write('\n')
        }
      }
    }

    if (!hConf.exists(parallelOutputPath + "/_SUCCESS"))
      fatal("write failed: no success indicator found")

    if (exportType == ExportType.CONCATENATED) {
      hConf.copyMerge(parallelOutputPath, filename, hasHeader = false)
    }
  }

  def collectOrdered()(implicit tct: ClassTag[T]): Array[T] =
    r.zipWithIndex().collect().sortBy(_._2).map(_._1)

  def find(f: T => Boolean): Option[T] = r.filter(f).take(1) match {
    case Array(elem) => Some(elem)
    case _ => None
  }

  def collectAsSet(): collection.Set[T] = {
    r.aggregate(mutable.Set.empty[T])(
      { case (s, elem) => s += elem },
      { case (s1, s2) => s1 ++ s2 }
    )
  }

  def subsetPartitions(keep: Array[Int])(implicit ct: ClassTag[T]): RDD[T] = {
    require(keep.length <= r.partitions.length, "tried to subset to more partitions than exist")
    require(keep.isSorted && keep.forall{i => i >= 0 && i < r.partitions.length},
      "values not sorted or not in range [0, number of partitions)")
    val parentPartitions = r.partitions

    new RDD[T](r.sparkContext, Seq(new NarrowDependency[T](r) {
      def getParents(partitionId: Int): Seq[Int] = Seq(keep(partitionId))
    })) {
      def getPartitions: Array[Partition] = keep.indices.map { i =>
        SubsetRDDPartition(i, parentPartitions(keep(i)))
      }.toArray

      def compute(split: Partition, context: TaskContext): Iterator[T] =
        r.compute(split.asInstanceOf[SubsetRDDPartition].parentPartition, context)
    }
  }

  def writePartitions(path: String, write: (Int, Iterator[T], OutputStream) => Long): Long = {
    val sc = r.sparkContext
    val hadoopConf = sc.hadoopConfiguration
    
    hadoopConf.mkDir(path + "/parts")
    
    val sHadoopConf = new SerializableHadoopConfiguration(hadoopConf)
    
    val nPartitions = r.getNumPartitions
    val d = digitsNeeded(nPartitions)

    val itemCount = r.mapPartitionsWithIndex { case (i, it) =>
      val is = i.toString
      assert(is.length <= d)
      val pis = StringUtils.leftPad(is, d, "0")

      val filename = path + "/parts/part-" + pis
      
      val os = sHadoopConf.value.unsafeWriter(filename)

      Iterator.single(write(i, it, os))
    }
      .fold(0L)(_ + _)

    info(s"wrote $itemCount items in $nPartitions partitions")
    
    itemCount
  }
}
