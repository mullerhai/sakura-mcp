/*
 * Copyright 2024 - 2024 the original author or authors.
 */
package io.modelcontextprotocol.client.transport

import com.fasterxml.jackson.core
import com.fasterxml.jackson.core.`type`.TypeReference
import com.fasterxml.jackson.databind.ObjectMapper
import io.modelcontextprotocol.client.transport.FlowSseClient.SseEvent
import io.modelcontextprotocol.spec.McpSchema.JSONRPCMessage
import io.modelcontextprotocol.spec.{ClientMcpTransport, McpError, McpSchema}
import io.modelcontextprotocol.util.Assert
import org.slf4j.{Logger, LoggerFactory}
import reactor.core.publisher.Mono

import java.io.IOException
import java.net.URI
import java.net.http.{HttpClient, HttpRequest, HttpResponse}
import java.time.Duration
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.{CompletableFuture, CountDownLatch, TimeUnit}
import java.util.function.Function

/**
 * Server-Sent Events (SSE) implementation of the
 * {@link io.modelcontextprotocol.spec.McpTransport} that follows the MCP HTTP with SSE
 * transport specification, using Java's HttpClient.
 *
 * <p>
 * This transport implementation establishes a bidirectional communication channel between
 * client and server using SSE for server-to-client messages and HTTP POST requests for
 * client-to-server messages. The transport:
 * <ul>
 * <li>Establishes an SSE connection to receive server messages</li>
 * <li>Handles endpoint discovery through SSE events</li>
 * <li>Manages message serialization/deserialization using Jackson</li>
 * <li>Provides graceful connection termination</li>
 * </ul>
 *
 * <p>
 * The transport supports two types of SSE events:
 * <ul>
 * <li>'endpoint' - Contains the URL for sending client messages</li>
 * <li>'message' - Contains JSON-RPC message payload</li>
 * </ul>
 *
 * @author Christian Tzolov
 * @see io.modelcontextprotocol.spec.McpTransport
 * @see io.modelcontextprotocol.spec.ClientMcpTransport
 */
object HttpClientSseClientTransport {
  private val logger = LoggerFactory.getLogger(classOf[HttpClientSseClientTransport])
  /** SSE event type for JSON-RPC messages */
  private val MESSAGE_EVENT_TYPE = "message"
  /** SSE event type for endpoint discovery */
  private val ENDPOINT_EVENT_TYPE = "endpoint"
  /** Default SSE endpoint path */
  private val SSE_ENDPOINT = "/sse"
}

class HttpClientSseClientTransport(clientBuilder: HttpClient.Builder,

                                   /** Base URI for the MCP server */
                                   private val baseUri: String,

                                   /** JSON object mapper for message serialization/deserialization */
                                   protected var objectMapper: ObjectMapper)

