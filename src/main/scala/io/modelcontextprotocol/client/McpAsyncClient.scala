/*
 * Copyright 2024-2024 the original author or authors.
 */
package io.modelcontextprotocol.client

import com.fasterxml.jackson.core
import com.fasterxml.jackson.core.`type`.TypeReference

import java.time.Duration
import java.util
import java.util.concurrent.ConcurrentHashMap
import java.util.function.Function

//type.TypeReference
import scala.jdk.CollectionConverters._
import io.modelcontextprotocol.spec.*
import io.modelcontextprotocol.spec.DefaultMcpSession.{NotificationHandler, RequestHandler}
import io.modelcontextprotocol.spec.McpSchema.*
import io.modelcontextprotocol.util.{Assert, Utils}
import org.slf4j.{Logger, LoggerFactory}
import reactor.core.publisher.{Flux, Mono}

/**
 * The Model Context Protocol (MCP) client implementation that provides asynchronous
 * communication with MCP servers using Project Reactor's Mono and Flux types.
 *
 * <p>
 * This client implements the MCP specification, enabling AI models to interact with
 * external tools and resources through a standardized interface. Key features include:
 * <ul>
 * <li>Asynchronous communication using reactive programming patterns
 * <li>Tool discovery and invocation for server-provided functionality
 * <li>Resource access and management with URI-based addressing
 * <li>Prompt template handling for standardized AI interactions
 * <li>Real-time notifications for tools, resources, and prompts changes
 * <li>Structured logging with configurable severity levels
 * <li>Message sampling for AI model interactions
 * </ul>
 *
 * <p>
 * The client follows a lifecycle:
 * <ol>
 * <li>Initialization - Establishes connection and negotiates capabilities
 * <li>Normal Operation - Handles requests and notifications
 * <li>Graceful Shutdown - Ensures clean connection termination
 * </ol>
 *
 * <p>
 * This implementation uses Project Reactor for non-blocking operations, making it
 * suitable for high-throughput scenarios and reactive applications. All operations return
 * Mono or Flux types that can be composed into reactive pipelines.
 *
 * @author Dariusz Jędrzejczyk
 * @author Christian Tzolov
 * @see McpClient
 * @see McpSchema
 * @see DefaultMcpSession
 */
object McpAsyncClient {
  private val logger = LoggerFactory.getLogger(classOf[McpAsyncClient])
  private val VOID_TYPE_REFERENCE = new TypeReference[Void]() {}
  // --------------------------
  // Tools
  // --------------------------
  private val CALL_TOOL_RESULT_TYPE_REF = new TypeReference[McpSchema.CallToolResult]() {}
  private val LIST_TOOLS_RESULT_TYPE_REF = new TypeReference[McpSchema.ListToolsResult]() {}
  // --------------------------
  // Resources
  // --------------------------
  private val LIST_RESOURCES_RESULT_TYPE_REF = new TypeReference[McpSchema.ListResourcesResult]() {}
  private val READ_RESOURCE_RESULT_TYPE_REF = new TypeReference[McpSchema.ReadResourceResult]() {}
  private val LIST_RESOURCE_TEMPLATES_RESULT_TYPE_REF = new TypeReference[McpSchema.ListResourceTemplatesResult]() {}
  // --------------------------
  // Prompts
  // --------------------------
  private val LIST_PROMPTS_RESULT_TYPE_REF = new TypeReference[McpSchema.ListPromptsResult]() {}
  private val GET_PROMPT_RESULT_TYPE_REF = new TypeReference[McpSchema.GetPromptResult]() {}
}

