package com.thoughtworks.dod

import spark._
import SparkContext._
import scala.xml.XML
import scala.io.Source


package object parsers {

    object out {

        val outputSeparator = "="
        val columnSeparator = " | "
        val labelDataSeparator = "-"

        def println(analyzerDesc: String, r: Result) = {
            Predef.println(analyzerDesc)
            val sizes = r.labels.map(_.size).toArray
            r.rows.foreach { row =>
                row.zipWithIndex.foreach { case (column, i) => sizes(i) = math.max(sizes(i), column.toString.size) }
            }
            val sepSize = (sizes.sum + ((sizes.size - 1) * columnSeparator.size))
            Predef.println(outputSeparator * sepSize)
            Predef.println(r.labels.zipWithIndex.map { case (label, i) => label.padTo(sizes(i), ' ') }.mkString(columnSeparator))
            Predef.println(labelDataSeparator * sepSize)
            r.rows.foreach { row =>
                Predef.println(row.zipWithIndex.map { case (column, i) => column.toString.padTo(sizes(i), ' ') }.mkString(columnSeparator))
            }
            Predef.println(outputSeparator * sepSize)
            Predef.println()
        }
    }

    trait Parser[T] {
        def parse(inputFile: String): Seq[T]
    }

    implicit object gitLogParser extends Parser[Commit] {
        val binaryFileGitNumstat = "-"
        val Separator = "====="
        val CommitInfo = """([\da-z]+);(.*);(\d+);(.*)""".r
        val MessageFormat = """\s*([^-]+)-(\d+)(.*)""".r
        val FileFormat = """([\d-]+)\s+([\d-]+)\s+(.*)""".r

        def breakIndividualCommits(inputFile: String) = {
            Source.fromFile(inputFile)
                .getLines
                .foldLeft(List[List[String]]()) {
                    case (list, "") => list
                    case (list, Separator) => Nil :: list
                    case (list, e) => (e :: list.head) :: list.tail
                }
        }

        def parseCommit(commit: List[String]) = {
            val commitInfo :: filesInfo = commit.reverse
            val CommitInfo(hash, author, time, fullMessage) = commitInfo
            val files = filesInfo.flatMap { fi =>
                val FileFormat(addedLines, removedLines, path) = fi
                Option(addedLines)
                    .filterNot(_.contains(binaryFileGitNumstat))
                    .map(_ => FileChange(path, addedLines.toInt, removedLines.toInt))
            }

            val (project, story, message) =
                MessageFormat.unapplySeq(fullMessage)
                    .flatMap {
                        case List(p,s,m) => Some(Option(p.toUpperCase), Option(s.toInt), m)
                        case _ => None
                    }.getOrElse((None, None, fullMessage))

            Commit(hash, author.toLowerCase, time.toLong, project, story, message, files)
        }

        def parse(inputFile: String) =
            breakIndividualCommits(inputFile).map(parseCommit(_))
    }

    implicit object jiraExportParser extends Parser[Issue] {

        val KeyFormat = """([^-]+)-(\d+)""".r

        def parse(inputFile: String) = {
            val items = XML.loadFile(inputFile) \\ "item"
            items.map { i =>
                val title = (i \ "title").text
                val KeyFormat(project, story) = (i \ "key").text
                val typeOf = (i \ "type").text
                val status = (i \ "resolution").text
                val devs = (i \\ "customfield")
                    .filter(cf => (cf \ "customfieldname")
                    .text.contains("Developer"))
                    .headOption
                    .map(cf => (cf \\ "customfieldvalue").map(v => v.text))
                Issue(title, project, story.toInt, typeOf, status, devs)
            }
        }
    }

    def parse[T : Parser](inputFile: String) =
        implicitly[Parser[T]].parse(inputFile)

    class RDDCreator(sc: SparkContext) {
        def toRDD[T](inputFile: String)(implicit ev: Parser[T], m: Manifest[T]) : RDD[T] =
            sc.makeRDD(ev.parse(inputFile), sc.defaultParallelism)
                .cache
    }
    implicit def sparkContext2rddCreator(sc: SparkContext) = new RDDCreator(sc)
}
