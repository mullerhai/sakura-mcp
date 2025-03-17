/*
 * Copyright 2024-2024 the original author or authors.
 */
package io.modelcontextprotocol.server

import com.fasterxml.jackson.core
import com.fasterxml.jackson.core.`type`.TypeReference
import io.modelcontextprotocol.spec.DefaultMcpSession.RequestHandler

import java.time.Duration
import java.util
import java.util.Optional
import java.util.concurrent.{ConcurrentHashMap, CopyOnWriteArrayList}
import java.util.function.Function
import scala.jdk.FunctionConverters.*
//type.TypeReference

import io.modelcontextprotocol.spec.DefaultMcpSession.NotificationHandler
import io.modelcontextprotocol.spec.McpSchema.*
import io.modelcontextprotocol.spec.{DefaultMcpSession, McpError, McpSchema, ServerMcpTransport}
import io.modelcontextprotocol.util.Utils
import org.slf4j.{Logger, LoggerFactory}
import reactor.core.publisher.{Flux, Mono}

/**
 * The Model Context Protocol (MCP) server implementation that provides asynchronous
 * communication using Project Reactor's Mono and Flux types.
 *
 * <p>
 * This server implements the MCP specification, enabling AI models to expose tools,
 * resources, and prompts through a standardized interface. Key features include:
 * <ul>
 * <li>Asynchronous communication using reactive programming patterns
 * <li>Dynamic tool registration and management
 * <li>Resource handling with URI-based addressing
 * <li>Prompt template management
 * <li>Real-time client notifications for state changes
 * <li>Structured logging with configurable severity levels
 * <li>Support for client-side AI model sampling
 * </ul>
 *
 * <p>
 * The server follows a lifecycle:
 * <ol>
 * <li>Initialization - Accepts client connections and negotiates capabilities
 * <li>Normal Operation - Handles client requests and sends notifications
 * <li>Graceful Shutdown - Ensures clean connection termination
 * </ol>
 *
 * <p>
 * This implementation uses Project Reactor for non-blocking operations, making it
 * suitable for high-throughput scenarios and reactive applications. All operations return
 * Mono or Flux types that can be composed into reactive pipelines.
 *
 * <p>
 * The server supports runtime modification of its capabilities through methods like
 * {@link # addTool}, {@link # addResource}, and {@link # addPrompt}, automatically notifying
 * connected clients of changes when configured to do so.
 *
 * @author Christian Tzolov
 * @author Dariusz Jędrzejczyk
 * @see McpServer
 * @see McpSchema
 * @see DefaultMcpSession
 */
object McpAsyncServer {
  private val logger = LoggerFactory.getLogger(classOf[McpAsyncServer])
  private val LIST_ROOTS_RESULT_TYPE_REF = new TypeReference[McpSchema.ListRootsResult]() {}
  // ---------------------------------------
  // Sampling
  // ---------------------------------------
  private val CREATE_MESSAGE_RESULT_TYPE_REF = new TypeReference[McpSchema.CreateMessageResult]() {}
}

class McpAsyncServer (transport: ServerMcpTransport, features: McpServerFeatures.Async) {
  final var mcpSession: DefaultMcpSession = null
  final var serverCapabilities: McpSchema.ServerCapabilities = null
  final var serverInfo: McpSchema.Implementation = null
  var clientCapabilities: McpSchema.ClientCapabilities = null
  var clientInfo: McpSchema.Implementation = null
  /**
   * Thread-safe list of tool handlers that can be modified at runtime.
   */
  final val tools = new CopyOnWriteArrayList[McpServerFeatures.AsyncToolRegistration]
  final val resourceTemplates = new CopyOnWriteArrayList[McpSchema.ResourceTemplate]
  final val resources = new ConcurrentHashMap[String, McpServerFeatures.AsyncResourceRegistration]
  final val prompts = new ConcurrentHashMap[String, McpServerFeatures.AsyncPromptRegistration]
  var minLoggingLevel = LoggingLevel.DEBUG
  /**
   * Supported protocol versions.
   */
  private var protocolVersions = util.List.of(McpSchema.LATEST_PROTOCOL_VERSION)

  /**
   * Retrieves the list of all roots provided by the client.
   *
   * @return A Mono that emits the list of roots result.
   */
  def listRoots: Mono[McpSchema.ListRootsResult] = this.listRoots(null)

