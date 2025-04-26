/*
 * Copyright 2024-2024 the original author or authors.
 */
package torch.modelcontextprotocol.client

import torch.modelcontextprotocol.spec.McpSchema.{ClientCapabilities, GetPromptRequest, GetPromptResult, ListPromptsResult}
import org.slf4j.{Logger, LoggerFactory}
import torch.modelcontextprotocol.spec.{ClientMcpTransport, McpSchema}
import torch.modelcontextprotocol.util.Assert

import java.time.Duration

/**
 * A synchronous client implementation for the Model Context Protocol (MCP) that wraps an
 * {@link McpAsyncClient} to provide blocking operations.
 *
 * <p>
 * This client implements the MCP specification by delegating to an asynchronous client
 * and blocking on the results. Key features include:
 * <ul>
 * <li>Synchronous, blocking API for simpler integration in non-reactive applications
 * <li>Tool discovery and invocation for server-provided functionality
 * <li>Resource access and management with URI-based addressing
 * <li>Prompt template handling for standardized AI interactions
 * <li>Real-time notifications for tools, resources, and prompts changes
 * <li>Structured logging with configurable severity levels
 * </ul>
 *
 * <p>
 * The client follows the same lifecycle as its async counterpart:
 * <ol>
 * <li>Initialization - Establishes connection and negotiates capabilities
 * <li>Normal Operation - Handles requests and notifications
 * <li>Graceful Shutdown - Ensures clean connection termination
 * </ol>
 *
 * <p>
 * This implementation implements {@link AutoCloseable} for resource cleanup and provides
 * both immediate and graceful shutdown options. All operations block until completion or
 * timeout, making it suitable for traditional synchronous programming models.
 *
 * @author Dariusz Jędrzejczyk
 * @author Christian Tzolov
 * @see McpClient
 * @see McpAsyncClient
 * @see McpSchema
 */
object McpSyncClient {
  private val logger = LoggerFactory.getLogger(classOf[McpSyncClient])
  // TODO: Consider providing a client config to set this properly
  // this is currently a concern only because AutoCloseable is used - perhaps it
  // is not a requirement?
  private val DEFAULT_CLOSE_TIMEOUT_MS = 10_000L
}

class McpSyncClient @deprecated // TODO make the constructor package private post-deprecation
(private val delegate: McpAsyncClient)