class McpAsyncClient (private val transport: McpTransport, requestTimeout: Duration, features: McpClientFeatures.Async){
  Assert.notNull(transport, "Transport must not be null")
  Assert.notNull(requestTimeout, "Request timeout must not be null")
  final private var mcpSession: DefaultMcpSession = null
  /**
   * Client capabilities.
   */
  final private var clientCapabilities: McpSchema.ClientCapabilities = null
  /**
   * Client implementation information.
   */
  final private var clientInfo: McpSchema.Implementation = null
  /**
   * Server capabilities.
   */
  private var serverCapabilities: McpSchema.ServerCapabilities = null
  /**
   * Server implementation information.
   */
  private var serverInfo: McpSchema.Implementation = null
  /**
   * Roots define the boundaries of where servers can operate within the filesystem,
   * allowing them to understand which directories and files they have access to.
   * Servers can request the list of roots from supporting clients and receive
   * notifications when that list changes.
   */
  final private var roots: ConcurrentHashMap[String, McpSchema.Root] = null
  val notificationHandlers = new ConcurrentHashMap[String, DefaultMcpSession.NotificationHandler]
  // Tools Change Notification
  val toolsChangeConsumersFinal = new util.ArrayList[Function[util.List[McpSchema.Tool], Mono[Void]]]
  this.clientInfo = features.clientInfo
  this.clientCapabilities = features.clientCapabilities
  this.roots = new ConcurrentHashMap[String, McpSchema.Root](features.roots)
/**
 * Create a new McpAsyncClient with the given transport and session request-response
 * timeout.
 *
 * @param transport      the transport to use.
 * @param requestTimeout the session request-response timeout.
 * @param features       the MCP Client supported features.
 */ 

  // Request Handlers
  val requestHandlers = new ConcurrentHashMap[String, DefaultMcpSession.RequestHandler[_]]
  // Roots List Request Handler
  if (this.clientCapabilities.roots != null) requestHandlers.put(McpSchema.METHOD_ROOTS_LIST, rootsListRequestHandler)
  // Sampling Handler
  if (this.clientCapabilities.sampling != null) {
    if (features.samplingHandler == null) throw new McpError("Sampling handler must not be null when client capabilities include sampling")
    this.samplingHandler = features.samplingHandler
    requestHandlers.put(McpSchema.METHOD_SAMPLING_CREATE_MESSAGE, samplingCreateMessageHandler)
  }
  // Notification Handlers

  toolsChangeConsumersFinal.add((notification: util.List[McpSchema.Tool]) => Mono.fromRunnable(() => McpAsyncClient.logger.debug("Tools changed: {}", notification)))
  if (!Utils.isEmpty(features.toolsChangeConsumers)) toolsChangeConsumersFinal.addAll(features.toolsChangeConsumers)
  val asyncNotificationHandler: AnyRef => Mono[_$1] = asyncToolsChangeNotificationHandler(toolsChangeConsumersFinal)
  notificationHandlers.put(McpSchema.METHOD_NOTIFICATION_TOOLS_LIST_CHANGED,asyncNotificationHandler)
  // Resources Change Notification
  val resourcesChangeConsumersFinal = new util.ArrayList[Function[util.List[McpSchema.Resource], Mono[Void]]]
  resourcesChangeConsumersFinal.add((notification: util.List[McpSchema.Resource]) => Mono.fromRunnable(() => McpAsyncClient.logger.debug("Resources changed: {}", notification)))
  if (!Utils.isEmpty(features.resourcesChangeConsumers)) resourcesChangeConsumersFinal.addAll(features.resourcesChangeConsumers)
  val kkk: AnyRef => Mono[_$1] = asyncResourcesChangeNotificationHandler(resourcesChangeConsumersFinal)
  notificationHandlers.put(McpSchema.METHOD_NOTIFICATION_RESOURCES_LIST_CHANGED,kkk )
  // Prompts Change Notification
  val promptsChangeConsumersFinal = new util.ArrayList[Function[util.List[McpSchema.Prompt], Mono[Void]]]
  promptsChangeConsumersFinal.add((notification: util.List[McpSchema.Prompt]) => Mono.fromRunnable(() => McpAsyncClient.logger.debug("Prompts changed: {}", notification)))
  if (!Utils.isEmpty(features.promptsChangeConsumers)) promptsChangeConsumersFinal.addAll(features.promptsChangeConsumers)
  val jjj: AnyRef => Mono[McpSchema.Prompt] =asyncPromptsChangeNotificationHandler(promptsChangeConsumersFinal)
  notificationHandlers.put(McpSchema.METHOD_NOTIFICATION_PROMPTS_LIST_CHANGED,jjj )
  // Utility Logging Notification
  val loggingConsumersFinal = new util.ArrayList[Function[McpSchema.LoggingMessageNotification, Mono[Void]]]
  loggingConsumersFinal.add((notification: McpSchema.LoggingMessageNotification) => Mono.fromRunnable(() => McpAsyncClient.logger.debug("Logging: {}", notification)))
  if (!Utils.isEmpty(features.loggingConsumers)) loggingConsumersFinal.addAll(features.loggingConsumers)
  val lll: AnyRef => Mono[McpSchema.LoggingMessageNotification] =asyncLoggingNotificationHandler(loggingConsumersFinal)  
  // asyncLoggingNotificationHandler(loggingConsumersFinal)
  notificationHandlers.put(McpSchema.METHOD_NOTIFICATION_MESSAGE,lll)
  this.mcpSession = new DefaultMcpSession(requestTimeout, transport, requestHandlers, notificationHandlers)
  /**
   * The MCP session implementation that manages bidirectional JSON-RPC communication
   * between clients and servers.
   */

