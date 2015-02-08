package com.ibm.spark.kernel.api

import java.io.{OutputStream, InputStream, PrintStream}

import com.ibm.spark.annotations.Experimental
import com.ibm.spark.comm.CommManager
import com.ibm.spark.global
import com.ibm.spark.interpreter.Results.Result
import com.ibm.spark.interpreter._
import com.ibm.spark.kernel.protocol.v5
import com.ibm.spark.kernel.protocol.v5.kernel.ActorLoader
import com.ibm.spark.kernel.protocol.v5.magic.MagicParser
import com.ibm.spark.kernel.protocol.v5.stream.KernelInputStream
import com.ibm.spark.magic.{MagicLoader, MagicExecutor}
import com.ibm.spark.utils.LogLike
import scala.util.DynamicVariable

import scala.language.dynamics

/**
 * Represents the main kernel API to be used for interaction.
 *
 * @param interpreter The interpreter to expose in this instance
 * @param comm The Comm manager to expose in this instance
 * @param actorLoader The actor loader to use for message relaying
 */
@Experimental
class Kernel (
  private val actorLoader: ActorLoader,
  val interpreter: Interpreter,
  val comm: CommManager,
  val magicLoader: MagicLoader
) extends KernelLike with LogLike {

  /**
   * Represents the current input stream used by the kernel for the specific
   * thread.
   */
  private val currentInputStream =
    new DynamicVariable[InputStream](null)
  private val currentInputStreamInfo =
    new DynamicVariable[StreamInfo](null)

  /**
   * Represents the current output stream used by the kernel for the specific
   * thread.
   */
  private val currentOutputStream =
    new DynamicVariable[PrintStream](null)
  private val currentOutputStreamInfo =
    new DynamicVariable[StreamInfo](null)

  /**
   * Represents the current error stream used by the kernel for the specific
   * thread.
   */
  private val currentErrorStream =
    new DynamicVariable[PrintStream](null)
  private val currentErrorStreamInfo =
    new DynamicVariable[StreamInfo](null)

  /**
   * Represents magics available through the kernel.
   */
  val magics = new MagicExecutor(magicLoader)

  /**
   * Represents magic parsing functionality.
   */
  val magicParser = new MagicParser(magicLoader)

  /**
   * Handles the output of interpreting code.
   * @param output the output of the interpreter
   * @return (success, message) or (failure, message)
   */
  private def handleInterpreterOutput(
    output: (Result, Either[ExecuteOutput, ExecuteFailure])
  ): (Boolean, String) = {
    val (success, result) = output
    success match {
      case Results.Success =>
        (true, result.left.getOrElse("").asInstanceOf[String])
      case Results.Error =>
        (false, result.right.getOrElse("").toString)
      case Results.Aborted =>
        (false, "Aborted!")
      case Results.Incomplete =>
        // If we get an incomplete it's most likely a syntax error, so
        // let the user know.
        (false, "Syntax Error!")
    }
  }

  /**
   * Executes a block of code represented as a string and returns the result.
   *
   * @param code The code as an option to execute
   * @return A tuple containing the result (true/false) and the output as a
   *         string
   */
  def eval(code: Option[String]): (Boolean, String) = {
    code.map(c => {
      magicParser.parse(c) match {
        case Left(parsedCode) =>
          val output = interpreter.interpret(parsedCode)
          handleInterpreterOutput(output)
        case Right(errMsg) =>
          (false, errMsg)
      }
    }).getOrElse((false, "Error!"))
  }

  /**
   * Returns a print stream to be used for communication back to clients
   * via standard out.
   *
   * @return The print stream instance or an error if the stream info is
   *         not found
   */
  override def out(implicit streamInfo: StreamInfo): PrintStream = {
    require(streamInfo.isInstanceOf[v5.KernelMessage],
      "The StreamInfo provided is not a KernelMessage instance!")

    // Update the stream being used only if the information has changed
    // or if the stream has not been initialized
    if (updateOutputStreamInfo(streamInfo) || currentOutputStream.value == null) {
      logger.trace("Creating new kernel output stream!")
      val kernelMessage = streamInfo.asInstanceOf[v5.KernelMessage]
      val outputStream = new v5.stream.KernelOutputStream(
        actorLoader, v5.KMBuilder().withParent(kernelMessage),
        global.ScheduledTaskManager.instance, "stdout")

      currentOutputStream.value = new PrintStream(outputStream)
    }

    currentOutputStream.value
  }

  /**
   * Returns a print stream to be used for communication back to clients
   * via standard error.
   *
   * @return The print stream instance or an error if the stream info is
   *         not found
   */
  override def err(implicit streamInfo: StreamInfo): PrintStream = {
    require(streamInfo.isInstanceOf[v5.KernelMessage],
      "The StreamInfo provided is not a KernelMessage instance!")

    // Update the stream being used only if the information has changed
    // or if the stream has not been initialized
    if (updateErrorStreamInfo(streamInfo) || currentErrorStream.value == null) {
      logger.trace("Creating new kernel error stream!")
      val kernelMessage = streamInfo.asInstanceOf[v5.KernelMessage]
      val outputStream = new v5.stream.KernelOutputStream(
        actorLoader, v5.KMBuilder().withParent(kernelMessage),
        global.ScheduledTaskManager.instance, "stderr")

      currentErrorStream.value = new PrintStream(outputStream)
    }

    currentErrorStream.value
  }

  /**
   * Returns an input stream to be used to receive information from the client.
   *
   * @return The input stream instance or an error if the stream info is
   *         not found
   */
  override def in(implicit streamInfo: StreamInfo): InputStream = {
    require(streamInfo.isInstanceOf[v5.KernelMessage],
      "The StreamInfo provided is not a KernelMessage instance!")

    // Update the stream being used only if the information has changed
    // or if the stream has not been initialized
    if (updateInputStreamInfo(streamInfo) || currentInputStream.value == null) {
      logger.trace("Creating new kernel input stream!")
      currentInputStream.value = new KernelInputStream(
        actorLoader,
        v5.KMBuilder()
          .withIds(streamInfo.asInstanceOf[v5.KernelMessage].ids)
          .withParent(streamInfo.asInstanceOf[v5.KernelMessage])
      )
    }

    currentInputStream.value
  }

  /**
   * Updates the last stream info returning the status of whether or not the
   * new stream info was different than the last stream info.
   *
   * @param dynamicStreamInfo The dynamic variable containing the current
   *                          stream info
   * @param streamInfo The new stream info
   * @return True if the new stream info is different from the last (therefore
   *         replaced), otherwise false
   */
  private def updateStreamInfo(
    dynamicStreamInfo: DynamicVariable[StreamInfo],
    streamInfo: StreamInfo
  ): Boolean =
    if (streamInfo != null && !streamInfo.equals(dynamicStreamInfo.value)) {
      dynamicStreamInfo.value = streamInfo
      true
    } else {
      false
    }
  private val updateInputStreamInfo =
    updateStreamInfo(currentInputStreamInfo, _: StreamInfo)
  private val updateOutputStreamInfo =
    updateStreamInfo(currentOutputStreamInfo, _: StreamInfo)
  private val updateErrorStreamInfo =
    updateStreamInfo(currentErrorStreamInfo, _: StreamInfo)
}
