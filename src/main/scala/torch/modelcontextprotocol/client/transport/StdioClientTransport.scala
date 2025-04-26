/*
 * Copyright 2024-2024 the original author or authors.
 */
package torch.modelcontextprotocol.client.transport

import com.fasterxml.jackson.core.`type`.TypeReference
import com.fasterxml.jackson.databind.ObjectMapper
import torch.modelcontextprotocol.spec.McpSchema.JSONRPCMessage
import org.slf4j.LoggerFactory
import reactor.core.publisher.{Flux, Mono, Sinks, SynchronousSink}
import reactor.core.scheduler.{Scheduler, Schedulers}
import reactor.util.context.Context
import torch.modelcontextprotocol.spec.{ClientMcpTransport, McpSchema}
import torch.modelcontextprotocol.util.Assert

import java.io.{BufferedReader, IOException, InputStreamReader}
import java.nio.charset.StandardCharsets
import java.time.Duration
import java.util
import java.util.concurrent.{CompletableFuture, Executors}
import java.util.function.{Consumer, Function}
import scala.collection.mutable.ListBuffer
import scala.jdk.CollectionConverters.*
import scala.jdk.FutureConverters.*
import scala.util.control.Breaks.break

/**
 * Implementation of the MCP Stdio transport that communicates with a server process using
 * standard input/output streams. Messages are exchanged as newline-delimited JSON-RPC
 * messages over stdin/stdout, with errors and debug information sent to stderr.
 *
 * @author Christian Tzolov
 * @author Dariusz Jędrzejczyk
 */
object StdioClientTransport {
  private val logger = LoggerFactory.getLogger(classOf[StdioClientTransport])
}

class StdioClientTransport(/** Parameters for configuring and starting the server process */
                           private val params: ServerParameters, private var objectMapper: ObjectMapper)