  /**
   * Retrieves a paginated list of roots provided by the server.
   *
   * @param cursor Optional pagination cursor from a previous list request
   * @return A Mono that emits the list of roots result containing
   */
  def listRoots(cursor: String): Mono[McpSchema.ListRootsResult] = this.mcpSession.sendRequest(McpSchema.METHOD_ROOTS_LIST, new McpSchema.PaginatedRequest(cursor), McpAsyncServer.LIST_ROOTS_RESULT_TYPE_REF)

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
   * Gracefully closes the server, allowing any in-progress operations to complete.
   *
   * @return A Mono that completes when the server has been closed
   */
  def closeGracefully: Mono[Void] = this.mcpSession.closeGracefully

  /**
   * Close the server immediately.
   */
  def close(): Unit = {
    this.mcpSession.close()
  }

  /**
   * Remove a tool handler at runtime.
   *
   * @param toolName The name of the tool handler to remove
   * @return Mono that completes when clients have been notified of the change
   */
  def removeTool(toolName: String): Mono[Void] = {
    if (toolName == null) return Mono.error(McpError("Tool name must not be null"))
    if (this.serverCapabilities.tools == null) return Mono.error(McpError("Server must be configured with tool capabilities"))
    Mono.defer(() => {
        val removed = this.tools.removeIf((toolRegistration: McpServerFeatures.AsyncToolRegistration) => toolRegistration.tool.name == toolName)
        if (removed) {
          McpAsyncServer.logger.debug("Removed tool handler: {}", toolName)
          if (this.serverCapabilities.tools.listChanged) return notifyToolsListChanged
          return Mono.empty
        }
      Mono.error(McpError("Tool with name '" + toolName + "' not found"))
    })
  }


  /**
   * Create a new McpAsyncServer with the given transport and capabilities.
   *
   * @param mcpTransport The transport layer implementation for MCP communication.
   * @param features     The MCP server supported features.
   */
  //{
  this.serverInfo = features.serverInfo
  this.serverCapabilities = features.serverCapabilities
  this.tools.addAll(features.tools)
  this.resources.putAll(features.resources)
  this.resourceTemplates.addAll(features.resourceTemplates)
  this.prompts.putAll(features.prompts)
  val requestHandlers = new ConcurrentHashMap[String, DefaultMcpSession.RequestHandler[_]]
  // Initialize request handlers for standard MCP methods
  requestHandlers.put(McpSchema.METHOD_INITIALIZE, asyncInitializeRequestHandler)
  // Ping MUST respond with an empty data, but not NULL response.
  requestHandlers.put(McpSchema.METHOD_PING, (params: AnyRef) => Mono.just(""))
  // Add tools API handlers if the tool capability is enabled
  if (this.serverCapabilities.tools != null) {
    requestHandlers.put(McpSchema.METHOD_TOOLS_LIST, toolsListRequestHandler)
    requestHandlers.put(McpSchema.METHOD_TOOLS_CALL, toolsCallRequestHandler)
  }
  // Add resources API handlers if provided
  if (this.serverCapabilities.resources != null) {
    requestHandlers.put(McpSchema.METHOD_RESOURCES_LIST, resourcesListRequestHandler)
    requestHandlers.put(McpSchema.METHOD_RESOURCES_READ, resourcesReadRequestHandler)
    requestHandlers.put(McpSchema.METHOD_RESOURCES_TEMPLATES_LIST, resourceTemplateListRequestHandler)
  }
  // Add prompts API handlers if provider exists
  if (this.serverCapabilities.prompts != null) {
    requestHandlers.put(McpSchema.METHOD_PROMPT_LIST, promptsListRequestHandler)
    requestHandlers.put(McpSchema.METHOD_PROMPT_GET, promptsGetRequestHandler)
  }
  // Add logging API handlers if the logging capability is enabled
  if (this.serverCapabilities.logging != null) requestHandlers.put(McpSchema.METHOD_LOGGING_SET_LEVEL, setLoggerRequestHandler)
  val notificationHandlers = new ConcurrentHashMap[String, DefaultMcpSession.NotificationHandler]
  notificationHandlers.put(McpSchema.METHOD_NOTIFICATION_INITIALIZED, (params: AnyRef) => Mono.empty)
  var rootsChangeConsumers: util.List[Function[util.List[McpSchema.Root], Mono[Void]]] = features.rootsChangeConsumers
  notificationHandlers.put(McpSchema.METHOD_NOTIFICATION_ROOTS_LIST_CHANGED, asyncRootsListChangedNotificationHandler(rootsChangeConsumers))
  this.mcpSession = new DefaultMcpSession(Duration.ofSeconds(10), transport, requestHandlers, notificationHandlers)