  /**
   * MCP provides a standardized way for servers to request LLM sampling ("completions"
   * or "generations") from language models via clients. This flow allows clients to
   * maintain control over model access, selection, and permissions while enabling
   * servers to leverage AI capabilities—with no server API keys necessary. Servers can
   * request text or image-based interactions and optionally include context from MCP
   * servers in their prompts.
   */
  var samplingHandler: Function[McpSchema.CreateMessageRequest, Mono[McpSchema.CreateMessageResult]] = null
  /**
   * Supported protocol versions.
   */
  var protocolVersions = util.List.of(McpSchema.LATEST_PROTOCOL_VERSION)

  /**
   * The initialization phase MUST be the first interaction between client and server.
   * During this phase, the client and server:
   * <ul>
   * <li>Establish protocol version compatibility</li>
   * <li>Exchange and negotiate capabilities</li>
   * <li>Share implementation details</li>
   * </ul>
   * <br/>
   * The client MUST initiate this phase by sending an initialize request containing:
   * <ul>
   * <li>The protocol version the client supports</li>
   * <li>The client's capabilities</li>
   * <li>Client implementation information</li>
   * </ul>
   *
   * The server MUST respond with its own capabilities and information:
   * {@link McpSchema.ServerCapabilities}. <br/>
   * After successful initialization, the client MUST send an initialized notification
   * to indicate it is ready to begin normal operations.
   *
   * <br/>
   *
   * <a href=
   * "https://github.com/modelcontextprotocol/specification/blob/main/docs/specification/basic/lifecycle.md#initialization">Initialization
   * Spec</a>
   *
   * @return the initialize result.
   */
  // --------------------------
  // Lifecycle
  // --------------------------
  def initialize: Mono[McpSchema.InitializeResult] = {
    val latestVersion = this.protocolVersions.get(this.protocolVersions.size - 1)
    val initializeRequest  = new McpSchema.InitializeRequest(// @formatter:off
    latestVersion, this.clientCapabilities, this.clientInfo) // @formatter:on
    val result = this.mcpSession.sendRequest(McpSchema.METHOD_INITIALIZE, initializeRequest, new TypeReference[McpSchema.InitializeResult]() {})
    result.flatMap((initializeResult: McpSchema.InitializeResult) => {
      this.serverCapabilities = initializeResult.capabilities
      this.serverInfo = initializeResult.serverInfo
      McpAsyncClient.logger.info("Server response with Protocol: {}, Capabilities: {}, Info: {} and Instructions {}", initializeResult.protocolVersion, initializeResult.capabilities, initializeResult.serverInfo, initializeResult.instructions)
      if (!this.protocolVersions.contains(initializeResult.protocolVersion)) Mono.error(new McpError("Unsupported protocol version from the server: " + initializeResult.protocolVersion))
      else this.mcpSession.sendNotification(McpSchema.METHOD_NOTIFICATION_INITIALIZED, null).thenReturn(initializeResult)
    })
  }

