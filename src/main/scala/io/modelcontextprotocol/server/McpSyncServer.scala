/*
 * Copyright 2024-2024 the original author or authors.
 */
package io.modelcontextprotocol.server

import io.modelcontextprotocol.spec.McpSchema.{ClientCapabilities,ListRootsResult, LoggingMessageNotification,ListToolsResult}
import io.modelcontextprotocol.spec.{McpError, McpSchema}
import io.modelcontextprotocol.util.Assert

/**
 * A synchronous implementation of the Model Context Protocol (MCP) server that wraps
 * {@link McpAsyncServer} to provide blocking operations. This class delegates all
 * operations to an underlying async server instance while providing a simpler,
 * synchronous API for scenarios where reactive programming is not required.
 *
 * <p>
 * The MCP server enables AI models to expose tools, resources, and prompts through a
 * standardized interface. Key features available through this synchronous API include:
 * <ul>
 * <li>Tool registration and management for extending AI model capabilities
 * <li>Resource handling with URI-based addressing for providing context
 * <li>Prompt template management for standardized interactions
 * <li>Real-time client notifications for state changes
 * <li>Structured logging with configurable severity levels
 * <li>Support for client-side AI model sampling
 * </ul>
 *
 * <p>
 * While {@link McpAsyncServer} uses Project Reactor's Mono and Flux types for
 * non-blocking operations, this class converts those into blocking calls, making it more
 * suitable for:
 * <ul>
 * <li>Traditional synchronous applications
 * <li>Simple scripting scenarios
 * <li>Testing and debugging
 * <li>Cases where reactive programming adds unnecessary complexity
 * </ul>
 *
 * <p>
 * The server supports runtime modification of its capabilities through methods like
 * {@link # addTool}, {@link # addResource}, and {@link # addPrompt}, automatically notifying
 * connected clients of changes when configured to do so.
 *
 * @author Christian Tzolov
 * @author Dariusz Jędrzejczyk
 * @see McpAsyncServer
 * @see McpSchema
 */
class McpSyncServer(val asyncServer: McpAsyncServer) {
  /**
   * Creates a new synchronous server that wraps the provided async server.
   *
   * @param asyncServer The async server to wrap
   */
  //{
  Assert.notNull(asyncServer, "Async server must not be null")

  /**
   * Retrieves the list of all roots provided by the client.
   *
   * @return The list of roots
   */
  def listRoots: McpSchema.ListRootsResult = this.listRoots(null)

  /**
   * Retrieves a paginated list of roots provided by the server.
   *
   * @param cursor Optional pagination cursor from a previous list request
   * @return The list of roots
   */
  def listRoots(cursor: String): McpSchema.ListRootsResult = this.asyncServer.listRoots(cursor).block

  /**
   * Add a new tool handler.
   *
   * @param toolHandler The tool handler to add
   */
  def addTool(toolHandler: McpServerFeatures.SyncToolRegistration): Unit = {
    this.asyncServer.addTool(McpServerFeatures.AsyncToolRegistration.fromSync(toolHandler)).block
  }

  /**
   * Remove a tool handler.
   *
   * @param toolName The name of the tool handler to remove
   */
  def removeTool(toolName: String): Unit = {
    this.asyncServer.removeTool(toolName).block
  }

  /**
   * Add a new resource handler.
   *
   * @param resourceHandler The resource handler to add
   */
  def addResource(resourceHandler: McpServerFeatures.SyncResourceRegistration): Unit = {
    this.asyncServer.addResource(McpServerFeatures.AsyncResourceRegistration.fromSync(resourceHandler)).block
  }

  /**
   * Remove a resource handler.
   *
   * @param resourceUri The URI of the resource handler to remove
   */
  def removeResource(resourceUri: String): Unit = {
    this.asyncServer.removeResource(resourceUri).block
  }