  def rootIsEmpty: util.List[Root] => Mono[Void] = (roots: util.List[McpSchema.Root]) => Mono.fromRunnable(() => McpAsyncServer.logger.warn("Roots list changed notification, but no consumers provided. Roots list changed: {}", roots))


  if (Utils.isEmpty(rootsChangeConsumers)) then rootsChangeConsumers = util.List.of(rootIsEmpty.asJavaFunction)
  //      (roots: util.List[McpSchema.Root]) => Mono.fromRunnable(() => McpAsyncServer.logger.warn("Roots list changed notification, but no consumers provided. Roots list changed: {}", roots))
  //    )

  /**
   * The MCP session implementation that manages bidirectional JSON-RPC communication
   * between clients and servers.
   */
  def addPrompt(promptRegistration: McpServerFeatures.AsyncPromptRegistration): Mono[Void] = {
    if (promptRegistration == null) return Mono.error(McpError("Prompt registration must not be null"))
    if (this.serverCapabilities.prompts == null) return Mono.error(McpError("Server must be configured with prompt capabilities"))
    Mono.defer(() => {
      val registration = this.prompts.putIfAbsent(promptRegistration.prompt.name, promptRegistration)
      if (registration != null) return Mono.error(McpError("Prompt with name '" + promptRegistration.prompt.name + "' already exists"))
      McpAsyncServer.logger.debug("Added prompt handler: {}", promptRegistration.prompt.name)
      // Servers that declared the listChanged capability SHOULD send a
      // notification,
      // when the list of available prompts changes
      if (this.serverCapabilities.prompts.listChanged) return notifyPromptsListChanged
      Mono.empty
    })

  }

  def removePrompt(promptName: String): Mono[Void] = {
    if (promptName == null) return Mono.error(McpError("Prompt name must not be null"))
    if (this.serverCapabilities.prompts == null) return Mono.error(McpError("Server must be configured with prompt capabilities"))
    Mono.defer(() => {
      val removed = this.prompts.remove(promptName)
      if (removed != null) {
        McpAsyncServer.logger.debug("Removed prompt handler: {}", promptName)
        // Servers that declared the listChanged capability SHOULD send a
        // notification, when the list of available prompts changes
        if (this.serverCapabilities.prompts.listChanged) return this.notifyPromptsListChanged
        return Mono.empty
      }
      Mono.error(McpError("Prompt with name '" + promptName + "' not found"))
    })
  }


  def promptsGetRequestHandler: DefaultMcpSession.RequestHandler[McpSchema.GetPromptResult] =
    (params: AnyRef) => {
      val promptRequest = transport.unmarshalFrom(params, new TypeReference[McpSchema.GetPromptRequest]() {})
      // Implement prompt retrieval logic here
      val registration = this.prompts.get(promptRequest.name)
      val error = Mono.error(McpError("Prompt not found: " + promptRequest.name))
      //      if (registration == null) //todo  handle error
      //        return error
      registration.promptHandler.apply(promptRequest)
    }

  def notifyPromptsListChanged: Mono[Void] = this.mcpSession.sendNotification(McpSchema.METHOD_NOTIFICATION_PROMPTS_LIST_CHANGED, null)

