/*
 * Copyright 2024-2024 the original author or authors.
 */
package torch.modelcontextprotocol.client

import torch.modelcontextprotocol.spec.McpSchema.*
import reactor.core.publisher.Mono
import torch.modelcontextprotocol.spec.{ClientMcpTransport, McpSchema}
import torch.modelcontextprotocol.util.Assert

import java.time.Duration
import java.util
import java.util.function.Consumer
import scala.collection.mutable
import scala.collection.mutable.ListBuffer

/**
 * Factory class for creating Model Context Protocol (MCP) clients. MCP is a protocol that
 * enables AI models to interact with external tools and resources through a standardized
 * interface.
 *
 * <p>
 * This class serves as the main entry point for establishing connections with MCP
 * servers, implementing the client-side of the MCP specification. The protocol follows a
 * client-server architecture where:
 * <ul>
 * <li>The client (this implementation) initiates connections and sends requests
 * <li>The server responds to requests and provides access to tools and resources
 * <li>Communication occurs through a transport layer (e.g., stdio, SSE) using JSON-RPC
 * 2.0
 * </ul>
 *
 * <p>
 * The class provides factory methods to create either:
 * <ul>
 * <li>{@link McpAsyncClient} for non-blocking operations with CompletableFuture responses
 * <li>{@link McpSyncClient} for blocking operations with direct responses
 * </ul>
 *
 * <p>
 * Example of creating a basic synchronous client: <pre>{@code
 * McpClient.sync(transport)
 * .requestTimeout(Duration.ofSeconds(5))
 * .build();
 * }</pre>
 *
 * Example of creating a basic asynchronous client: <pre>{@code
 * McpClient.async(transport)
 * .requestTimeout(Duration.ofSeconds(5))
 * .build();
 * }</pre>
 *
 * <p>
 * Example with advanced asynchronous configuration: <pre>{@code
 * McpClient.async(transport)
 * .requestTimeout(Duration.ofSeconds(10))
 * .capabilities(new ClientCapabilities(...))
 * .clientInfo(new Implementation("My Client", "1.0.0"))
 * .roots(new Root("file://workspace", "Workspace Files"))
 * .toolsChangeConsumer(tools -> Mono.fromRunnable(() -> System.out.println("Tools updated: " + tools)))
 * .resourcesChangeConsumer(resources -> Mono.fromRunnable(() -> System.out.println("Resources updated: " + resources)))
 * .promptsChangeConsumer(prompts -> Mono.fromRunnable(() -> System.out.println("Prompts updated: " + prompts)))
 * .loggingConsumer(message -> Mono.fromRunnable(() -> System.out.println("Log message: " + message)))
 * .build();
 * }</pre>
 *
 * <p>
 * The client supports:
 * <ul>
 * <li>Tool discovery and invocation
 * <li>Resource access and management
 * <li>Prompt template handling
 * <li>Real-time updates through change consumers
 * <li>Custom sampling strategies
 * <li>Structured logging with severity levels
 * </ul>
 *
 * <p>
 * The client supports structured logging through the MCP logging utility:
 * <ul>
 * <li>Eight severity levels from DEBUG to EMERGENCY
 * <li>Optional logger name categorization
 * <li>Configurable logging consumers
 * <li>Server-controlled minimum log level
 * </ul>
 *
 * @author Christian Tzolov
 * @author Dariusz Jędrzejczyk
 * @see McpAsyncClient
 * @see McpSyncClient
 * @see McpTransport
 */
object McpClient {
  /**
   * Start building a synchronous MCP client with the specified transport layer. The
   * synchronous MCP client provides blocking operations. Synchronous clients wait for
   * each operation to complete before returning, making them simpler to use but
   * potentially less performant for concurrent operations. The transport layer handles
   * the low-level communication between client and server using protocols like stdio or
   * Server-Sent Events (SSE).
   *
   * @param transport The transport layer implementation for MCP communication. Common
   *                  implementations include {@code StdioClientTransport} for stdio-based communication
   *                  and {@code SseClientTransport} for SSE-based communication.
   * @return A new builder instance for configuring the client
   * @throws IllegalArgumentException if transport is null
   */
  def sync(transport: ClientMcpTransport) = new McpClient.SyncSpec(transport)

  /**
   * Start building an asynchronous MCP client with the specified transport layer. The
   * asynchronous MCP client provides non-blocking operations. Asynchronous clients
   * return reactive primitives (Mono/Flux) immediately, allowing for concurrent
   * operations and reactive programming patterns. The transport layer handles the
   * low-level communication between client and server using protocols like stdio or
   * Server-Sent Events (SSE).
   *
   * @param transport The transport layer implementation for MCP communication. Common
   *                  implementations include {@code StdioClientTransport} for stdio-based communication
   *                  and {@code SseClientTransport} for SSE-based communication.
   * @return A new builder instance for configuring the client
   * @throws IllegalArgumentException if transport is null
   */
  def async(transport: ClientMcpTransport) = new McpClient.AsyncSpec(transport)