  /**
   * Get the server capabilities that define the supported features and functionality.
   *
   * @return The server capabilities
   */
  def getServerCapabilities: McpSchema.ServerCapabilities = this.serverCapabilities

  /**
   * Get the server implementation information.
   *
   * @return The server implementation details
   */
  def getServerInfo: McpSchema.Implementation = this.serverInfo

  /**
   * Check if the client-server connection is initialized.
   *
   * @return true if the client-server connection is initialized
   */
  def isInitialized: Boolean = this.serverCapabilities != null

  /**
   * Get the client capabilities that define the supported features and functionality.
   *
   * @return The client capabilities
   */
  def getClientCapabilities: McpSchema.ClientCapabilities = this.clientCapabilities

  /**
   * Get the client implementation information.
   *
   * @return The client implementation details
   */
  def getClientInfo: McpSchema.Implementation = this.clientInfo

  /**
   * Closes the client connection immediately.
   */
  def close(): Unit = {
    this.mcpSession.close()
  }

  /**
   * Gracefully closes the client connection.
   *
   * @return A Mono that completes when the connection is closed
   */
  def closeGracefully: Mono[Void] = this.mcpSession.closeGracefully

  /**
   * Sends a ping request to the server.
   *
   * @return A Mono that completes with the server's ping response
   */
  // --------------------------
  // Basic Utilites
  // --------------------------
  def ping: Mono[AnyRef] = this.mcpSession.sendRequest(McpSchema.METHOD_PING, null, new TypeReference[AnyRef]() {})

  /**
   * Adds a new root to the client's root list.
   *
   * @param root The root to add
   * @return A Mono that completes when the root is added and notifications are sent
   */
  // --------------------------
  // Roots
  // --------------------------
  def addRoot(root: McpSchema.Root): Mono[Void] = {
    if (root == null) return Mono.error(new McpError("Root must not be null"))
    if (this.clientCapabilities.roots == null) return Mono.error(new McpError("Client must be configured with roots capabilities"))
    if (this.roots.containsKey(root.uri)) return Mono.error(new McpError("Root with uri '" + root.uri + "' already exists"))
    this.roots.put(root.uri, root)
    McpAsyncClient.logger.debug("Added root: {}", root)
    if (this.clientCapabilities.roots.listChanged) return this.rootsListChangedNotification
    Mono.empty
  }

  /**
   * Removes a root from the client's root list.
   *
   * @param rootUri The URI of the root to remove
   * @return A Mono that completes when the root is removed and notifications are sent
   */
  def removeRoot(rootUri: String): Mono[Void] = {
    if (rootUri == null) return Mono.error(new McpError("Root uri must not be null"))
    if (this.clientCapabilities.roots == null) return Mono.error(new McpError("Client must be configured with roots capabilities"))
    val removed = this.roots.remove(rootUri)
    if (removed != null) {
      McpAsyncClient.logger.debug("Removed Root: {}", rootUri)
      if (this.clientCapabilities.roots.listChanged) return this.rootsListChangedNotification
      return Mono.empty
    }
    Mono.error(new McpError("Root with uri '" + rootUri + "' not found"))
  }

  /**
   * Manually sends a roots/list_changed notification. The addRoot and removeRoot
   * methods automatically send the roots/list_changed notification.
   *
   * @return A Mono that completes when the notification is sent
   */
  def rootsListChangedNotification: Mono[Void] = this.mcpSession.sendNotification(McpSchema.METHOD_NOTIFICATION_ROOTS_LIST_CHANGED)

  def rootsListRequestHandler:RequestHandler[McpSchema.ListRootsResult] = (params: AnyRef) => {
    @SuppressWarnings(Array("unused")) val request = transport.unmarshalFrom(params, new TypeReference[McpSchema.PaginatedRequest]() {})
    val roots = this.roots.values.stream.toList
    Mono.just(McpSchema.ListRootsResult(roots))
  }

  // --------------------------
  // Sampling
  // --------------------------
  def samplingCreateMessageHandler:RequestHandler[CreateMessageResult] = (params: AnyRef) => {
    val request = transport.unmarshalFrom(params, new TypeReference[McpSchema.CreateMessageRequest]() {})
    this.samplingHandler.apply(request)
  }