  def promptsListRequestHandler:DefaultMcpSession.RequestHandler[McpSchema.ListPromptsResult] =
    (params: AnyRef) => {
      // TODO: Implement pagination
      // McpSchema.PaginatedRequest request = transport.unmarshalFrom(params,
      // new TypeReference<McpSchema.PaginatedRequest>() {
      // });
      import scala.jdk.CollectionConverters.*
      def promptRegToPrompt(reg: McpServerFeatures.AsyncPromptRegistration): McpSchema.Prompt = {
        reg.prompt
      }

      val promptList: util.List[McpSchema.Prompt] = this.prompts.values.stream.map(reg => promptRegToPrompt(reg)).toList

      //      this.prompts.values.asScala.map( ( ele: McpServerFeatures.SyncPromptRegistration) => McpServerFeatures.AsyncPromptRegistration.fromSync(ele))
      //      val promptList:util.List[McpSchema.Prompt] = this.prompts.values.stream.map(McpServerFeatures.AsyncPromptRegistration.prompt).toList
      Mono.just(McpSchema.ListPromptsResult(promptList, null))
    }  
  // ---------------------------------------
  // Lifecycle Management
  // ---------------------------------------
  def asyncInitializeRequestHandler:DefaultMcpSession.RequestHandler[McpSchema.InitializeResult] = (params: AnyRef) => {
    val initializeRequest = transport.unmarshalFrom(params, new TypeReference[McpSchema.InitializeRequest]() {})
    this.clientCapabilities = initializeRequest.capabilities
    this.clientInfo = initializeRequest.clientInfo
    McpAsyncServer.logger.info("Client initialize request - Protocol: {}, Capabilities: {}, Info: {}", initializeRequest.protocolVersion, initializeRequest.capabilities, initializeRequest.clientInfo)
    // The server MUST respond with the highest protocol version it supports if
    // it does not support the requested (e.g. Client) version.
    var serverProtocolVersion = this.protocolVersions.get(this.protocolVersions.size - 1)
    if (this.protocolVersions.contains(initializeRequest.protocolVersion)) {
      // If the server supports the requested protocol version, it MUST respond
      // with the same version.
      serverProtocolVersion = initializeRequest.protocolVersion
    }
    else McpAsyncServer.logger.warn("Client requested unsupported protocol version: {}, so the server will sugggest the {} version instead", initializeRequest.protocolVersion, serverProtocolVersion)
    Mono.just(McpSchema.InitializeResult(serverProtocolVersion, this.serverCapabilities, this.serverInfo, null))
  }

 

  def asyncRootsListChangedNotificationHandler(rootsChangeConsumers: util.List[Function[util.List[McpSchema.Root], Mono[Void]]]):NotificationHandler = (params: AnyRef) => listRoots.flatMap((listRootsResult: McpSchema.ListRootsResult) => Flux.fromIterable(rootsChangeConsumers).flatMap((consumer: Function[util.List[McpSchema.Root], Mono[Void]]) => consumer.apply(listRootsResult.roots)).onErrorResume((error: Throwable) => {
    McpAsyncServer.logger.error("Error handling roots list change notification", error)
    Mono.empty
  }).`then`)

  /**
   * Add a new tool registration at runtime.
   *
   * @param toolRegistration The tool registration to add
   * @return Mono that completes when clients have been notified of the change
   */
  // ---------------------------------------
  // Tool Management
  // ---------------------------------------
  def addTool(toolRegistration: McpServerFeatures.AsyncToolRegistration): Mono[Void] = {
    if (toolRegistration == null) return Mono.error(McpError("Tool registration must not be null"))
    if (toolRegistration.tool == null) return Mono.error(McpError("Tool must not be null"))
    if (toolRegistration.call == null) return Mono.error(McpError("Tool call handler must not be null"))
    if (this.serverCapabilities.tools == null) return Mono.error(McpError("Server must be configured with tool capabilities"))
    Mono.defer(() => {
        // Check for duplicate tool names
      if (this.tools.stream.anyMatch((th: McpServerFeatures.AsyncToolRegistration) => th.tool.name == toolRegistration.tool.name)) return Mono.error(McpError("Tool with name '" + toolRegistration.tool.name + "' already exists"))
        this.tools.add(toolRegistration)
        McpAsyncServer.logger.debug("Added tool handler: {}", toolRegistration.tool.name)
        if (this.serverCapabilities.tools.listChanged) 
          return notifyToolsListChanged
        Mono.empty
    })
  }


  /**
   * Notifies clients that the list of available tools has changed.
   *
   * @return A Mono that completes when all clients have been notified
   */
  def notifyToolsListChanged: Mono[Void] = this.mcpSession.sendNotification(McpSchema.METHOD_NOTIFICATION_TOOLS_LIST_CHANGED, null)

  def toolsListRequestHandler:DefaultMcpSession.RequestHandler[McpSchema.ListToolsResult]= (params: AnyRef) => {
    val tools: util.List[McpSchema.Tool] = this.tools.stream.map(reg => reg.tool).toList // McpServerFeatures.AsyncToolRegistration.tool
    Mono.just(McpSchema.ListToolsResult(tools, null))
  }

