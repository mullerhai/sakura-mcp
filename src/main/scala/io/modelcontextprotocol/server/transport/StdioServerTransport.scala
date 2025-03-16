/*
 * Copyright 2024-2024 the original author or authors.
 */
package io.modelcontextprotocol.server.transport

import com.fasterxml.jackson.core.`type`.TypeReference
import reactor.util.context.Context

import java.io.*
import java.nio.charset.StandardCharsets
import java.util.concurrent.Executors
import java.util.function.Function
import scala.util.control.Breaks.break
//import com.fasterxml.jackson.core.
//
//type.TypeReference

import com.fasterxml.jackson.databind.ObjectMapper
import io.modelcontextprotocol.spec.McpSchema.JSONRPCMessage
import io.modelcontextprotocol.spec.{McpSchema, ServerMcpTransport}
import io.modelcontextprotocol.util.Assert
import org.slf4j.{Logger, LoggerFactory}
import reactor.core.publisher.{Flux, Mono, Sinks}
import reactor.core.scheduler.{Scheduler, Schedulers}

/**
 * Implementation of the MCP Stdio transport for servers that communicates using standard
 * input/output streams. Messages are exchanged as newline-delimited JSON-RPC messages
 * over stdin/stdout, with errors and debug information sent to stderr.
 *
 * @author Christian Tzolov
 */
object StdioServerTransport {
  private val logger = LoggerFactory.getLogger(classOf[StdioServerTransport])
}

class StdioServerTransport(var objectMapper: ObjectMapper)  extends ServerMcpTransport {
  final private var inboundSink: Sinks.Many[McpSchema.JSONRPCMessage] = null
  final private var outboundSink: Sinks.Many[McpSchema.JSONRPCMessage] = null
  /** Scheduler for handling inbound messages */
  private var inboundScheduler: Scheduler = null
  /** Scheduler for handling outbound messages */
  private var outboundScheduler: Scheduler = null
  @volatile private var isClosing = false
  final private var inputStream: InputStream = null
  final private var outputStream: OutputStream = null
  final private val inboundReady = Sinks.one
  final private val outboundReady = Sinks.one
  Assert.notNull(objectMapper, "The ObjectMapper can not be null")
  this.inboundSink = Sinks.many.unicast.onBackpressureBuffer
  this.outboundSink = Sinks.many.unicast.onBackpressureBuffer
  this.inputStream = System.in
  this.outputStream = System.out
  // Use bounded schedulers for better resource management
  this.inboundScheduler = Schedulers.fromExecutorService(Executors.newSingleThreadExecutor, "inbound")
  this.outboundScheduler = Schedulers.fromExecutorService(Executors.newSingleThreadExecutor, "outbound")
 
  def logIfNotClosing(message: String, e: Exception): Unit = {
    if (!this.isClosing) StdioServerTransport.logger.error(message, e)
  }

  def logIfNotClosing(message: String): Unit = {
    if (!this.isClosing) StdioServerTransport.logger.error(message)
  }

  override def closeGracefully = Mono.defer[Void](() => {
    isClosing = true
    StdioServerTransport.logger.debug("Initiating graceful shutdown")
    // Completing the inbound causes the outbound to be completed as well, so
    // we only close the inbound.
    inboundSink.tryEmitComplete
    StdioServerTransport.logger.debug("Graceful shutdown complete")
    Mono.empty
  }).subscribeOn(Schedulers.boundedElastic)

  override def unmarshalFrom[T](data: AnyRef, typeRef: TypeReference[T]) = this.objectMapper.convertValue(data, typeRef)

  override def connect(handler: Function[Mono[McpSchema.JSONRPCMessage], Mono[McpSchema.JSONRPCMessage]]): Mono[Void] = Mono.fromRunnable[Void](() => {
    handleIncomingMessages(handler)
    // Start threads
    startInboundProcessing()
    startOutboundProcessing()
  }).subscribeOn(Schedulers.boundedElastic)

  def handleIncomingMessages(inboundMessageHandler: Function[Mono[McpSchema.JSONRPCMessage], Mono[McpSchema.JSONRPCMessage]]): Unit = {
    this.inboundSink.asFlux.flatMap((message: McpSchema.JSONRPCMessage) => Mono.just(message).transform(inboundMessageHandler).contextWrite((ctx: Context) => ctx.put("observation", "myObservation"))).doOnTerminate(() => {

      // The outbound processing will dispose its scheduler upon completion
      this.outboundSink.tryEmitComplete
      this.inboundScheduler.dispose()
    }).subscribe
  }