/**
 * Creates a new StdioClientTransport with the specified parameters and ObjectMapper.
 *
 * @param params       The parameters for configuring the server process
 * @param objectMapper The ObjectMapper to use for JSON serialization/deserialization
 */
  extends ClientMcpTransport {
  Assert.notNull(params, "The params can not be null")
  Assert.notNull(objectMapper, "The ObjectMapper can not be null")
  this.inboundSink = Sinks.many.unicast.onBackpressureBuffer
  this.outboundSink = Sinks.many.unicast.onBackpressureBuffer
  this.errorSink = Sinks.many.unicast.onBackpressureBuffer
  // Start threads
  this.inboundScheduler = Schedulers.fromExecutorService(Executors.newSingleThreadExecutor, "inbound")
  this.outboundScheduler = Schedulers.fromExecutorService(Executors.newSingleThreadExecutor, "outbound")
  this.errorScheduler = Schedulers.fromExecutorService(Executors.newSingleThreadExecutor, "error")
  final private var inboundSink: Sinks.Many[McpSchema.JSONRPCMessage] = null
  final private var outboundSink: Sinks.Many[McpSchema.JSONRPCMessage] = null
  /** The server process being communicated with */
  private var process: Process = null
  /** Scheduler for handling inbound messages from the server process */
  private var inboundScheduler: Scheduler = null
  /** Scheduler for handling outbound messages to the server process */
  private var outboundScheduler: Scheduler = null
  /** Scheduler for handling error messages from the server process */
  private var errorScheduler: Scheduler = null
  final private var errorSink: Sinks.Many[String] = null
  @volatile private var isClosing = false
  // visible for tests
  var stdErrorHandler :Consumer[String] = (error: String) => StdioClientTransport.logger.info("STDERR Message received: {}", error)

  /**
   * Creates a new StdioClientTransport with the specified parameters and default
   * ObjectMapper.
   *
   * @param params The parameters for configuring the server process
   */
  def this(params: ServerParameters) ={
    this(params, new ObjectMapper)
  }

  /**
   * Starts the server process and initializes the message processing streams. This
   * method sets up the process with the configured command, arguments, and environment,
   * then starts the inbound, outbound, and error processing threads.
   *
   * @throws RuntimeException if the process fails to start or if the process streams
   *                          are null
   */
  override def connect(handler: Function[Mono[McpSchema.JSONRPCMessage], Mono[McpSchema.JSONRPCMessage]]): Mono[Void] = Mono.fromRunnable[Void](() => {
    handleIncomingMessages(handler)
    handleIncomingErrors()
    // Prepare command and environment
    val fullCommand = new ListBuffer[String]
    fullCommand.append(params.getCommand)
    fullCommand.addAll(params.getArgs)
    val processBuilder = this.getProcessBuilder
    processBuilder.command(fullCommand.toArray *)
    processBuilder.environment.putAll(params.getEnv.asJava)
    // Start the process
    try this.process = processBuilder.start
    catch {
      case e: IOException =>
        throw new RuntimeException("Failed to start process with command: " + fullCommand, e)
    }
    // Validate process streams
    if (this.process.getInputStream == null || process.getOutputStream == null) {
      this.process.destroy()
      throw new RuntimeException("Process input or output stream is null")
    }
    // Start threads
    startInboundProcessing()
    startOutboundProcessing()
    startErrorProcessing()
  }).subscribeOn(Schedulers.boundedElastic)

  /**
   * Creates and returns a new ProcessBuilder instance. Protected to allow overriding in
   * tests.
   *
   * @return A new ProcessBuilder instance
   */
  protected def getProcessBuilder = new ProcessBuilder

  /**
   * Sets the handler for processing transport-level errors.
   *
   * <p>
   * The provided handler will be called when errors occur during transport operations,
   * such as connection failures or protocol violations.
   * </p>
   *
   * @param errorHandler a consumer that processes error messages
   */
  def setStdErrorHandler(errorHandler: Consumer[String]): Unit = {
    this.stdErrorHandler = errorHandler
  }

  /**
   * Waits for the server process to exit.
   *
   * @throws RuntimeException if the process is interrupted while waiting
   */
  def awaitForExit(): Unit = {
    try this.process.waitFor
    catch {
      case e: InterruptedException =>
        throw new RuntimeException("Process interrupted", e)
    }
  }

  /**
   * Starts the error processing thread that reads from the process's error stream.
   * Error messages are logged and emitted to the error sink.
   */
  private def startErrorProcessing(): Unit = {
    this.errorScheduler.schedule(() => {
      try {
        val processErrorReader = new BufferedReader(new InputStreamReader(process.getErrorStream))
        try {
          var line: String = null
          while (!isClosing && {line = processErrorReader.readLine ; line != null}) 
            try {
              if (!this.errorSink.tryEmitNext(line).isSuccess) {
                if (!isClosing) StdioClientTransport.logger.error("Failed to emit error message")
                break //todo: break is not supported
              }
            }catch {
              case e: Exception =>
                if (!isClosing)
                  StdioClientTransport.logger.error("Error processing error message", e)
                  break //todo: break is not supported
            } }catch {
                  case e: IOException =>
                  if (!isClosing) StdioClientTransport.logger.error("Error reading from error stream", e)
        } finally {
          isClosing = true
          errorSink.tryEmitComplete
          if (processErrorReader != null) processErrorReader.close()
        }
      }
    })
  }

  private def handleIncomingMessages(inboundMessageHandler: Function[Mono[McpSchema.JSONRPCMessage], Mono[McpSchema.JSONRPCMessage]]): Unit = {
    this.inboundSink.asFlux.flatMap((message: McpSchema.JSONRPCMessage) => Mono.just(message).transform(inboundMessageHandler).contextWrite((ctx: Context) => ctx.put("observation", "myObservation"))).subscribe
  }

  private def handleIncomingErrors(): Unit = {
    this.errorSink.asFlux.subscribe((e: String) => {
      this.stdErrorHandler.accept(e)
    })
  }

  override def sendMessage(message: McpSchema.JSONRPCMessage): Mono[Void] = if (this.outboundSink.tryEmitNext(message).isSuccess) {
    // TODO: essentially we could reschedule ourselves in some time and make
    // another attempt with the already read data but pause reading until
    // success
    // In this approach we delegate the retry and the backpressure onto the
    // caller. This might be enough for most cases.
    Mono.empty
  }
  else Mono.error(new RuntimeException("Failed to enqueue message"))

  /**
   * Starts the inbound processing thread that reads JSON-RPC messages from the
   * process's input stream. Messages are deserialized and emitted to the inbound sink.
   */
  private def startInboundProcessing(): Unit = {
    this.inboundScheduler.schedule(() => {
      try {
        val processReader = new BufferedReader(new InputStreamReader(process.getInputStream))
        try {
          var line: String = null
          while (!isClosing && {line = processReader.readLine ; line != null}) try {
            val message = McpSchema.deserializeJsonRpcMessage(this.objectMapper, line)
            if (!this.inboundSink.tryEmitNext(message).isSuccess) {
              if (!isClosing) StdioClientTransport.logger.error("Failed to enqueue inbound message: {}", message)
              break //todo: break is not supported
            }
          } catch {
            case e: Exception =>
              if (!isClosing) StdioClientTransport.logger.error("Error processing inbound message for line: " + line, e)
              break //todo: break is not supported
          }
        } catch {
          case e: IOException =>
            if (!isClosing) StdioClientTransport.logger.error("Error reading from input stream", e)
        } finally {
          isClosing = true
          inboundSink.tryEmitComplete
          if (processReader != null) processReader.close()
        }
      }
    })
  }

  /**
   * Starts the outbound processing thread that writes JSON-RPC messages to the
   * process's output stream. Messages are serialized to JSON and written with a newline
   * delimiter.
   */
  private def startOutboundProcessing(): Unit = {
    this.handleOutbound((messages: Flux[McpSchema.JSONRPCMessage]) => messages.publishOn(outboundScheduler).handle((message: McpSchema.JSONRPCMessage, s: SynchronousSink[McpSchema.JSONRPCMessage]) => {
      if (message != null && !isClosing) try {
        var jsonMessage = objectMapper.writeValueAsString(message)
        // Escape any embedded newlines in the JSON message as per spec:
        // https://spec.modelcontextprotocol.io/specification/basic/transports/#stdio
        // - Messages are delimited by newlines, and MUST NOT contain
        // embedded newlines.
        jsonMessage = jsonMessage.replace("\r\n", "\\n").replace("\n", "\\n").replace("\r", "\\n")
        val os = this.process.getOutputStream
        os.synchronized {
          os.write(jsonMessage.getBytes(StandardCharsets.UTF_8))
          os.write("\n".getBytes(StandardCharsets.UTF_8))
          os.flush()
        }
        s.next(message)
      } catch {
        case e: IOException =>
          s.error(new RuntimeException(e))
      }
    }))
  }

  protected def handleOutbound(outboundConsumer: Function[Flux[McpSchema.JSONRPCMessage], Flux[McpSchema.JSONRPCMessage]]): Unit = {
    outboundConsumer.apply(outboundSink.asFlux).doOnComplete(() => {
      isClosing = true
      outboundSink.tryEmitComplete
    }).doOnError((e: Throwable) => {
      if (!isClosing) {
        StdioClientTransport.logger.error("Error in outbound processing", e)
        isClosing = true
        outboundSink.tryEmitComplete
      }
    }).subscribe
  }

  /**
   * Gracefully closes the transport by destroying the process and disposing of the
   * schedulers. This method sends a TERM signal to the process and waits for it to exit
   * before cleaning up resources.
   *
   * @return A Mono that completes when the transport is closed
   */

  import java.lang.Process as JavaProcess
  override def closeGracefully: Mono[Void] = Mono.fromRunnable(() => {
    isClosing = true
    StdioClientTransport.logger.debug("Initiating graceful shutdown")
  }).`then`(Mono.defer(() => {
    // First complete all sinks to stop accepting new messages
    // First complete all sinks to stop accepting new messages
    inboundSink.tryEmitComplete
    outboundSink.tryEmitComplete
    errorSink.tryEmitComplete

    //    inboundSink.asInstanceOf[AnyRef].getClass.getMethod("tryEmitComplete").invoke(inboundSink)
    //    outboundSink.asInstanceOf[AnyRef].getClass.getMethod("tryEmitComplete").invoke(outboundSink)
    //    errorSink.asInstanceOf[AnyRef].getClass.getMethod("tryEmitComplete").invoke(errorSink)
    // Give a short time for any pending messages to be processed
    Mono.delay(Duration.ofMillis(100))
  })).`then`(Mono.fromFuture(() => {
    StdioClientTransport.logger.debug("Sending TERM to process")
    if (this.process != null) {
      this.process.destroy()
      // 使用 Java 9+ 的 onExit 方法
      CompletableFuture.supplyAsync(() => {
        try {
          process.waitFor()
          process
        } catch {
          case e: InterruptedException =>
            Thread.currentThread().interrupt()
            throw e
        }
      })
    } else {
      // 模拟 failedFuture 方法
      val future = new CompletableFuture[JavaProcess]()
      future.completeExceptionally(new RuntimeException("Process not started"))
      future
    }
  })).doOnNext(new Consumer[JavaProcess] {
    override def accept(process: JavaProcess): Unit = {
      if (process.exitValue() != 0) {
        StdioClientTransport.logger.warn("Process terminated with code " + process.exitValue())
      }
    }
  }).`then`(Mono.fromRunnable(() => {
    try {
      // The Threads are blocked on readLine so disposeGracefully would not
      // interrupt them, therefore we issue an async hard dispose.
      inboundScheduler.dispose()
      errorScheduler.dispose()
      outboundScheduler.dispose()
      StdioClientTransport.logger.debug("Graceful shutdown completed")
      //      inboundScheduler.asInstanceOf[AnyRef].getClass.getMethod("dispose").invoke(inboundScheduler)
      //      errorScheduler.asInstanceOf[AnyRef].getClass.getMethod("dispose").invoke(errorScheduler)
      //      outboundScheduler.asInstanceOf[AnyRef].getClass.getMethod("dispose").invoke(outboundScheduler)
      //      StdioClientTransport.logger.debug("Graceful shutdown completed")
    } catch {
      case e: Exception =>
        StdioClientTransport.logger.error("Error during graceful shutdown", e)
    }
  })).`then`().subscribeOn(Schedulers.boundedElastic)

  //  override def closeGracefullys: Mono[Void] = Mono.fromRunnable(() => {
  //    isClosing = true
  //    StdioClientTransport.logger.debug("Initiating graceful shutdown")
  //  }).`then`(Mono.defer(() => {
  //
  //    // First complete all sinks to stop accepting new messages
  //    inboundSink.tryEmitComplete
  //    outboundSink.tryEmitComplete
  //    errorSink.tryEmitComplete
  //    // Give a short time for any pending messages to be processed
  //    Mono.delay(Duration.ofMillis(100))
  //  })).`then`(Mono.fromFuture(() => {
  //    StdioClientTransport.logger.debug("Sending TERM to process")
  //    if (this.process != null) {
  //      this.process.destroy()
  //      process.onExit()
  //    }
  //    else CompletableFuture.failedFuture(new RuntimeException("Process not started"))
  //  })).doOnNext((process: Process) => {
  //    if (process.exitValue != 0) StdioClientTransport.logger.warn("Process terminated with code " + process.exitValue)
  //  }).`then`(Mono.fromRunnable(() => {
  //    try {
  //      // The Threads are blocked on readLine so disposeGracefully would not
  //      // interrupt them, therefore we issue an async hard dispose.
  //      inboundScheduler.dispose()
  //      errorScheduler.dispose()
  //      outboundScheduler.dispose()
  //      StdioClientTransport.logger.debug("Graceful shutdown completed")
  //    } catch {
  //      case e: Exception =>
  //        StdioClientTransport.logger.error("Error during graceful shutdown", e)
  //    }
  //  })).`then`.subscribeOn(Schedulers.boundedElastic)

  def getErrorSink: Sinks.Many[String] = this.errorSink

  override def unmarshalFrom[T](data: AnyRef, typeRef: TypeReference[T]): T = this.objectMapper.convertValue(data, typeRef)
}