  def toolsCallRequestHandler:DefaultMcpSession.RequestHandler[McpSchema.CallToolResult] = 
    (params: AnyRef) => {
      val callToolRequest = transport.unmarshalFrom(params, new TypeReference[McpSchema.CallToolRequest]() {})
      val toolRegistration = this.tools.stream.filter((tr: McpServerFeatures.AsyncToolRegistration) => callToolRequest.name == tr.tool.name).findAny
      val error = Mono.error(McpError("Tool not found: " + callToolRequest.name))
      //      if (toolRegistration.isEmpty) //todo  handle error
      //        return error
      toolRegistration.map((tool: McpServerFeatures.AsyncToolRegistration) =>
        tool.call.apply(callToolRequest.arguments)).orElse(Mono.error(McpError("Tool not found: " + callToolRequest.name)))
    }

  

  /**
   * Add a new resource handler at runtime.
   *
   * @param resourceHandler The resource handler to add
   * @return Mono that completes when clients have been notified of the change
   */
  // ---------------------------------------
  // Resource Management
  // ---------------------------------------
  def addResource(resourceHandler: McpServerFeatures.AsyncResourceRegistration): Mono[Void] = {
    if (resourceHandler == null || resourceHandler.resource == null) return Mono.error(McpError("Resource must not be null"))
    if (this.serverCapabilities.resources == null) return Mono.error(McpError("Server must be configured with resource capabilities"))
    Mono.defer(() => {
      if (this.resources.putIfAbsent(resourceHandler.resource.uri, resourceHandler) != null) return Mono.error(McpError("Resource with URI '" + resourceHandler.resource.uri + "' already exists"))
        McpAsyncServer.logger.debug("Added resource handler: {}", resourceHandler.resource.uri)
        if (this.serverCapabilities.resources.listChanged) return notifyResourcesListChanged
        Mono.empty
      })
  }

  /**
   * Remove a resource handler at runtime.
   *
   * @param resourceUri The URI of the resource handler to remove
   * @return Mono that completes when clients have been notified of the change
   */
  def removeResource(resourceUri: String): Mono[Void] = {
    if (resourceUri == null) return Mono.error(McpError("Resource URI must not be null"))
    if (this.serverCapabilities.resources == null) return Mono.error(McpError("Server must be configured with resource capabilities"))
    Mono.defer(() => {
        val removed = this.resources.remove(resourceUri)
        if (removed != null) {
          McpAsyncServer.logger.debug("Removed resource handler: {}", resourceUri)
          if (this.serverCapabilities.resources.listChanged) return notifyResourcesListChanged
          return Mono.empty
        }
      Mono.error(McpError("Resource with URI '" + resourceUri + "' not found"))
      })
  }

  /**
   * Notifies clients that the list of available resources has changed.
   *
   * @return A Mono that completes when all clients have been notified
   */
  def notifyResourcesListChanged: Mono[Void] = this.mcpSession.sendNotification(McpSchema.METHOD_NOTIFICATION_RESOURCES_LIST_CHANGED, null)

  def resourcesListRequestHandler:DefaultMcpSession.RequestHandler[McpSchema.ListResourcesResult]= (params: AnyRef) => {
    val resourceList = this.resources.values.stream.map(reg => reg.resource).toList
    //    val resourceList = this.resources.values.stream.map((re :McpServerFeatures.SyncResourceRegistration)=>
    //      McpServerFeatures.AsyncResourceRegistration.fromSync(re).resource).toList
    Mono.just(new McpSchema.ListResourcesResult(resourceList, null))
  }

  def resourceTemplateListRequestHandler:DefaultMcpSession.RequestHandler[McpSchema.ListResourceTemplatesResult] = (params: AnyRef) => Mono.just(new McpSchema.ListResourceTemplatesResult(this.resourceTemplates, null))

  def resourcesReadRequestHandler: DefaultMcpSession.RequestHandler[McpSchema.ReadResourceResult] =
    (params: AnyRef) => {
      val resourceRequest = transport.unmarshalFrom(params, new TypeReference[McpSchema.ReadResourceRequest]() {})
      val resourceUri = resourceRequest.uri
      val registration = this.resources.get(resourceUri)
      //      registration.readHandler.apply(resourceRequest)
      if (registration != null) //todo handle error
        registration.readHandler.apply(resourceRequest)
      else
        Mono.error(McpError("Resource not found: " + resourceUri))
    }