  /**
   * Calls a tool provided by the server. Tools enable servers to expose executable
   * functionality that can interact with external systems, perform computations, and
   * take actions in the real world.
   *
   * @param callToolRequest The request containing: - name: The name of the tool to call
   *                        (must match a tool name from tools/list) - arguments: Arguments that conform to the
   *                        tool's input schema
   * @return A Mono that emits the tool execution result containing: - content: List of
   *         content items (text, images, or embedded resources) representing the tool's output
   *         - isError: Boolean indicating if the execution failed (true) or succeeded
   *         (false/absent)
   */
  def callTool(callToolRequest: McpSchema.CallToolRequest): Mono[McpSchema.CallToolResult] = {
    if (!this.isInitialized) return Mono.error(new McpError("Client must be initialized before calling tools"))
    if (this.serverCapabilities.tools == null) return Mono.error(new McpError("Server does not provide tools capability"))
    this.mcpSession.sendRequest(McpSchema.METHOD_TOOLS_CALL, callToolRequest, McpAsyncClient.CALL_TOOL_RESULT_TYPE_REF)
  }

  /**
   * Retrieves the list of all tools provided by the server.
   *
   * @return A Mono that emits the list of tools result containing: - tools: List of
   *         available tools, each with a name, description, and input schema - nextCursor:
   *         Optional cursor for pagination if more tools are available
   */
  def listTools: Mono[McpSchema.ListToolsResult] = this.listTools(null)

  /**
   * Retrieves a paginated list of tools provided by the server.
   *
   * @param cursor Optional pagination cursor from a previous list request
   * @return A Mono that emits the list of tools result containing: - tools: List of
   *         available tools, each with a name, description, and input schema - nextCursor:
   *         Optional cursor for pagination if more tools are available
   */
  def listTools(cursor: String): Mono[McpSchema.ListToolsResult] = {
    if (!this.isInitialized) return Mono.error(new McpError("Client must be initialized before listing tools"))
    if (this.serverCapabilities.tools == null) return Mono.error(new McpError("Server does not provide tools capability"))
    this.mcpSession.sendRequest(McpSchema.METHOD_TOOLS_LIST, new McpSchema.PaginatedRequest(cursor), McpAsyncClient.LIST_TOOLS_RESULT_TYPE_REF)
  }

  /**
   * Creates a notification handler for tools/list_changed notifications from the
   * server. When the server's available tools change, it sends a notification to inform
   * connected clients. This handler automatically fetches the updated tool list and
   * distributes it to all registered consumers.
   *
   * @param toolsChangeConsumers List of consumers that will be notified when the tools
   *                             list changes. Each consumer receives the complete updated list of tools.
   * @return A NotificationHandler that processes tools/list_changed notifications by:
   *         1. Fetching the current list of tools from the server 2. Distributing the updated
   *         list to all registered consumers 3. Handling any errors that occur during this
   *         process
   */
  def asyncToolsChangeNotificationHandler(toolsChangeConsumers: util.List[Function[util.List[McpSchema.Tool], Mono[Void]]]) = {
    // TODO: params are not used yet
    (params: AnyRef) =>
      listTools.flatMap((listToolsResult: McpSchema.ListToolsResult) => Flux.fromIterable(toolsChangeConsumers).flatMap((consumer: Function[util.List[McpSchema.Tool], Mono[Void]]) => consumer.apply(listToolsResult.tools)).onErrorResume((error: Throwable) => {
        McpAsyncClient.logger.error("Error handling tools list change notification", error)
        Mono.empty
      }).`then`)
  }

  /**
   * Send a resources/list request.
   *
   * @return A Mono that completes with the list of resources result
   */
  def listResources: Mono[McpSchema.ListResourcesResult] = this.listResources(null)