  /**
   * Synchronous client specification. This class follows the builder pattern to provide
   * a fluent API for setting up clients with custom configurations.
   *
   * <p>
   * The builder supports configuration of:
   * <ul>
   * <li>Transport layer for client-server communication
   * <li>Request timeouts for operation boundaries
   * <li>Client capabilities for feature negotiation
   * <li>Client implementation details for version tracking
   * <li>Root URIs for resource access
   * <li>Change notification handlers for tools, resources, and prompts
   * <li>Custom message sampling logic
   * </ul>
   */
  class SyncSpec ( val transport: ClientMcpTransport) {
    Assert.notNull(transport, "Transport must not be null")
    private var requestTimeout = Duration.ofSeconds(20) // Default timeout
    private var capabilities: McpSchema.ClientCapabilities = null
    private var clientInfo = new McpSchema.Implementation("Java SDK MCP Client", "1.0.0")
    final private val roots = new mutable.HashMap[String, McpSchema.Root]
    final private val toolsChangeConsumers = new ListBuffer[Consumer[List[McpSchema.Tool]]]
    final private val resourcesChangeConsumers = new ListBuffer[Consumer[List[McpSchema.Resource]]]
    final private val promptsChangeConsumers = new ListBuffer[Consumer[List[McpSchema.Prompt]]]
    final private val loggingConsumers = new ListBuffer[Consumer[McpSchema.LoggingMessageNotification]]
    private var samplingHandler: Function[McpSchema.CreateMessageRequest, McpSchema.CreateMessageResult] = null

    /**
     * Sets the duration to wait for server responses before timing out requests. This
     * timeout applies to all requests made through the client, including tool calls,
     * resource access, and prompt operations.
     *
     * @param requestTimeout The duration to wait before timing out requests. Must not
     *                       be null.
     * @return This builder instance for method chaining
     * @throws IllegalArgumentException if requestTimeout is null
     */
    def requestTimeout(requestTimeout: Duration): McpClient.SyncSpec = {
      Assert.notNull(requestTimeout, "Request timeout must not be null")
      this.requestTimeout = requestTimeout
      this
    }

    /**
     * Sets the client capabilities that will be advertised to the server during
     * connection initialization. Capabilities define what features the client
     * supports, such as tool execution, resource access, and prompt handling.
     *
     * @param capabilities The client capabilities configuration. Must not be null.
     * @return This builder instance for method chaining
     * @throws IllegalArgumentException if capabilities is null
     */
    def capabilities(capabilities: McpSchema.ClientCapabilities): McpClient.SyncSpec = {
      Assert.notNull(capabilities, "Capabilities must not be null")
      this.capabilities = capabilities
      this
    }

    /**
     * Sets the client implementation information that will be shared with the server
     * during connection initialization. This helps with version compatibility and
     * debugging.
     *
     * @param clientInfo The client implementation details including name and version.
     *                   Must not be null.
     * @return This builder instance for method chaining
     * @throws IllegalArgumentException if clientInfo is null
     */
    def clientInfo(clientInfo: McpSchema.Implementation): McpClient.SyncSpec = {
      Assert.notNull(clientInfo, "Client info must not be null")
      this.clientInfo = clientInfo
      this
    }

    /**
     * Sets the root URIs that this client can access. Roots define the base URIs for
     * resources that the client can request from the server. For example, a root
     * might be "file://workspace" for accessing workspace files.
     *
     * @param roots A list of root definitions. Must not be null.
     * @return This builder instance for method chaining
     * @throws IllegalArgumentException if roots is null
     */
    def roots(roots: List[McpSchema.Root]): McpClient.SyncSpec = {
      Assert.notNull(roots, "Roots must not be null")
//      import scala.collection.JavaConversions.*
      for (root <- roots) {
        this.roots.put(root.uri, root)
      }
      this
    }

    /**
     * Sets the root URIs that this client can access, using a varargs parameter for
     * convenience. This is an alternative to {@link # roots ( List )}.
     *
     * @param roots An array of root definitions. Must not be null.
     * @return This builder instance for method chaining
     * @throws IllegalArgumentException if roots is null
     * @see #roots(List)
     */
    def roots(roots: McpSchema.Root*): McpClient.SyncSpec = {
      Assert.notNull(roots, "Roots must not be null")
      for (root <- roots) {
        this.roots.put(root.uri, root)
      }
      this
    }