  def loggingNotification(loggingMessageNotification: McpSchema.LoggingMessageNotification): Mono[Void] = {
    if (loggingMessageNotification == null) return Mono.error(McpError("Logging message must not be null"))
    val params = this.transport.unmarshalFrom(loggingMessageNotification, new TypeReference[util.Map[String, AnyRef]]() {})
    if (loggingMessageNotification.level.level < minLoggingLevel.level) return Mono.empty
    this.mcpSession.sendNotification(McpSchema.METHOD_NOTIFICATION_MESSAGE, params)
  }

  def createMessage(createMessageRequest: McpSchema.CreateMessageRequest): Mono[McpSchema.CreateMessageResult] = {
    if (this.clientCapabilities == null) return Mono.error(McpError("Client must be initialized. Call the initialize method first!"))
    if (this.clientCapabilities.sampling == null) return Mono.error(McpError("Client must be configured with sampling capabilities"))
    this.mcpSession.sendRequest(McpSchema.METHOD_SAMPLING_CREATE_MESSAGE, createMessageRequest, McpAsyncServer.CREATE_MESSAGE_RESULT_TYPE_REF)
  }

  def setLoggerRequestHandler: DefaultMcpSession.RequestHandler[McpSchema.ListResourcesResult] = 
    (params: AnyRef) => {
      this.minLoggingLevel = transport.unmarshalFrom(params, new TypeReference[McpSchema.LoggingLevel]() {})
      Mono.empty
  }

  def setProtocolVersions(protocolVersions: util.List[String]): Unit = {
    this.protocolVersions = protocolVersions
  }
  /**
   * Add a new prompt handler at runtime.
   *
   * @param promptRegistration The prompt handler to add
   * @return Mono that completes when clients have been notified of the change
   */
  // ---------------------------------------
  // Prompt Management
  // ---------------------------------------
//  def addPrompt(promptRegistration: McpServerFeatures.AsyncPromptRegistration): Mono[Void] = {
//    if (promptRegistration == null) return Mono.error(new McpError("Prompt registration must not be null"))
//    if (this.serverCapabilities.prompts == null) return Mono.error(new McpError("Server must be configured with prompt capabilities"))
//    Mono.defer(() => {
//        val registration = this.prompts.putIfAbsent(promptRegistration.prompt.name, promptRegistration)
//        if (registration != null) return Mono.error(new McpError("Prompt with name '" + promptRegistration.prompt.name + "' already exists"))
//        McpAsyncServer.logger.debug("Added prompt handler: {}", promptRegistration.prompt.name)
//        // Servers that declared the listChanged capability SHOULD send a
//        // notification,
//        // when the list of available prompts changes
//        if (this.serverCapabilities.prompts.listChanged) return notifyPromptsListChanged
//        Mono.empty
//      })
//  }

  /**
   * Remove a prompt handler at runtime.
   *
   * @param promptName The name of the prompt handler to remove
   * @return Mono that completes when clients have been notified of the change
   */
//  def removePrompt(promptName: String): Mono[Void] = {
//    if (promptName == null) return Mono.error(new McpError("Prompt name must not be null"))
//    if (this.serverCapabilities.prompts == null) return Mono.error(new McpError("Server must be configured with prompt capabilities"))
//    Mono.defer(() => {
//        val removed = this.prompts.remove(promptName)
//        if (removed != null) {
//          McpAsyncServer.logger.debug("Removed prompt handler: {}", promptName)
//          // Servers that declared the listChanged capability SHOULD send a
//          // notification, when the list of available prompts changes
//          if (this.serverCapabilities.prompts.listChanged) return this.notifyPromptsListChanged
//          return Mono.empty
//        }
//        Mono.error(new McpError("Prompt with name '" + promptName + "' not found"))
//      })
//  }

