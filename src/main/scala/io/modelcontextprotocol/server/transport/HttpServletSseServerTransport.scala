/*
* Copyright 2024 - 2024 the original author or authors.
*/
package io.modelcontextprotocol.server.transport

import com.fasterxml.jackson.core
import com.fasterxml.jackson.core.`type`.TypeReference
import reactor.core.publisher.MonoSink

import java.io.{BufferedReader, IOException, PrintWriter}
import java.util
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.function.Function
import scala.jdk.FunctionConverters.*
//type.TypeReference

import com.fasterxml.jackson.databind.ObjectMapper
import io.modelcontextprotocol.spec.{McpError, McpSchema, ServerMcpTransport}
import jakarta.servlet.annotation.WebServlet
import jakarta.servlet.http.{HttpServlet, HttpServletRequest, HttpServletResponse}
import jakarta.servlet.{AsyncContext, ServletException}
import org.slf4j.{Logger, LoggerFactory}
import reactor.core.publisher.Mono

/**
 * A Servlet-based implementation of the MCP HTTP with Server-Sent Events (SSE) transport
 * specification. This implementation provides similar functionality to
 * WebFluxSseServerTransport but uses the traditional Servlet API instead of WebFlux.
 *
 * <p>
 * The transport handles two types of endpoints:
 * <ul>
 * <li>SSE endpoint (/sse) - Establishes a long-lived connection for server-to-client
 * events</li>
 * <li>Message endpoint (configurable) - Handles client-to-server message requests</li>
 * </ul>
 *
 * <p>
 * Features:
 * <ul>
 * <li>Asynchronous message handling using Servlet 6.0 async support</li>
 * <li>Session management for multiple client connections</li>
 * <li>Graceful shutdown support</li>
 * <li>Error handling and response formatting</li>
 * </ul>
 *
 * @author Christian Tzolov
 * @author Alexandros Pappas
 * @see ServerMcpTransport
 * @see HttpServlet
 */
@WebServlet(asyncSupported = true) object HttpServletSseServerTransport {
  /** Logger for this class */
  private val logger = LoggerFactory.getLogger(classOf[HttpServletSseServerTransport])
  val UTF_8 = "UTF-8"
  val APPLICATION_JSON = "application/json"
  val FAILED_TO_SEND_ERROR_RESPONSE = "Failed to send error response: {}"
  /** Default endpoint path for SSE connections */
  val DEFAULT_SSE_ENDPOINT = "/sse"
  /** Event type for regular messages */
  val MESSAGE_EVENT_TYPE = "message"
  /** Event type for endpoint information */
  val ENDPOINT_EVENT_TYPE = "endpoint"