    /**
     * Sets a custom sampling handler for processing message creation requests. The
     * sampling handler can modify or validate messages before they are sent to the
     * server, enabling custom processing logic.
     *
     * @param samplingHandler A function that processes message requests and returns
     *                        results. Must not be null.
     * @return This builder instance for method chaining
     * @throws IllegalArgumentException if samplingHandler is null
     */
    def sampling(samplingHandler: Function[McpSchema.CreateMessageRequest, McpSchema.CreateMessageResult]): McpClient.SyncSpec = {
      Assert.notNull(samplingHandler, "Sampling handler must not be null")
      this.samplingHandler = samplingHandler
      this
    }

    /**
     * Adds a consumer to be notified when the available tools change. This allows the
     * client to react to changes in the server's tool capabilities, such as tools
     * being added or removed.
     *
     * @param toolsChangeConsumer A consumer that receives the updated list of
     *                            available tools. Must not be null.
     * @return This builder instance for method chaining
     * @throws IllegalArgumentException if toolsChangeConsumer is null
     */
    def toolsChangeConsumer(toolsChangeConsumer: Consumer[List[McpSchema.Tool]]): McpClient.SyncSpec = {
      Assert.notNull(toolsChangeConsumer, "Tools change consumer must not be null")
      this.toolsChangeConsumers.append(toolsChangeConsumer)
      this
    }

    /**
     * Adds a consumer to be notified when the available resources change. This allows
     * the client to react to changes in the server's resource availability, such as
     * files being added or removed.
     *
     * @param resourcesChangeConsumer A consumer that receives the updated list of
     *                                available resources. Must not be null.
     * @return This builder instance for method chaining
     * @throws IllegalArgumentException if resourcesChangeConsumer is null
     */
    def resourcesChangeConsumer(resourcesChangeConsumer: Consumer[List[McpSchema.Resource]]): McpClient.SyncSpec = {
      Assert.notNull(resourcesChangeConsumer, "Resources change consumer must not be null")
      this.resourcesChangeConsumers.append(resourcesChangeConsumer)
      this
    }

    /**
     * Adds a consumer to be notified when the available prompts change. This allows
     * the client to react to changes in the server's prompt templates, such as new
     * templates being added or existing ones being modified.
     *
     * @param promptsChangeConsumer A consumer that receives the updated list of
     *                              available prompts. Must not be null.
     * @return This builder instance for method chaining
     * @throws IllegalArgumentException if promptsChangeConsumer is null
     */
    def promptsChangeConsumer(promptsChangeConsumer: Consumer[List[McpSchema.Prompt]]): McpClient.SyncSpec = {
      Assert.notNull(promptsChangeConsumer, "Prompts change consumer must not be null")
      this.promptsChangeConsumers.append(promptsChangeConsumer)
      this
    }

    /**
     * Adds a consumer to be notified when logging messages are received from the
     * server. This allows the client to react to log messages, such as warnings or
     * errors, that are sent by the server.
     *
     * @param loggingConsumer A consumer that receives logging messages. Must not be
     *                        null.
     * @return This builder instance for method chaining
     */
    def loggingConsumer(loggingConsumer: Consumer[McpSchema.LoggingMessageNotification]): McpClient.SyncSpec = {
      Assert.notNull(loggingConsumer, "Logging consumer must not be null")
      this.loggingConsumers.append(loggingConsumer)
      this
    }

    /**
     * Adds multiple consumers to be notified when logging messages are received from
     * the server. This allows the client to react to log messages, such as warnings
     * or errors, that are sent by the server.
     *
     * @param loggingConsumers A list of consumers that receive logging messages. Must
     *                         not be null.
     * @return This builder instance for method chaining
     */
    def loggingConsumers(loggingConsumers: List[Consumer[McpSchema.LoggingMessageNotification]]): McpClient.SyncSpec = {
      Assert.notNull(loggingConsumers, "Logging consumers must not be null")
      this.loggingConsumers.addAll(loggingConsumers)
      this
    }

    /**
     * Create an instance of {@link McpSyncClient} with the provided configurations or
     * sensible defaults.
     *
     * @return a new instance of {@link McpSyncClient}.
     */
    def build: McpSyncClient = {
      val syncFeatures = McpClientFeatures.Sync(this.clientInfo, this.capabilities, this.roots, this.toolsChangeConsumers.toList, this.resourcesChangeConsumers.toList, this.promptsChangeConsumers.toList, this.loggingConsumers.toList, this.samplingHandler)
      val asyncFeatures = McpClientFeatures.Async.fromSync(syncFeatures)
      new McpSyncClient(new McpAsyncClient(transport, this.requestTimeout, asyncFeatures))
    }
  }