  /**
   * Notifies clients that the list of available prompts has changed.
   *
   * @return A Mono that completes when all clients have been notified
   */
//  def notifyPromptsListChanged: Mono[Void] = this.mcpSession.sendNotification(McpSchema.METHOD_NOTIFICATION_PROMPTS_LIST_CHANGED, null)
//
//  def promptsListRequestHandler:DefaultMcpSession.RequestHandler[McpSchema.ListResourcesResult] = 
//    (params: AnyRef) => {
//    // TODO: Implement pagination
//    // McpSchema.PaginatedRequest request = transport.unmarshalFrom(params,
//    // new TypeReference<McpSchema.PaginatedRequest>() {
//    // });
//        val promptList = this.prompts.values.stream.map(McpServerFeatures.AsyncPromptRegistration.prompt).toList
//        Mono.just(new McpSchema.ListPromptsResult(promptList, null))
//     }

//  def promptsGetRequestHandler :DefaultMcpSession.RequestHandler[McpSchema.ListResourcesResult]= 
//    (params: AnyRef) => {
//      val promptRequest = transport.unmarshalFrom(params, new TypeReference[McpSchema.GetPromptRequest]() {})
//      // Implement prompt retrieval logic here
//      val registration = this.prompts.get(promptRequest.name)
//      if (registration == null) return Mono.error(new McpError("Prompt not found: " + promptRequest.name))
//      registration.promptHandler.apply(promptRequest)
//    }
//  }

  /**
   * Send a logging message notification to all connected clients. Messages below the
   * current minimum logging level will be filtered out.
   *
   * @param loggingMessageNotification The logging message to send
   * @return A Mono that completes when the notification has been sent
   */
  // ---------------------------------------
  // Logging Management
  // ---------------------------------------
//  def loggingNotification(loggingMessageNotification: McpSchema.LoggingMessageNotification): Mono[Void] = {
//    if (loggingMessageNotification == null) return Mono.error(new McpError("Logging message must not be null"))
//    val params = this.transport.unmarshalFrom(loggingMessageNotification, new TypeReference[util.Map[String, AnyRef]]() {})
//    if (loggingMessageNotification.level.level < minLoggingLevel.level) return Mono.empty
//    this.mcpSession.sendNotification(McpSchema.METHOD_NOTIFICATION_MESSAGE, params)
//  }

  /**
   * Handles requests to set the minimum logging level. Messages below this level will
   * not be sent.
   *
   * @return A handler that processes logging level change requests
   */
//  def setLoggerRequestHandler :DefaultMcpSession.RequestHandler[McpSchema.ListResourcesResult]= (params: AnyRef) => {
//    this.minLoggingLevel = transport.unmarshalFrom(params, new TypeReference[McpSchema.LoggingLevel]() {})
//    Mono.empty
//  }

  /**
   * Create a new message using the sampling capabilities of the client. The Model
   * Context Protocol (MCP) provides a standardized way for servers to request LLM
   * sampling (“completions” or “generations”) from language models via clients. This
   * flow allows clients to maintain control over model access, selection, and
   * permissions while enabling servers to leverage AI capabilities—with no server API
   * keys necessary. Servers can request text or image-based interactions and optionally
   * include context from MCP servers in their prompts.
   *
   * @param createMessageRequest The request to create a new message
   * @return A Mono that completes when the message has been created
   * @throws McpError if the client has not been initialized or does not support
   *                  sampling capabilities
   * @throws McpError if the client does not support the createMessage method
   * @see McpSchema.CreateMessageRequest
   * @see McpSchema.CreateMessageResult
   * @see <a href=
   *      "https://spec.modelcontextprotocol.io/specification/client/sampling/">Sampling
   *      Specification</a>
   */
//  def createMessage(createMessageRequest: McpSchema.CreateMessageRequest): Mono[McpSchema.CreateMessageResult] = {
//    if (this.clientCapabilities == null) return Mono.error(new McpError("Client must be initialized. Call the initialize method first!"))
//    if (this.clientCapabilities.sampling == null) return Mono.error(new McpError("Client must be configured with sampling capabilities"))
//    this.mcpSession.sendRequest(McpSchema.METHOD_SAMPLING_CREATE_MESSAGE, createMessageRequest, McpAsyncServer.CREATE_MESSAGE_RESULT_TYPE_REF)
//  }

  /**
   * This method is package-private and used for test only. Should not be called by user
   * code.
   *
   * @param protocolVersions the Client supported protocol versions.
   */
//  def setProtocolVersions(protocolVersions: util.List[String]): Unit = {
//    this.protocolVersions = protocolVersions
//  }
}