  /**
   * Send a resources/list request.
   *
   * @param cursor the cursor for pagination
   * @return A Mono that completes with the list of resources result
   */
  def listResources(cursor: String): Mono[McpSchema.ListResourcesResult] = {
    if (!this.isInitialized) return Mono.error(new McpError("Client must be initialized before listing resources"))
    if (this.serverCapabilities.resources == null) return Mono.error(new McpError("Server does not provide the resources capability"))
    this.mcpSession.sendRequest(McpSchema.METHOD_RESOURCES_LIST, new McpSchema.PaginatedRequest(cursor), McpAsyncClient.LIST_RESOURCES_RESULT_TYPE_REF)
  }

  /**
   * Send a resources/read request.
   *
   * @param resource the resource to read
   * @return A Mono that completes with the resource content
   */
  def readResource(resource: McpSchema.Resource): Mono[McpSchema.ReadResourceResult] = this.readResource(new McpSchema.ReadResourceRequest(resource.uri))

  /**
   * Send a resources/read request.
   *
   * @param readResourceRequest the read resource request
   * @return A Mono that completes with the resource content
   */
  def readResource(readResourceRequest: McpSchema.ReadResourceRequest): Mono[McpSchema.ReadResourceResult] = {
    if (!this.isInitialized) return Mono.error(new McpError("Client must be initialized before reading resources"))
    if (this.serverCapabilities.resources == null) return Mono.error(new McpError("Server does not provide the resources capability"))
    this.mcpSession.sendRequest(McpSchema.METHOD_RESOURCES_READ, readResourceRequest, McpAsyncClient.READ_RESOURCE_RESULT_TYPE_REF)
  }

  /**
   * Resource templates allow servers to expose parameterized resources using URI
   * templates. Arguments may be auto-completed through the completion API.
   *
   * Request a list of resource templates the server has.
   *
   * @return A Mono that completes with the list of resource templates result
   */
  def listResourceTemplates: Mono[McpSchema.ListResourceTemplatesResult] = this.listResourceTemplates(null)

  /**
   * Resource templates allow servers to expose parameterized resources using URI
   * templates. Arguments may be auto-completed through the completion API.
   *
   * Request a list of resource templates the server has.
   *
   * @param cursor the cursor for pagination
   * @return A Mono that completes with the list of resource templates result
   */
  def listResourceTemplates(cursor: String): Mono[McpSchema.ListResourceTemplatesResult] = {
    if (!this.isInitialized) return Mono.error(new McpError("Client must be initialized before listing resource templates"))
    if (this.serverCapabilities.resources == null) return Mono.error(new McpError("Server does not provide the resources capability"))
    this.mcpSession.sendRequest(McpSchema.METHOD_RESOURCES_TEMPLATES_LIST, new McpSchema.PaginatedRequest(cursor), McpAsyncClient.LIST_RESOURCE_TEMPLATES_RESULT_TYPE_REF)
  }

  /**
   * Subscriptions. The protocol supports optional subscriptions to resource changes.
   * Clients can subscribe to specific resources and receive notifications when they
   * change.
   *
   * Send a resources/subscribe request.
   *
   * @param subscribeRequest the subscribe request contains the uri of the resource to
   *                         subscribe to
   * @return A Mono that completes when the subscription is complete
   */
  def subscribeResource(subscribeRequest: McpSchema.SubscribeRequest): Mono[Void] = this.mcpSession.sendRequest(McpSchema.METHOD_RESOURCES_SUBSCRIBE, subscribeRequest, McpAsyncClient.VOID_TYPE_REFERENCE)

  /**
   * Send a resources/unsubscribe request.
   *
   * @param unsubscribeRequest the unsubscribe request contains the uri of the resource
   *                           to unsubscribe from
   * @return A Mono that completes when the unsubscription is complete
   */
  def unsubscribeResource(unsubscribeRequest: McpSchema.UnsubscribeRequest): Mono[Void] = this.mcpSession.sendRequest(McpSchema.METHOD_RESOURCES_UNSUBSCRIBE, unsubscribeRequest, McpAsyncClient.VOID_TYPE_REFERENCE)