  /**
   * Asynchronous client specification. This class follows the builder pattern to
   * provide a fluent API for setting up clients with custom configurations.
   *
   * <p>
   * The builder supports configuration of:
   * <ul>
   * <li>Transport layer for client-server communication
   * <li>Request timeouts for operation boundaries
   * <li>Client capabilities for feature negotiation
   * <li>Client implementation details for version tracking
   * <li>Root URIs for resource access
   * <li>Change notification handlers for tools, resources, and prompts
   * <li>Custom message sampling logic
   * </ul>
   */
  class AsyncSpec (private val transport: ClientMcpTransport) {
    Assert.notNull(transport, "Transport must not be null")
    private var requestTimeout = Duration.ofSeconds(20) // Default timeout
    private var capabilities: McpSchema.ClientCapabilities = null
    private var clientInfo = new McpSchema.Implementation("Spring AI MCP Client", "0.3.1")
    final private val roots = new mutable.HashMap[String, McpSchema.Root]
    final private val toolsChangeConsumers = new ListBuffer[Function[List[McpSchema.Tool], Mono[Void]]]
    final private val resourcesChangeConsumers = new ListBuffer[Function[List[McpSchema.Resource], Mono[Void]]]
    final private val promptsChangeConsumers = new ListBuffer[Function[List[McpSchema.Prompt], Mono[Void]]]
    final private val loggingConsumers = new ListBuffer[Function[McpSchema.LoggingMessageNotification, Mono[Void]]]
    private var samplingHandler: Function[McpSchema.CreateMessageRequest, Mono[McpSchema.CreateMessageResult]] = null

    /**
     * Sets the duration to wait for server responses before timing out requests. This
     * timeout applies to all requests made through the client, including tool calls,
     * resource access, and prompt operations.
     *
     * @param requestTimeout The duration to wait before timing out requests. Must not
     *                       be null.
     * @return This builder instance for method chaining
     * @throws IllegalArgumentException if requestTimeout is null
     */
    def requestTimeout(requestTimeout: Duration): McpClient.AsyncSpec = {
      Assert.notNull(requestTimeout, "Request timeout must not be null")
      this.requestTimeout = requestTimeout
      this
    }

    /**
     * Sets the client capabilities that will be advertised to the server during
     * connection initialization. Capabilities define what features the client
     * supports, such as tool execution, resource access, and prompt handling.
     *
     * @param capabilities The client capabilities configuration. Must not be null.
     * @return This builder instance for method chaining
     * @throws IllegalArgumentException if capabilities is null
     */
    def capabilities(capabilities: McpSchema.ClientCapabilities): McpClient.AsyncSpec = {
      Assert.notNull(capabilities, "Capabilities must not be null")
      this.capabilities = capabilities
      this
    }

    /**
     * Sets the client implementation information that will be shared with the server
     * during connection initialization. This helps with version compatibility and
     * debugging.
     *
     * @param clientInfo The client implementation details including name and version.
     *                   Must not be null.
     * @return This builder instance for method chaining
     * @throws IllegalArgumentException if clientInfo is null
     */
    def clientInfo(clientInfo: McpSchema.Implementation): McpClient.AsyncSpec = {
      Assert.notNull(clientInfo, "Client info must not be null")
      this.clientInfo = clientInfo
      this
    }

    /**
     * Sets the root URIs that this client can access. Roots define the base URIs for
     * resources that the client can request from the server. For example, a root
     * might be "file://workspace" for accessing workspace files.
     *
     * @param roots A list of root definitions. Must not be null.
     * @return This builder instance for method chaining
     * @throws IllegalArgumentException if roots is null
     */
    def roots(roots: List[McpSchema.Root]): McpClient.AsyncSpec = {
      Assert.notNull(roots, "Roots must not be null")
//      import scala.collection.JavaConversions.*
      for (root <- roots) {
        this.roots.put(root.uri, root)
      }
      this
    }

    /**
     * Sets the root URIs that this client can access, using a varargs parameter for
     * convenience. This is an alternative to {@link # roots ( List )}.
     *
     * @param roots An array of root definitions. Must not be null.
     * @return This builder instance for method chaining
     * @throws IllegalArgumentException if roots is null
     * @see #roots(List)
     */
    def roots(roots: McpSchema.Root*): McpClient.AsyncSpec = {
      Assert.notNull(roots, "Roots must not be null")
      for (root <- roots) {
        this.roots.put(root.uri, root)
      }
      this
    }

