/*
 * Copyright 2024-2024 the original author or authors.
 */
package io.modelcontextprotocol.spec

import io.modelcontextprotocol.spec.McpSchema.JSONRPCResponse
import io.modelcontextprotocol.util.Assert
import com.fasterxml.jackson.core.*
import com.fasterxml.jackson.core.`type`.TypeReference
import reactor.core.publisher.SynchronousSink

import java.time.Duration
import java.util
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import scala.jdk.FunctionConverters.*
//type.TypeReference


import org.slf4j.LoggerFactory
import reactor.core.Disposable
import reactor.core.publisher.{Mono, MonoSink}

/**
 * Default implementation of the MCP (Model Context Protocol) session that manages
 * bidirectional JSON-RPC communication between clients and servers. This implementation
 * follows the MCP specification for message exchange and transport handling.
 *
 * <p>
 * The session manages:
 * <ul>
 * <li>Request/response handling with unique message IDs</li>
 * <li>Notification processing</li>
 * <li>Message timeout management</li>
 * <li>Transport layer abstraction</li>
 * </ul>
 *
 * @author Christian Tzolov
 * @author Dariusz Jędrzejczyk
 */
object DefaultMcpSession {
  /** Logger for this class */
  private val logger = LoggerFactory.getLogger(classOf[DefaultMcpSession])

  /**
   * Functional interface for handling incoming JSON-RPC requests. Implementations
   * should process the request parameters and return a response.
   *
   * @param < T> Response type
   */
  @FunctionalInterface trait RequestHandler[T] {
    /**
     * Handles an incoming request with the given parameters.
     *
     * @param params The request parameters
     * @return A Mono containing the response object
     */
    def handle(params: AnyRef): Mono[T]
  }

  /**
   * Functional interface for handling incoming JSON-RPC notifications. Implementations
   * should process the notification parameters without returning a response.
   */
  @FunctionalInterface trait NotificationHandler {
    /**
     * Handles an incoming notification with the given parameters.
     *
     * @param params The notification parameters
     * @return A Mono that completes when the notification is processed
     */
    def handle(params: AnyRef): Mono[Void]
  }

  final case class MethodNotFoundError (method: String, message: String, data: AnyRef) //{
//    this.method = method
//    this.message = message
//    this.data = data
//    final private val method: String = null
//    final private val message: String = null
//    final private val data: AnyRef = null
//  }

  def getMethodNotFoundError(method: String): DefaultMcpSession.MethodNotFoundError = method match {
    case McpSchema.METHOD_ROOTS_LIST =>
      new DefaultMcpSession.MethodNotFoundError(method, "Roots not supported", util.Map.of("reason", "Client does not have roots capability"))
    case _ =>
      new DefaultMcpSession.MethodNotFoundError(method, "Method not found: " + method, null)
  }
}