  /**
   * Represents a client connection session.
   * <p>
   * This class holds the necessary information about a client's SSE connection,
   * including its ID, async context, and output writer.
   */
  case class ClientSession(id: String, asyncContext: AsyncContext, writer: PrintWriter)
}
@WebServlet(asyncSupported = true) 
class HttpServletSseServerTransport(val objectMapper: ObjectMapper,
                                    val messageEndpoint: String,
                                    val sseEndpoint: String)extends HttpServlet with ServerMcpTransport {

/**
 * Creates a new HttpServletSseServerTransport instance with a custom SSE endpoint.
 *
 * @param objectMapper    The JSON object mapper to use for message
 *                        serialization/deserialization
 * @param messageEndpoint The endpoint path where clients will send their messages
 * @param sseEndpoint     The endpoint path where clients will establish SSE connections
 */
  
  /** Map of active client sessions, keyed by session ID */
  final val sessions = new ConcurrentHashMap[String, HttpServletSseServerTransport.ClientSession]
  /** Flag indicating if the transport is in the process of shutting down */
  final val isClosing = new AtomicBoolean(false)
  /** Handler for processing incoming messages */
  var connectHandler: Function[Mono[McpSchema.JSONRPCMessage], Mono[McpSchema.JSONRPCMessage]] = null

  /**
   * Creates a new HttpServletSseServerTransport instance with the default SSE endpoint.
   *
   * @param objectMapper    The JSON object mapper to use for message
   *                        serialization/deserialization
   * @param messageEndpoint The endpoint path where clients will send their messages
   */
  def this(objectMapper: ObjectMapper, messageEndpoint: String) = {
    this(objectMapper, messageEndpoint, HttpServletSseServerTransport.DEFAULT_SSE_ENDPOINT)
  }

  /**
   * Handles GET requests to establish SSE connections.
   * <p>
   * This method sets up a new SSE connection when a client connects to the SSE
   * endpoint. It configures the response headers for SSE, creates a new session, and
   * sends the initial endpoint information to the client.
   *
   * @param request  The HTTP servlet request
   * @param response The HTTP servlet response
   * @throws ServletException If a servlet-specific error occurs
   * @throws IOException      If an I/O error occurs
   */
  @throws[ServletException]
  @throws[IOException]
  override protected def doGet(request: HttpServletRequest, response: HttpServletResponse): Unit = {
    val pathInfo = request.getPathInfo
    if (!(sseEndpoint == pathInfo)) {
      response.sendError(HttpServletResponse.SC_NOT_FOUND)
      return
    }
    if (isClosing.get) {
      response.sendError(HttpServletResponse.SC_SERVICE_UNAVAILABLE, "Server is shutting down")
      return
    }
    response.setContentType("text/event-stream")
    response.setCharacterEncoding(HttpServletSseServerTransport.UTF_8)
    response.setHeader("Cache-Control", "no-cache")
    response.setHeader("Connection", "keep-alive")
    response.setHeader("Access-Control-Allow-Origin", "*")
    val sessionId = UUID.randomUUID.toString
    val asyncContext = request.startAsync
    asyncContext.setTimeout(0)
    val writer = response.getWriter
    val session = new HttpServletSseServerTransport.ClientSession(sessionId, asyncContext, writer)
    this.sessions.put(sessionId, session)
    // Send initial endpoint event
    this.sendEvent(writer, HttpServletSseServerTransport.ENDPOINT_EVENT_TYPE, messageEndpoint)
  }

  /**
   * Broadcasts a message to all connected clients.
   * <p>
   * This method serializes the message and sends it to all active client sessions. If a
   * client is disconnected, its session is removed.
   *
   * @param message The message to broadcast
   * @return A Mono that completes when the message has been sent to all clients
   */
  override def sendMessage(message: McpSchema.JSONRPCMessage): Mono[Void] = {
    if (sessions.isEmpty) {
      HttpServletSseServerTransport.logger.debug("No active sessions to broadcast message to")
      return Mono.empty
    }
    Mono.create((sink: MonoSink[Void]) => {
      try {
        val jsonText = objectMapper.writeValueAsString(message)
        sessions.values.forEach((session: HttpServletSseServerTransport.ClientSession) => {
          try this.sendEvent(session.writer, HttpServletSseServerTransport.MESSAGE_EVENT_TYPE, jsonText)
          catch {
            case e: IOException =>
              HttpServletSseServerTransport.logger.error("Failed to send message to session {}: {}", session.id, e.getMessage)
              removeSession(session)
          }
        })
        sink.success()
      } catch {
        case e: Exception =>
          HttpServletSseServerTransport.logger.error("Failed to process message: {}", e.getMessage)
          sink.error(McpError("Failed to process message: " + e.getMessage))
      }
    })
  }

  /**
   * Sets up the message handler for processing client requests.
   *
   * @param handler The function to process incoming messages and produce responses
   * @return A Mono that completes when the handler is set up
   */
  override def connect(handler: Function[Mono[McpSchema.JSONRPCMessage], Mono[McpSchema.JSONRPCMessage]]): Mono[Void] = {
    this.connectHandler = handler
    Mono.empty
  }

  /**
   * Handles POST requests for client messages.
   * <p>
   * This method processes incoming messages from clients, routes them through the
   * connect handler if configured, and sends back the appropriate response. It handles
   * error cases and formats error responses according to the MCP specification.
   *
   * @param request  The HTTP servlet request
   * @param response The HTTP servlet response
   * @throws ServletException If a servlet-specific error occurs
   * @throws IOException      If an I/O error occurs
   */
  @throws[ServletException]
  @throws[IOException]
  override protected def doPost(request: HttpServletRequest, response: HttpServletResponse): Unit = {
    if (isClosing.get) {
      response.sendError(HttpServletResponse.SC_SERVICE_UNAVAILABLE, "Server is shutting down")
      return
    }
    val pathInfo = request.getPathInfo
    if (!(messageEndpoint == pathInfo)) {
      response.sendError(HttpServletResponse.SC_NOT_FOUND)
      return
    }
    try {
      val reader = request.getReader
      val body = new StringBuilder
      var line: String = null
      while ({line = reader.readLine ; line != null}) 
        body.append(line)
      val message = McpSchema.deserializeJsonRpcMessage(objectMapper, body.toString)
      if (connectHandler != null) connectHandler.apply(Mono.just(message)).subscribe((responseMessage: McpSchema.JSONRPCMessage) => {
        try {
          response.setContentType(HttpServletSseServerTransport.APPLICATION_JSON)
          response.setCharacterEncoding(HttpServletSseServerTransport.UTF_8)
          val jsonResponse = objectMapper.writeValueAsString(responseMessage)
          val writer = response.getWriter
          writer.write(jsonResponse)
          writer.flush()
        } catch {
          case e: Exception =>
            HttpServletSseServerTransport.logger.error("Error sending response: {}", e.getMessage)
            try response.sendError(HttpServletResponse.SC_INTERNAL_SERVER_ERROR, "Error processing response: " + e.getMessage)
            catch {
              case ex: IOException =>
                HttpServletSseServerTransport.logger.error(HttpServletSseServerTransport.FAILED_TO_SEND_ERROR_RESPONSE, ex.getMessage)
            }
        }
      }, (error: Throwable) => {
        try {
          HttpServletSseServerTransport.logger.error("Error processing message: {}", error.getMessage)
          val mcpError = McpError(error.getMessage)
          response.setContentType(HttpServletSseServerTransport.APPLICATION_JSON)
          response.setCharacterEncoding(HttpServletSseServerTransport.UTF_8)
          response.setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR)
          val jsonError = objectMapper.writeValueAsString(mcpError)
          val writer = response.getWriter
          writer.write(jsonError)
          writer.flush()
        } catch {
          case e: IOException =>
            HttpServletSseServerTransport.logger.error(HttpServletSseServerTransport.FAILED_TO_SEND_ERROR_RESPONSE, e.getMessage)
            try response.sendError(HttpServletResponse.SC_INTERNAL_SERVER_ERROR, "Error sending error response: " + e.getMessage)
            catch {
              case ex: IOException =>
                HttpServletSseServerTransport.logger.error(HttpServletSseServerTransport.FAILED_TO_SEND_ERROR_RESPONSE, ex.getMessage)
            }
        }
      })
      else response.sendError(HttpServletResponse.SC_SERVICE_UNAVAILABLE, "No message handler configured")
    } catch {
      case e: Exception =>
        HttpServletSseServerTransport.logger.error("Invalid message format: {}", e.getMessage)
        try {
          val mcpError = McpError("Invalid message format: " + e.getMessage)
          response.setContentType(HttpServletSseServerTransport.APPLICATION_JSON)
          response.setCharacterEncoding(HttpServletSseServerTransport.UTF_8)
          response.setStatus(HttpServletResponse.SC_BAD_REQUEST)
          val jsonError = objectMapper.writeValueAsString(mcpError)
          val writer = response.getWriter
          writer.write(jsonError)
          writer.flush()
        } catch {
          case ex: IOException =>
            HttpServletSseServerTransport.logger.error(HttpServletSseServerTransport.FAILED_TO_SEND_ERROR_RESPONSE, ex.getMessage)
            response.sendError(HttpServletResponse.SC_BAD_REQUEST, "Invalid message format")
        }
    }
  }

  /**
   * Closes the transport.
   * <p>
   * This implementation delegates to the super class's close method.
   */
  override def close(): Unit = { 
      this.close()
//    ServerMcpTransport.super.close()
//    super.ServerMcpTransport.close()
  }


  /**
   * Unmarshals data from one type to another using the object mapper.
   *
   * @param <       T> The target type
   * @param data    The source data
   * @param typeRef The type reference for the target type
   * @return The unmarshaled data
   */
  override def unmarshalFrom[T](data: AnyRef, typeRef: TypeReference[T]): T = objectMapper.convertValue(data, typeRef)

  /**
   * Initiates a graceful shutdown of the transport.
   * <p>
   * This method marks the transport as closing and closes all active client sessions.
   * New connection attempts will be rejected during shutdown.
   *
   * @return A Mono that completes when all sessions have been closed
   */
  override def closeGracefully: Mono[Void] = {
    isClosing.set(true)
    HttpServletSseServerTransport.logger.debug("Initiating graceful shutdown with {} active sessions", sessions.size)
    Mono.create((sink: MonoSink[Void]) => {
      sessions.values.forEach(this.removeSession)
      sink.success()
    })
  }

  /**
   * Sends an SSE event to a client.
   *
   * @param writer    The writer to send the event through
   * @param eventType The type of event (message or endpoint)
   * @param data      The event data
   * @throws IOException If an error occurs while writing the event
   */
  @throws[IOException]
  private def sendEvent(writer: PrintWriter, eventType: String, data: String): Unit = {
    writer.write("event: " + eventType + "\n")
    writer.write("data: " + data + "\n\n")
    writer.flush()
    if (writer.checkError) throw new IOException("Client disconnected")
  }

  /**
   * Removes a client session and completes its async context.
   *
   * @param session The session to remove
   */
  private def removeSession(session: HttpServletSseServerTransport.ClientSession): Unit = {
    sessions.remove(session.id)
    session.asyncContext.complete()
  }

  /**
   * Cleans up resources when the servlet is being destroyed.
   * <p>
   * This method ensures a graceful shutdown by closing all client connections before
   * calling the parent's destroy method.
   */
  override def destroy(): Unit = {
    closeGracefully.block
    super.destroy()
  }
}