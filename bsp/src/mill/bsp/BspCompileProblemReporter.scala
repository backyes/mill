package mill.bsp

import ch.epfl.scala.bsp4j._
import ch.epfl.scala.{bsp4j => bsp}
import mill.api.{CompileProblemReporter, Problem}
import os.Path

import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.{AtomicBoolean, AtomicInteger}
import scala.collection.concurrent
import scala.jdk.CollectionConverters._
import scala.language.implicitConversions

/**
 * Specialized reporter that sends compilation diagnostics
 * for each problem it logs, either as information, warning or
 * error as well as task finish notifications of type `compile-report`.
 *
 * @param client              the client to send diagnostics to
 * @param targetId            the target id of the target whose compilation
 *                            the diagnostics are related to
 * @param taskId              a unique id of the compilation task of the target
 *                            specified by `targetId`
 * @param compilationOriginId optional origin id the client assigned to
 *                            the compilation request. Needs to be sent
 *                            back as part of the published diagnostics
 *                            as well as compile report
 */
class BspCompileProblemReporter(
    client: bsp.BuildClient,
    targetId: BuildTargetIdentifier,
    targetDisplayName: String,
    taskId: TaskId,
    compilationOriginId: Option[String]
) extends CompileProblemReporter {

  private[this] val errors = new AtomicInteger(0)
  private[this] val warnings = new AtomicInteger(0)
  private[this] val infos = new AtomicInteger(0)
  private[this] val diagnosticMap
      : concurrent.Map[TextDocumentIdentifier, bsp.PublishDiagnosticsParams] =
    new ConcurrentHashMap[TextDocumentIdentifier, bsp.PublishDiagnosticsParams]().asScala
  private[this] val started = new AtomicBoolean(false)
  private[this] val finished = new AtomicBoolean(false)

  val compileTask = new CompileTask(targetId)

  override def logError(problem: Problem): Unit = {
    client.onBuildPublishDiagnostics(getDiagnostics(problem, targetId, compilationOriginId))
    errors.incrementAndGet()
  }

  override def logInfo(problem: Problem): Unit = {
    client.onBuildPublishDiagnostics(getDiagnostics(problem, targetId, compilationOriginId))
    infos.incrementAndGet()
  }

  // Obtains the parameters for sending diagnostics about the given Problem ( as well as
  // about all previous problems generated for the same text file ) related to the specified
  // targetId, incorporating the given optional originId ( generated by the client for the
  // compile request )
  // TODO: document that if the problem is a general information without a text document
  // associated to it, then the document field of the diagnostic is set to the uri of the target
  private[this] def getDiagnostics(
      problem: Problem,
      targetId: bsp.BuildTargetIdentifier,
      originId: Option[String]
  ): bsp.PublishDiagnosticsParams = {
    val diagnostic = getSingleDiagnostic(problem)
    val sourceFile = problem.position.sourceFile
    val textDocument = new TextDocumentIdentifier(
      sourceFile.getOrElse(None) match {
        case None => targetId.getUri
        case f: File => f.toURI.toString
      }
    )
    val params = new bsp.PublishDiagnosticsParams(
      textDocument,
      targetId,
      appendDiagnostics(textDocument, diagnostic).asJava,
      true
    )

    if (originId.nonEmpty) {
      params.setOriginId(originId.get)
    }
    diagnosticMap.put(textDocument, params)
    params
  }

  private[this] def ensureDiagnostics(textDocument: TextDocumentIdentifier)
      : bsp.PublishDiagnosticsParams = {
    diagnosticMap.putIfAbsent(
      textDocument,
      new bsp.PublishDiagnosticsParams(
        textDocument,
        targetId,
        List.empty[Diagnostic].asJava,
        true
      )
    )
    diagnosticMap(textDocument)
  }

  // Update the published diagnostics for the given text file by
  // adding the recently computed diagnostic to the list of
  // all previous diagnostics generated for the same file.
  private[this] def appendDiagnostics(
      textDocument: TextDocumentIdentifier,
      currentDiagnostic: Diagnostic
  ): List[Diagnostic] = {
    ensureDiagnostics(textDocument).getDiagnostics.asScala.toList ++ List(currentDiagnostic)
  }

  // Computes the diagnostic related to the given Problem
  private[this] def getSingleDiagnostic(problem: Problem): Diagnostic = {
    // Zinc's range starts at 1 whereas BSP at 0
    def correctLine = (_: Int) - 1

    val pos = problem.position
    val line = pos.line.map(correctLine)
    val start = new bsp.Position(
      pos.startLine.map(correctLine).orElse(line).getOrElse[Int](0),
      pos.startColumn.orElse(pos.pointer).getOrElse[Int](0)
    )
    val end = new bsp.Position(
      pos.endLine.map(correctLine).orElse(line).getOrElse[Int](start.getLine.intValue()),
      pos.endColumn.orElse(pos.pointer).getOrElse[Int](start.getCharacter.intValue())
    )
    val diagnostic = new bsp.Diagnostic(new bsp.Range(start, end), problem.message)
    diagnostic.setSource("mill")
    diagnostic.setSeverity(
      problem.severity match {
        case mill.api.Info => bsp.DiagnosticSeverity.INFORMATION
        case mill.api.Error => bsp.DiagnosticSeverity.ERROR
        case mill.api.Warn => bsp.DiagnosticSeverity.WARNING
      }
    )
    problem.diagnosticCode.foreach { existingCode => diagnostic.setCode(existingCode.code) }
    diagnostic
  }

  override def logWarning(problem: Problem): Unit = {
    client.onBuildPublishDiagnostics(getDiagnostics(problem, targetId, compilationOriginId))
    warnings.incrementAndGet()
  }

  override def fileVisited(file: Path): Unit = {
    val uri = file.toNIO.toUri.toString
    client.onBuildPublishDiagnostics(ensureDiagnostics(new TextDocumentIdentifier(uri)))
  }

  override def printSummary(): Unit = {
    finish()
  }

  // Compute the compilation status code
  private[this] def getStatusCode: StatusCode = {
    if (errors.get > 0) StatusCode.ERROR else StatusCode.OK
  }

  override def start(): Unit = {
    if (!started.get()) {
      val taskStartParams = new TaskStartParams(taskId)
      taskStartParams.setEventTime(System.currentTimeMillis())
      taskStartParams.setData(compileTask)
      taskStartParams.setDataKind(TaskDataKind.COMPILE_TASK)
      taskStartParams.setMessage(s"Compiling target ${targetDisplayName}")
      client.onBuildTaskStart(taskStartParams)
      started.set(true)
    }
  }

  override def finish(): Unit = {
    if (!finished.get()) {
      val taskFinishParams = new TaskFinishParams(taskId, getStatusCode)
      taskFinishParams.setEventTime(System.currentTimeMillis())
      taskFinishParams.setMessage(s"Compiled ${targetDisplayName}")
      taskFinishParams.setDataKind(TaskDataKind.COMPILE_REPORT)
      val compileReport = new CompileReport(targetId, errors.get, warnings.get)
      compilationOriginId match {
        case Some(id) => compileReport.setOriginId(id)
        case None =>
      }
      taskFinishParams.setData(compileReport)
      client.onBuildTaskFinish(taskFinishParams)
      finished.set(true)
    }
  }

}