class DefaultMcpSession(/** Duration to wait for request responses before timing out */
                        val requestTimeout: Duration,
                        /** Transport layer implementation for message exchange */
                        val transport: McpTransport,
                        requestHandlers: ConcurrentHashMap[String, DefaultMcpSession.RequestHandler[_]],
                        notificationHandlers: ConcurrentHashMap[String, DefaultMcpSession.NotificationHandler]) extends McpSession {
  /** Map of pending responses keyed by request ID */
  final val pendingResponses = new ConcurrentHashMap[AnyRef, MonoSink[McpSchema.JSONRPCResponse]]
  /** Map of request handlers keyed by method name */
//  final val requestHandlers = new ConcurrentHashMap[String, DefaultMcpSession.RequestHandler[_]]
  /** Map of notification handlers keyed by method name */
//  final val notificationHandlers = new ConcurrentHashMap[String, DefaultMcpSession.NotificationHandler]
  /** Session-specific prefix for request IDs */
  final val sessionPrefix = UUID.randomUUID.toString.substring(0, 8)
  /** Atomic counter for generating unique request IDs */
  final val requestCounter = new AtomicLong(0)
  final var connection: Disposable = _
  Assert.notNull(requestTimeout, "The requstTimeout can not be null")
  Assert.notNull(transport, "The transport can not be null")
  Assert.notNull(requestHandlers, "The requestHandlers can not be null")
  Assert.notNull(notificationHandlers, "The notificationHandlers can not be null")
  this.requestHandlers.putAll(requestHandlers)
  this.notificationHandlers.putAll(notificationHandlers)

  override def sendRequest[T](method: String, requestParams: AnyRef, typeRef: TypeReference[T]): Mono[T] = {
    val requestId = this.generateRequestId
    Mono.create[McpSchema.JSONRPCResponse]((sink: MonoSink[McpSchema.JSONRPCResponse]) => {
      this.pendingResponses.put(requestId, sink)
      val jsonrpcRequest = McpSchema.JSONRPCRequest(McpSchema.JSONRPC_VERSION, method, requestId, requestParams)
      this.transport.sendMessage(jsonrpcRequest).subscribe((v: Void) => {
      }, (error: Throwable) => {
        this.pendingResponses.remove(requestId)
        sink.error(error)
      })
    }).timeout(this.requestTimeout).handle((jsonRpcResponse: McpSchema.JSONRPCResponse, sink: SynchronousSink[T]) => {
      if (jsonRpcResponse.error != null) sink.error(new McpError(jsonRpcResponse.error))
      else if (typeRef.getType == classOf[Void]) sink.complete()
      else sink.next(this.transport.unmarshalFrom(jsonRpcResponse.result, typeRef))
    })
  }

  /**
   * Sends a JSON-RPC notification.
   *
   * @param method The method name for the notification
   * @param params The notification parameters
   * @return A Mono that completes when the notification is sent
   */
  override def sendNotification(method: String, params: util.Map[String, AnyRef]): Mono[Void] = {
    val jsonrpcNotification = McpSchema.JSONRPCNotification(McpSchema.JSONRPC_VERSION, method, params)
    this.transport.sendMessage(jsonrpcNotification)
  }

  /**
   * Generates a unique request ID in a non-blocking way. Combines a session-specific
   * prefix with an atomic counter to ensure uniqueness.
   *
   * @return A unique request ID string
   */
  def generateRequestId = this.sessionPrefix + "-" + this.requestCounter.getAndIncrement

  /**
   * Closes the session gracefully, allowing pending operations to complete.
   *
   * @return A Mono that completes when the session is closed
   */
  override def closeGracefully: Mono[Void] = {
    this.connection.dispose()
    transport.closeGracefully
  }

  /**
   * Closes the session immediately, potentially interrupting pending operations.
   */
  override def close(): Unit = {
    this.connection.dispose()
    transport.close()
  }

/**
 * Creates a new DefaultMcpSession with the specified configuration and handlers.
 *
 * @param requestTimeout       Duration to wait for responses
 * @param transport            Transport implementation for message exchange
 * @param requestHandlers      Map of method names to request handlers
 * @param notificationHandlers Map of method names to notification handlers
 */


  // TODO: consider mono.transformDeferredContextual where the Context contains
  // the
  // Observation associated with the individual message - it can be used to
  // create child Observation and emit it together with the message to the
  // consumer
  this.connection = this.transport.connect((mono: Mono[McpSchema.JSONRPCMessage]) =>
    mono.doOnNext((message: McpSchema.JSONRPCMessage) => {
        if (message.isInstanceOf[McpSchema.JSONRPCResponse]) {
          val response = message.asInstanceOf[McpSchema.JSONRPCResponse]
          DefaultMcpSession.logger.debug("Received Response: {}", response)
          val sink = pendingResponses.remove(response.id)
          if (sink == null) DefaultMcpSession.logger.warn("Unexpected response for unkown id {}", response.id)
          else sink.success(response)
        }
        else if (message.isInstanceOf[McpSchema.JSONRPCRequest]) {
          val request = message.asInstanceOf[McpSchema.JSONRPCRequest]
          DefaultMcpSession.logger.debug("Received request: {}", request)
          handleIncomingRequest(request).subscribe((response: McpSchema.JSONRPCResponse) => transport.sendMessage(response).subscribe, (error: Throwable) => {
            val errorResponse = new McpSchema.JSONRPCResponse(McpSchema.JSONRPC_VERSION, request.id, null, new JSONRPCResponse.JSONRPCError(McpSchema.ErrorCodes.INTERNAL_ERROR, error.getMessage, null))
            transport.sendMessage(errorResponse).subscribe
          })
        }
        else if (message.isInstanceOf[McpSchema.JSONRPCNotification]) {
          val notification = message.asInstanceOf[McpSchema.JSONRPCNotification]
          DefaultMcpSession.logger.debug("Received notification: {}", notification)
          handleIncomingNotification(notification).subscribe(null, (error: Throwable) => DefaultMcpSession.logger.error("Error handling notification: {}", error.getMessage))
        }
  })).subscribe




  /**
   * Handles an incoming JSON-RPC notification by routing it to the appropriate handler.
   *
   * @param notification The incoming JSON-RPC notification
   * @return A Mono that completes when the notification is processed
   */
  def handleIncomingNotification(notification: McpSchema.JSONRPCNotification):Mono[Void] =
    Mono.defer(() => {
      val handler = notificationHandlers.get(notification.method)
      if (handler == null) {
        DefaultMcpSession.logger.error("No handler registered for notification method: {}", notification.method)
        return Mono.empty
      }
      handler.handle(notification.params)
    })

  /**
   * Handles an incoming JSON-RPC request by routing it to the appropriate handler.
   *
   * @param request The incoming JSON-RPC request
   * @return A Mono containing the JSON-RPC response
   */
  def handleIncomingRequest(request: McpSchema.JSONRPCRequest):Mono[McpSchema.JSONRPCResponse] =
    Mono.defer(() => {
      type HandlerType = AnyRef //todo declare type HandlerType = to really type but unknow ?
      val handler: DefaultMcpSession.RequestHandler[HandlerType] = this.requestHandlers.get(request.method).asInstanceOf[DefaultMcpSession.RequestHandler[HandlerType]] // requestHandlers.get(request.method)
      if (handler == null) {
        val error = DefaultMcpSession.getMethodNotFoundError(request.method)
        return Mono.just(new McpSchema.JSONRPCResponse(McpSchema.JSONRPC_VERSION, request.id, null,
          JSONRPCResponse.JSONRPCError(McpSchema.ErrorCodes.METHOD_NOT_FOUND, error.message, error.data)))
      }

      def handlerMapFunc = (result: HandlerType) =>
        new McpSchema.JSONRPCResponse(McpSchema.JSONRPC_VERSION, request.id, result, null)

      handler.handle(request.params).map(handlerMapFunc.asJavaFunction).
        onErrorResume((error: Throwable) =>
          Mono.just(new McpSchema.JSONRPCResponse(McpSchema.JSONRPC_VERSION, request.id, null, JSONRPCResponse.JSONRPCError(McpSchema.ErrorCodes.INTERNAL_ERROR, error.getMessage, null)))) // TODO: add error message

      // through the data field
    })



  /**
   * Sends a JSON-RPC request and returns the response.
   *
   * @param <             T> The expected response type
   * @param method        The method name to call
   * @param requestParams The request parameters
   * @param typeRef       Type reference for response deserialization
   * @return A Mono containing the response
   */


}