/**
 * Creates a new transport instance with custom HTTP client builder and object mapper.
 *
 * @param clientBuilder the HTTP client builder to use
 * @param baseUri       the base URI of the MCP server
 * @param objectMapper  the object mapper for JSON serialization/deserialization
 * @throws IllegalArgumentException if objectMapper or clientBuilder is null
 */
  extends ClientMcpTransport {
  Assert.notNull(objectMapper, "ObjectMapper must not be null")
  Assert.hasText(baseUri, "baseUri must not be empty")
  Assert.notNull(clientBuilder, "clientBuilder must not be null")

  /** SSE client for handling server-sent events. Uses the /sse endpoint */
  final private var sseClient: FlowSseClient = null
  /**
   * HTTP client for sending messages to the server. Uses HTTP POST over the message
   * endpoint
   */
  final private var httpClient: HttpClient = null

  this.httpClient = clientBuilder.connectTimeout(Duration.ofSeconds(10)).build
  this.sseClient = new FlowSseClient(this.httpClient)
  /** Flag indicating if the transport is in closing state */
  @volatile private var isClosing = false
  /** Latch for coordinating endpoint discovery */
  private val closeLatch = new CountDownLatch(1)
  /** Holds the discovered message endpoint URL */
  final private val messageEndpoint = new AtomicReference[String]
  /** Holds the SSE connection future */
  final private val connectionFuture = new AtomicReference[CompletableFuture[Void]]

  /**
   * Creates a new transport instance with default HTTP client and object mapper.
   *
   * @param baseUri the base URI of the MCP server
   */
  def this(baseUri: String) ={
    this(HttpClient.newBuilder, baseUri, new ObjectMapper)
  }

  /**
   * Establishes the SSE connection with the server and sets up message handling.
   *
   * <p>
   * This method:
   * <ul>
   * <li>Initiates the SSE connection</li>
   * <li>Handles endpoint discovery events</li>
   * <li>Processes incoming JSON-RPC messages</li>
   * </ul>
   *
   * @param handler the function to process received JSON-RPC messages
   * @return a Mono that completes when the connection is established
   */
  override def connect(handler: Function[Mono[McpSchema.JSONRPCMessage], Mono[McpSchema.JSONRPCMessage]]): Mono[Void] = {
    val future = new CompletableFuture[Void]
    connectionFuture.set(future)
    sseClient.subscribe(this.baseUri + HttpClientSseClientTransport.SSE_ENDPOINT, new FlowSseClient.SseEventHandler() {
      override def onEvent(event: FlowSseClient.SseEvent): Unit = {
        if (isClosing) return
        try if (HttpClientSseClientTransport.ENDPOINT_EVENT_TYPE == event.`type`) {
          val endpoint = event.data
          messageEndpoint.set(endpoint)
          closeLatch.countDown()
          future.complete(null)
        }
        else if (HttpClientSseClientTransport.MESSAGE_EVENT_TYPE == event.`type`) {
          val message = McpSchema.deserializeJsonRpcMessage(objectMapper, event.data)
          handler.apply(Mono.just(message)).subscribe
        }
        else HttpClientSseClientTransport.logger.error("Received unrecognized SSE event type: {}", event.`type`)
        catch {
          case e: IOException =>
            HttpClientSseClientTransport.logger.error("Error processing SSE event", e)
            future.completeExceptionally(e)
        }
      }

      override def onError(error: Throwable): Unit = {
        if (!isClosing) {
          HttpClientSseClientTransport.logger.error("SSE connection error", error)
          future.completeExceptionally(error)
        }
      }
    })
    Mono.fromFuture(future)
  }

  /**
   * Sends a JSON-RPC message to the server.
   *
   * <p>
   * This method waits for the message endpoint to be discovered before sending the
   * message. The message is serialized to JSON and sent as an HTTP POST request.
   *
   * @param message the JSON-RPC message to send
   * @return a Mono that completes when the message is sent
   * @throws McpError if the message endpoint is not available or the wait times out
   */
  override def sendMessage(message: McpSchema.JSONRPCMessage): Mono[Void] = {
    if (isClosing) return Mono.empty
    try if (!closeLatch.await(10, TimeUnit.SECONDS)) return Mono.error(McpError("Failed to wait for the message endpoint"))
    catch {
      case e: InterruptedException =>
        return Mono.error(McpError("Failed to wait for the message endpoint"))
    }
    val endpoint = messageEndpoint.get
    if (endpoint == null) return Mono.error(McpError("No message endpoint available"))
    try {
      val jsonText = this.objectMapper.writeValueAsString(message)
      val request = HttpRequest.newBuilder.uri(URI.create(this.baseUri + endpoint)).header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(jsonText)).build
      Mono.fromFuture(httpClient.sendAsync(request, HttpResponse.BodyHandlers.discarding).thenAccept((response: HttpResponse[Void]) => {
        if (response.statusCode != 200 && response.statusCode != 201 && response.statusCode != 202 && response.statusCode != 206) HttpClientSseClientTransport.logger.error("Error sending message: {}", response.statusCode)
      }))
    } catch {
      case e: IOException =>
        if (!isClosing) return Mono.error(new RuntimeException("Failed to serialize message", e))
        Mono.empty
    }
  }

  /**
   * Gracefully closes the transport connection.
   *
   * <p>
   * Sets the closing flag and cancels any pending connection future. This prevents new
   * messages from being sent and allows ongoing operations to complete.
   *
   * @return a Mono that completes when the closing process is initiated
   */
  override def closeGracefully: Mono[Void] = Mono.fromRunnable(() => {
    isClosing = true
    val future = connectionFuture.get
    if (future != null && !future.isDone) future.cancel(true)
  })

  /**
   * Unmarshals data to the specified type using the configured object mapper.
   *
   * @param data    the data to unmarshal
   * @param typeRef the type reference for the target type
   * @param <       T> the target type
   * @return the unmarshalled object
   */
  override def unmarshalFrom[T](data: AnyRef, typeRef: TypeReference[T]): T = this.objectMapper.convertValue(data, typeRef)
}