  override def sendMessage(message: McpSchema.JSONRPCMessage): Mono[Void] = Mono.zip(inboundReady.asMono, outboundReady.asMono).`then`(Mono.defer(() => {
    if (this.outboundSink.tryEmitNext(message).isSuccess) Mono.empty
    else Mono.error(new RuntimeException("Failed to enqueue message"))
  }))


  def startInboundProcessing(): Unit = {
    this.inboundScheduler.schedule(() => {
      inboundReady.tryEmitValue(null)
      var reader: BufferedReader = null
      try {
        reader = new BufferedReader(new InputStreamReader(inputStream))
        while (!isClosing) try {
          val line = reader.readLine
          if (line == null || isClosing) break //todo: break is not supported
          StdioServerTransport.logger.debug("Received JSON message: {}", line)
          try {
            val message = McpSchema.deserializeJsonRpcMessage(this.objectMapper, line)
            if (!this.inboundSink.tryEmitNext(message).isSuccess) {
              logIfNotClosing("Failed to enqueue message")
              break //todo: break is not supported
            }
          } catch {
            case e: Exception =>
              logIfNotClosing("Error processing inbound message", e)
              break //todo: break is not supported
          }
        } catch {
          case e: IOException =>
            logIfNotClosing("Error reading from stdin", e)
            break //todo: break is not supported
        }
      } catch {
        case e: Exception =>
          logIfNotClosing("Error in inbound processing", e)
      } finally {
        isClosing = true
        inboundSink.tryEmitComplete
      }
    })
  }

  /**
   * Starts the outbound processing thread that writes JSON-RPC messages to stdout.
   * Messages are serialized to JSON and written with a newline delimiter.
   */
  def startOutboundProcessing(): Unit =  { 
    val outboundConsumer :Function[Flux[JSONRPCMessage], Flux[JSONRPCMessage]] =
      (messages: Flux[McpSchema.JSONRPCMessage]) =>
        messages.doOnSubscribe(subscription => outboundReady.tryEmitValue(null)).publishOn(outboundScheduler).handle((message,sink) => {
          if (message != null && !isClosing)
            try {
              var jsonMessage = objectMapper.writeValueAsString(message)
              // Escape any embedded newlines in the JSON message as per spec
              jsonMessage = jsonMessage.replace("\r\n", "\\n").replace("\n", "\\n").replace("\r", "\\n")
              outputStream.synchronized {
                outputStream.write(jsonMessage.getBytes(StandardCharsets.UTF_8))
                outputStream.write("\n".getBytes(StandardCharsets.UTF_8))
                outputStream.flush()
              }
              sink.next(message)
            } catch {
              case e: IOException =>
                if (!isClosing) {
                  StdioServerTransport.logger.error("Error writing message", e)
                  sink.error(new RuntimeException(e))
                }
                else StdioServerTransport.logger.debug("Stream closed during shutdown", e)
            }
          else if (isClosing) sink.complete()
        }).doOnComplete(()=>{
          isClosing = true
//          outboundSink.tryEmitComplete
          outboundScheduler.dispose()          
        }).doOnError((e: Throwable) => {
          if (! isClosing )  { StdioServerTransport.logger.error("Error in outbound processing", e)
            isClosing = true
            outboundScheduler.dispose()
          }

        }).map((msg: AnyRef) => msg.asInstanceOf[McpSchema.JSONRPCMessage])
//        outboundConsumer.apply(outboundSink.asFlux).subscribe
        // @formatter:off.doOnSubscribe((subscription: Subscription) => outboundReady.tryEmitValue(null)).publishOn(outboundScheduler).handle((message: McpSchema.JSONRPCMessage, sink: SynchronousSink[AnyRef]) => {
    
    outboundConsumer.apply(outboundSink.asFlux).subscribe
  } // @formatter:on

  
}

/**
 * Creates a new StdioServerTransport with the specified ObjectMapper and System
 * streams.
 *
 * @param objectMapper The ObjectMapper to use for JSON serialization/deserialization
 */
 


  /**
   * Creates a new StdioServerTransport with a default ObjectMapper and System streams.
   */
//  def this {
//    this(new ObjectMapper)
//  }


  /**
   * Starts the inbound processing thread that reads JSON-RPC messages from stdin.
   * Messages are deserialized and emitted to the inbound sink.
   */