  def asyncResourcesChangeNotificationHandler(resourcesChangeConsumers: util.List[Function[util.List[McpSchema.Resource], Mono[Void]]]) = 
    (params: AnyRef) => listResources.flatMap((listResourcesResult: McpSchema.ListResourcesResult) =>
      Flux.fromIterable(resourcesChangeConsumers).flatMap((consumer: Function[util.List[McpSchema.Resource], Mono[Void]]) =>
        consumer.apply(listResourcesResult.resources)).onErrorResume((error: Throwable) => {
        McpAsyncClient.logger.error("Error handling resources list change notification", error)
        Mono.empty}).`then`)

  /**
   * List all available prompts.
   *
   * @return A Mono that completes with the list of prompts result
   */
  def listPrompts: Mono[McpSchema.ListPromptsResult] = this.listPrompts(null)

  /**
   * List all available prompts.
   *
   * @param cursor the cursor for pagination
   * @return A Mono that completes with the list of prompts result
   */
  def listPrompts(cursor: String): Mono[McpSchema.ListPromptsResult] = this.mcpSession.sendRequest(McpSchema.METHOD_PROMPT_LIST, new McpSchema.PaginatedRequest(cursor), McpAsyncClient.LIST_PROMPTS_RESULT_TYPE_REF)

  /**
   * Get a prompt by its id.
   *
   * @param getPromptRequest the get prompt request
   * @return A Mono that completes with the get prompt result
   */
  def getPrompt(getPromptRequest: McpSchema.GetPromptRequest): Mono[McpSchema.GetPromptResult] = this.mcpSession.sendRequest(McpSchema.METHOD_PROMPT_GET, getPromptRequest, McpAsyncClient.GET_PROMPT_RESULT_TYPE_REF)

  def asyncPromptsChangeNotificationHandler(promptsChangeConsumers: util.List[Function[util.List[McpSchema.Prompt], Mono[Void]]]) = 
    (params: AnyRef) => 
      listPrompts
        .flatMap((listPromptsResult: McpSchema.ListPromptsResult) => 
          Flux.fromIterable(promptsChangeConsumers).
            flatMap((consumer: Function[util.List[McpSchema.Prompt], Mono[Void]]) => 
            consumer.apply(listPromptsResult.prompts)).onErrorResume((error: Throwable) => {
            McpAsyncClient.logger.error("Error handling prompts list change notification", error)
            Mono.empty
          }).`then`)

  /**
   * Create a notification handler for logging notifications from the server. This
   * handler automatically distributes logging messages to all registered consumers.
   *
   * @param loggingConsumers List of consumers that will be notified when a logging
   *                         message is received. Each consumer receives the logging message notification.
   * @return A NotificationHandler that processes log notifications by distributing the
   *         message to all registered consumers
   */
  // --------------------------
  // Logging
  // --------------------------
  def asyncLoggingNotificationHandler(loggingConsumers: util.List[Function[McpSchema.LoggingMessageNotification, Mono[Void]]]) = (params: AnyRef) => {
    val loggingMessageNotification = transport.unmarshalFrom(params, new TypeReference[McpSchema.LoggingMessageNotification]() {})
    Flux.fromIterable(loggingConsumers).flatMap((consumer: Function[McpSchema.LoggingMessageNotification, Mono[Void]]) => consumer.apply(loggingMessageNotification)).`then`
  }

  /**
   * Client can set the minimum logging level it wants to receive from the server.
   *
   * @param loggingLevel the min logging level
   */
  def setLoggingLevel(loggingLevel: McpSchema.LoggingLevel): Mono[Void] = {
    Assert.notNull(loggingLevel, "Logging level must not be null")
    val levelName = this.transport.unmarshalFrom(loggingLevel, new TypeReference[String]() {})
    val params :util.Map[String,AnyRef]= util.Map.of("level", levelName)
    this.mcpSession.sendNotification(McpSchema.METHOD_LOGGING_SET_LEVEL, params)
  }

  /**
   * This method is package-private and used for test only. Should not be called by user
   * code.
   *
   * @param protocolVersions the Client supported protocol versions.
   */
  def setProtocolVersions(protocolVersions: util.List[String]): Unit = {
    this.protocolVersions = protocolVersions
  }
}