    /**
     * Sets a custom sampling handler for processing message creation requests. The
     * sampling handler can modify or validate messages before they are sent to the
     * server, enabling custom processing logic.
     *
     * @param samplingHandler A function that processes message requests and returns
     *                        results. Must not be null.
     * @return This builder instance for method chaining
     * @throws IllegalArgumentException if samplingHandler is null
     */
    def sampling(samplingHandler: Function[McpSchema.CreateMessageRequest, Mono[McpSchema.CreateMessageResult]]): McpClient.AsyncSpec = {
      Assert.notNull(samplingHandler, "Sampling handler must not be null")
      this.samplingHandler = samplingHandler
      this
    }

    /**
     * Adds a consumer to be notified when the available tools change. This allows the
     * client to react to changes in the server's tool capabilities, such as tools
     * being added or removed.
     *
     * @param toolsChangeConsumer A consumer that receives the updated list of
     *                            available tools. Must not be null.
     * @return This builder instance for method chaining
     * @throws IllegalArgumentException if toolsChangeConsumer is null
     */
    def toolsChangeConsumer(toolsChangeConsumer: Function[List[McpSchema.Tool], Mono[Void]]): McpClient.AsyncSpec = {
      Assert.notNull(toolsChangeConsumer, "Tools change consumer must not be null")
      this.toolsChangeConsumers.append(toolsChangeConsumer)
      this
    }

    /**
     * Adds a consumer to be notified when the available resources change. This allows
     * the client to react to changes in the server's resource availability, such as
     * files being added or removed.
     *
     * @param resourcesChangeConsumer A consumer that receives the updated list of
     *                                available resources. Must not be null.
     * @return This builder instance for method chaining
     * @throws IllegalArgumentException if resourcesChangeConsumer is null
     */
    def resourcesChangeConsumer(resourcesChangeConsumer: Function[List[McpSchema.Resource], Mono[Void]]): McpClient.AsyncSpec = {
      Assert.notNull(resourcesChangeConsumer, "Resources change consumer must not be null")
      this.resourcesChangeConsumers.append(resourcesChangeConsumer)
      this
    }

    /**
     * Adds a consumer to be notified when the available prompts change. This allows
     * the client to react to changes in the server's prompt templates, such as new
     * templates being added or existing ones being modified.
     *
     * @param promptsChangeConsumer A consumer that receives the updated list of
     *                              available prompts. Must not be null.
     * @return This builder instance for method chaining
     * @throws IllegalArgumentException if promptsChangeConsumer is null
     */
    def promptsChangeConsumer(promptsChangeConsumer: Function[List[McpSchema.Prompt], Mono[Void]]): McpClient.AsyncSpec = {
      Assert.notNull(promptsChangeConsumer, "Prompts change consumer must not be null")
      this.promptsChangeConsumers.append(promptsChangeConsumer)
      this
    }

    /**
     * Adds a consumer to be notified when logging messages are received from the
     * server. This allows the client to react to log messages, such as warnings or
     * errors, that are sent by the server.
     *
     * @param loggingConsumer A consumer that receives logging messages. Must not be
     *                        null.
     * @return This builder instance for method chaining
     */
    def loggingConsumer(loggingConsumer: Function[McpSchema.LoggingMessageNotification, Mono[Void]]): McpClient.AsyncSpec = {
      Assert.notNull(loggingConsumer, "Logging consumer must not be null")
      this.loggingConsumers.append(loggingConsumer)
      this
    }

    /**
     * Adds multiple consumers to be notified when logging messages are received from
     * the server. This allows the client to react to log messages, such as warnings
     * or errors, that are sent by the server.
     *
     * @param loggingConsumers A list of consumers that receive logging messages. Must
     *                         not be null.
     * @return This builder instance for method chaining
     */
    def loggingConsumers(loggingConsumers: List[Function[McpSchema.LoggingMessageNotification, Mono[Void]]]): McpClient.AsyncSpec = {
      Assert.notNull(loggingConsumers, "Logging consumers must not be null")
      this.loggingConsumers.addAll(loggingConsumers)
      this
    }

    /**
     * Create an instance of {@link McpAsyncClient} with the provided configurations
     * or sensible defaults.
     *
     * @return a new instance of {@link McpAsyncClient}.
     */
    def build = new McpAsyncClient(this.transport, this.requestTimeout, new McpClientFeatures.Async(this.clientInfo, this.capabilities, this.roots, this.toolsChangeConsumers.toList, this.resourcesChangeConsumers.toList, this.promptsChangeConsumers.toList, this.loggingConsumers.toList, this.samplingHandler))
  }
}

trait McpClient {}