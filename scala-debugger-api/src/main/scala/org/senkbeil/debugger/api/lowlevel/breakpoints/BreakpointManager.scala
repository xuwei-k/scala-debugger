package org.senkbeil.debugger.api.lowlevel.breakpoints

import java.util.concurrent.ConcurrentHashMap

import com.sun.jdi.request.{EventRequestManager, BreakpointRequest}
import org.senkbeil.debugger.api.lowlevel.classes.ClassManager
import org.senkbeil.debugger.api.lowlevel.requests.JDIRequestArgument
import org.senkbeil.debugger.api.lowlevel.requests.properties.{EnabledProperty, SuspendPolicyProperty}
import org.senkbeil.debugger.api.utils.Logging
import com.sun.jdi.Location

import scala.collection.JavaConverters._
import scala.util.{Try, Failure}

/**
 * Represents the manager for breakpoint requests.
 *
 * @param eventRequestManager The manager used to create breakpoint requests
 * @param classManager The class manager associated with the virtual machine,
 *                      used to retrieve location information
 */
class BreakpointManager(
  private val eventRequestManager: EventRequestManager,
  private val classManager: ClassManager
) extends Logging {
  import org.senkbeil.debugger.api.lowlevel.requests.Implicits._

  /** The arguments used to lookup breakpoint requests: (Class, Line) */
  type BreakpointArgs = (String, Int)

  /** The key used to lookup breakpoint requests */
  type BreakpointKey = String

  private val breakpointArgsToRequestId =
    new ConcurrentHashMap[BreakpointArgs, BreakpointKey]().asScala

  private val breakpointRequests =
    new ConcurrentHashMap[BreakpointKey, Seq[BreakpointRequest]]().asScala

  /**
   * Retrieves the list of breakpoints contained by this manager.
   *
   * @return The collection of breakpoints in the form of
   *         (file name, line number)
   */
  def breakpointRequestList: Seq[BreakpointArgs] =
    breakpointArgsToRequestId.keys.toSeq

  /**
   * Retrieves the list of breakpoints contained by this manager.
   *
   * @return The collection of breakpoints by id
   */
  def breakpointRequestListById: Seq[BreakpointKey] =
    breakpointRequests.keys.toSeq

  /**
   * Creates and enables a breakpoint on the specified line of the class.
   *
   * @param requestId The id of the request used for lookup and removal
   * @param fileName The name of the file to set a breakpoint
   * @param lineNumber The number of the line to break
   * @param extraArguments Any additional arguments to provide to the request
   *
   * @return Success(id) if successful, otherwise Failure
   */
  def createLineBreakpointRequestWithId(
    requestId: String,
    fileName: String,
    lineNumber: Int,
    extraArguments: JDIRequestArgument*
  ): Try[BreakpointKey] = {
    // Retrieve the available locations for the specified line
    val locations = classManager
      .linesAndLocationsForFile(fileName)
      .flatMap(_.get(lineNumber))
      .getOrElse(Nil)

    // Exit early if no locations are available
    if (locations.isEmpty)
      return Failure(NoBreakpointLocationFound(fileName, lineNumber))

    val arguments = Seq(
      SuspendPolicyProperty.EventThread,
      EnabledProperty(value = true)
    ) ++ extraArguments

    // TODO: Back out breakpoint creation if a failure occurs
    val requests = Try(locations.map(
      eventRequestManager.createBreakpointRequest(_: Location, arguments: _*)
    ))

    if (requests.isSuccess) {
      breakpointArgsToRequestId.put((fileName, lineNumber), requestId)
      breakpointRequests.put(requestId, requests.get)
    }

    // If no exception was thrown, assume that we succeeded
    requests.map(_ => requestId)
  }

  /**
   * Creates and enables a breakpoint on the specified line of the class.
   *
   * @param fileName The name of the file to set a breakpoint
   * @param lineNumber The number of the line to break
   * @param extraArguments Any additional arguments to provide to the request
   *
   * @return Success(id) if successful, otherwise Failure
   */
  def createLineBreakpointRequest(
    fileName: String,
    lineNumber: Int,
    extraArguments: JDIRequestArgument*
  ): Try[BreakpointKey] = {
    createLineBreakpointRequestWithId(
      newRequestId(),
      fileName,
      lineNumber,
      extraArguments: _*
    )
  }

  /**
   * Determines whether or not the breakpoint for the specific file's line.
   *
   * @param fileName The name of the file whose line to reference
   * @param lineNumber The number of the line to check for a breakpoint
   *
   * @return True if a breakpoint exists, otherwise false
   */
  def hasLineBreakpointRequest(fileName: String, lineNumber: Int): Boolean = {
    breakpointArgsToRequestId.get((fileName, lineNumber))
      .exists(hasLineBreakpointRequestWithId)
  }

  /**
   * Determines whether or not the breakpoint with the specified id exists.
   *
   * @param requestId The id of the request
   *
   * @return True if a breakpoint exists, otherwise false
   */
  def hasLineBreakpointRequestWithId(requestId: String): Boolean = {
    breakpointRequests.contains(requestId)
  }

  /**
   * Returns the collection of breakpoints representing the breakpoint for the
   * specified line.
   *
   * @param fileName The name of the file whose line to reference
   * @param lineNumber The number of the line to check for breakpoints
   *
   * @return Some collection of breakpoints for the specified line, or None if
   *         the specified line has no breakpoints
   */
  def getLineBreakpointRequest(
    fileName: String,
    lineNumber: Int
  ): Option[Seq[BreakpointRequest]] = {
    breakpointArgsToRequestId.get((fileName, lineNumber))
      .flatMap(getLineBreakpointRequestWithId)
  }

  /**
   * Returns the collection of breakpoints with the specified id.
   *
   * @param requestId The id of the request
   *
   * @return Some collection of breakpoints for the specified line, or None if
   *         the specified line has no breakpoints
   */
  def getLineBreakpointRequestWithId(
    requestId: String
  ): Option[Seq[BreakpointRequest]] = {
    breakpointRequests.get(requestId)
  }

  /**
   * Removes the breakpoint on the specified line of the file.
   *
   * @param fileName The name of the file to remove the breakpoint
   * @param lineNumber The number of the line to break
   *
   * @return True if successfully removed breakpoint, otherwise false
   */
  def removeLineBreakpointRequest(
    fileName: String,
    lineNumber: Int
  ): Boolean = {
    breakpointArgsToRequestId.get((fileName, lineNumber))
      .exists(removeLineBreakpointRequestWithId)
  }

  /**
   * Removes the breakpoint with the specified id.
   *
   * @param requestId The id of the request
   *
   * @return True if successfully removed breakpoint, otherwise false
   */
  def removeLineBreakpointRequestWithId(
    requestId: String
  ): Boolean = {
    val requests = breakpointRequests.remove(requestId)

    // Reverse-lookup arguments to remove argsToId mapping
    breakpointArgsToRequestId.find(_._2 == requestId).map(_._1)
      .foreach(breakpointArgsToRequestId.remove)

    requests.map(_.asJava).foreach(eventRequestManager.deleteEventRequests)

    requests.nonEmpty
  }

  /**
   * Generates an id for a new request.
   *
   * @return The id as a string
   */
  protected def newRequestId(): String = java.util.UUID.randomUUID().toString
}