/**
 * Create a new McpSyncClient with the given delegate.
 *
 * @param delegate the asynchronous kernel on top of which this synchronous client
 *                 provides a blocking API.
 * @deprecated Use {@link McpClient# sync ( ClientMcpTransport )} to obtain an instance.
 */
  extends AutoCloseable {
  Assert.notNull(delegate, "The delegate can not be null")

  /**
   * Get the server capabilities that define the supported features and functionality.
   *
   * @return The server capabilities
   */
  def getServerCapabilities: McpSchema.ServerCapabilities = this.delegate.getServerCapabilities

  /**
   * Get the server implementation information.
   *
   * @return The server implementation details
   */
  def getServerInfo: McpSchema.Implementation = this.delegate.getServerInfo

  /**
   * Get the client capabilities that define the supported features and functionality.
   *
   * @return The client capabilities
   */
  def getClientCapabilities: McpSchema.ClientCapabilities = this.delegate.getClientCapabilities

  /**
   * Get the client implementation information.
   *
   * @return The client implementation details
   */
  def getClientInfo: McpSchema.Implementation = this.delegate.getClientInfo

  override def close(): Unit = {
    this.delegate.close()
  }

  def closeGracefully: Boolean = {
    try this.delegate.closeGracefully.block(Duration.ofMillis(McpSyncClient.DEFAULT_CLOSE_TIMEOUT_MS))
    catch {
      case e: RuntimeException =>
        McpSyncClient.logger.warn("Client didn't close within timeout of {} ms.", McpSyncClient.DEFAULT_CLOSE_TIMEOUT_MS, e)
        return false
    }
    true
  }

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
  def initialize: McpSchema.InitializeResult = {
    // TODO: block takes no argument here as we assume the async client is
    // configured with a requestTimeout at all times
    this.delegate.initialize.block
  }

  /**
   * Send a roots/list_changed notification.
   */
  def rootsListChangedNotification(): Unit = {
    this.delegate.rootsListChangedNotification.block
  }

  /**
   * Add a roots dynamically.
   */
  def addRoot(root: McpSchema.Root): Unit = {
    this.delegate.addRoot(root).block
  }

  /**
   * Remove a root dynamically.
   */
  def removeRoot(rootUri: String): Unit = {
    this.delegate.removeRoot(rootUri).block
  }

  /**
   * Send a synchronous ping request.
   *
   * @return
   */
  def ping: AnyRef = this.delegate.ping.block

  /**
   * Calls a tool provided by the server. Tools enable servers to expose executable
   * functionality that can interact with external systems, perform computations, and
   * take actions in the real world.
   *
   * @param callToolRequest The request containing: - name: The name of the tool to call
   *                        (must match a tool name from tools/list) - arguments: Arguments that conform to the
   *                        tool's input schema
   * @return The tool execution result containing: - content: List of content items
   *         (text, images, or embedded resources) representing the tool's output - isError:
   *         Boolean indicating if the execution failed (true) or succeeded (false/absent)
   */
  // --------------------------
  // Tools
  // --------------------------
  def callTool(callToolRequest: McpSchema.CallToolRequest): McpSchema.CallToolResult = this.delegate.callTool(callToolRequest).block

  /**
   * Retrieves the list of all tools provided by the server.
   *
   * @return The list of tools result containing: - tools: List of available tools, each
   *         with a name, description, and input schema - nextCursor: Optional cursor for
   *         pagination if more tools are available
   */
  def listTools: McpSchema.ListToolsResult = this.delegate.listTools.block

  /**
   * Retrieves a paginated list of tools provided by the server.
   *
   * @param cursor Optional pagination cursor from a previous list request
   * @return The list of tools result containing: - tools: List of available tools, each
   *         with a name, description, and input schema - nextCursor: Optional cursor for
   *         pagination if more tools are available
   */
  def listTools(cursor: String): McpSchema.ListToolsResult = this.delegate.listTools(cursor).block

  /**
   * Send a resources/list request.
   *
   * @param cursor the cursor
   * @return the list of resources result.
   */
  // --------------------------
  // Resources
  // --------------------------
  def listResources(cursor: String): McpSchema.ListResourcesResult = this.delegate.listResources(cursor).block

  /**
   * Send a resources/list request.
   *
   * @return the list of resources result.
   */
  def listResources: McpSchema.ListResourcesResult = this.delegate.listResources.block

  /**
   * Send a resources/read request.
   *
   * @param resource the resource to read
   * @return the resource content.
   */
  def readResource(resource: McpSchema.Resource): McpSchema.ReadResourceResult = this.delegate.readResource(resource).block

  /**
   * Send a resources/read request.
   *
   * @param readResourceRequest the read resource request.
   * @return the resource content.
   */
  def readResource(readResourceRequest: McpSchema.ReadResourceRequest): McpSchema.ReadResourceResult = this.delegate.readResource(readResourceRequest).block

  /**
   * Resource templates allow servers to expose parameterized resources using URI
   * templates. Arguments may be auto-completed through the completion API.
   *
   * Request a list of resource templates the server has.
   *
   * @param cursor the cursor
   * @return the list of resource templates result.
   */
  def listResourceTemplates(cursor: String): McpSchema.ListResourceTemplatesResult = this.delegate.listResourceTemplates(cursor).block

  /**
   * Request a list of resource templates the server has.
   *
   * @return the list of resource templates result.
   */
  def listResourceTemplates: McpSchema.ListResourceTemplatesResult = this.delegate.listResourceTemplates.block

  /**
   * Subscriptions. The protocol supports optional subscriptions to resource changes.
   * Clients can subscribe to specific resources and receive notifications when they
   * change.
   *
   * Send a resources/subscribe request.
   *
   * @param subscribeRequest the subscribe request contains the uri of the resource to
   *                         subscribe to.
   */
  def subscribeResource(subscribeRequest: McpSchema.SubscribeRequest): Unit = {
    this.delegate.subscribeResource(subscribeRequest).block
  }

  /**
   * Send a resources/unsubscribe request.
   *
   * @param unsubscribeRequest the unsubscribe request contains the uri of the resource
   *                           to unsubscribe from.
   */
  def unsubscribeResource(unsubscribeRequest: McpSchema.UnsubscribeRequest): Unit = {
    this.delegate.unsubscribeResource(unsubscribeRequest).block
  }

  // --------------------------
  // Prompts
  // --------------------------
  def listPrompts(cursor: String): McpSchema.ListPromptsResult = this.delegate.listPrompts(cursor).block

  def listPrompts: McpSchema.ListPromptsResult = this.delegate.listPrompts.block

  def getPrompt(getPromptRequest: McpSchema.GetPromptRequest): McpSchema.GetPromptResult = this.delegate.getPrompt(getPromptRequest).block

  /**
   * Client can set the minimum logging level it wants to receive from the server.
   *
   * @param loggingLevel the min logging level
   */
  def setLoggingLevel(loggingLevel: McpSchema.LoggingLevel): Unit = {
    this.delegate.setLoggingLevel(loggingLevel).block
  }
}