  /**
   * Add a new prompt handler.
   *
   * @param promptRegistration The prompt registration to add
   */
  def addPrompt(promptRegistration: McpServerFeatures.SyncPromptRegistration): Unit = {
    this.asyncServer.addPrompt(McpServerFeatures.AsyncPromptRegistration.fromSync(promptRegistration)).block
  }

  /**
   * Remove a prompt handler.
   *
   * @param promptName The name of the prompt handler to remove
   */
  def removePrompt(promptName: String): Unit = {
    this.asyncServer.removePrompt(promptName).block
  }

  /**
   * Notify clients that the list of available tools has changed.
   */
  def notifyToolsListChanged(): Unit = {
    this.asyncServer.notifyToolsListChanged.block
  }

  /**
   * Get the server capabilities that define the supported features and functionality.
   *
   * @return The server capabilities
   */
  def getServerCapabilities: McpSchema.ServerCapabilities = this.asyncServer.getServerCapabilities

  /**
   * Get the server implementation information.
   *
   * @return The server implementation details
   */
  def getServerInfo: McpSchema.Implementation = this.asyncServer.getServerInfo

  /**
   * Get the client capabilities that define the supported features and functionality.
   *
   * @return The client capabilities
   */
  def getClientCapabilities: McpSchema.ClientCapabilities = this.asyncServer.getClientCapabilities

  /**
   * Get the client implementation information.
   *
   * @return The client implementation details
   */
  def getClientInfo: McpSchema.Implementation = this.asyncServer.getClientInfo

  /**
   * Notify clients that the list of available resources has changed.
   */
  def notifyResourcesListChanged(): Unit = {
    this.asyncServer.notifyResourcesListChanged.block
  }

  /**
   * Notify clients that the list of available prompts has changed.
   */
  def notifyPromptsListChanged(): Unit = {
    this.asyncServer.notifyPromptsListChanged.block
  }

  /**
   * Send a logging message notification to all clients.
   *
   * @param loggingMessageNotification The logging message notification to send
   */
  def loggingNotification(loggingMessageNotification: McpSchema.LoggingMessageNotification): Unit = {
    this.asyncServer.loggingNotification(loggingMessageNotification).block
  }

  /**
   * Close the server gracefully.
   */
  def closeGracefully(): Unit = {
    this.asyncServer.closeGracefully.block
  }

  /**
   * Close the server immediately.
   */
  def close(): Unit = {
    this.asyncServer.close()
  }

  /**
   * Get the underlying async server instance.
   *
   * @return The wrapped async server
   */
  def getAsyncServer: McpAsyncServer = this.asyncServer

  def createMessage(createMessageRequest: McpSchema.CreateMessageRequest): McpSchema.CreateMessageResult = this.asyncServer.createMessage(createMessageRequest).block

}












  /**
   * Create a new message using the sampling capabilities of the client. The Model
   * Context Protocol (MCP) provides a standardized way for servers to request LLM
   * sampling ("completions" or "generations") from language models via clients.
   *
   * <p>
   * This flow allows clients to maintain control over model access, selection, and
   * permissions while enabling servers to leverage AI capabilities—with no server API
   * keys necessary. Servers can request text or image-based interactions and optionally
   * include context from MCP servers in their prompts.
   *
   * <p>
   * Unlike its async counterpart, this method blocks until the message creation is
   * complete, making it easier to use in synchronous code paths.
   *
   * @param createMessageRequest The request to create a new message
   * @return The result of the message creation
   * @throws McpError if the client has not been initialized or does not support
   *                  sampling capabilities
   * @throws McpError if the client does not support the createMessage method
   * @see McpSchema.CreateMessageRequest
   * @see McpSchema.CreateMessageResult
   * @see <a href=
   *      "https://spec.modelcontextprotocol.io/specification/client/sampling/">Sampling
   *      Specification</a>
   */
//  def createMessage(createMessageRequest: McpSchema.CreateMessageRequest): McpSchema.CreateMessageResult = this.asyncServer.createMessage(createMessageRequest).block


//}