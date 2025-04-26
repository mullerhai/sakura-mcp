/*
 * Copyright 2024-2024 the original author or authors.
 */
package torch.modelcontextprotocol.server

import torch.modelcontextprotocol.spec.McpSchema.ResourceTemplate
import reactor.core.publisher.Mono
import torch.modelcontextprotocol.spec.{McpSchema, ServerMcpTransport}
import torch.modelcontextprotocol.util.Assert

import java.util
import java.util.function.{Consumer, Function}
import scala.collection.mutable
import scala.collection.mutable.ListBuffer

/**
 * Factory class for creating Model Context Protocol (MCP) servers. MCP servers expose
 * tools, resources, and prompts to AI models through a standardized interface.
 *
 * <p>
 * This class serves as the main entry point for implementing the server-side of the MCP
 * specification. The server's responsibilities include:
 * <ul>
 * <li>Exposing tools that models can invoke to perform actions
 * <li>Providing access to resources that give models context
 * <li>Managing prompt templates for structured model interactions
 * <li>Handling client connections and requests
 * <li>Implementing capability negotiation
 * </ul>
 *
 * <p>
 * Thread Safety: Both synchronous and asynchronous server implementations are
 * thread-safe. The synchronous server processes requests sequentially, while the
 * asynchronous server can handle concurrent requests safely through its reactive
 * programming model.
 *
 * <p>
 * Error Handling: The server implementations provide robust error handling through the
 * McpError class. Errors are properly propagated to clients while maintaining the
 * server's stability. Server implementations should use appropriate error codes and
 * provide meaningful error messages to help diagnose issues.
 *
 * <p>
 * The class provides factory methods to create either:
 * <ul>
 * <li>{@link McpAsyncServer} for non-blocking operations with CompletableFuture responses
 * <li>{@link McpSyncServer} for blocking operations with direct responses
 * </ul>
 *
 * <p>
 * Example of creating a basic synchronous server: <pre>{@code
 * McpServer.sync(transport)
 * .serverInfo("my-server", "1.0.0")
 * .tool(new Tool("calculator", "Performs calculations", schema),
 * args -> new CallToolResult("Result: " + calculate(args)))
 * .build();
 * }</pre>
 *
 * Example of creating a basic asynchronous server: <pre>{@code
 * McpServer.async(transport)
 * .serverInfo("my-server", "1.0.0")
 * .tool(new Tool("calculator", "Performs calculations", schema),
 * args -> Mono.just(new CallToolResult("Result: " + calculate(args))))
 * .build();
 * }</pre>
 *
 * <p>
 * Example with comprehensive asynchronous configuration: <pre>{@code
 * McpServer.async(transport)
 * .serverInfo("advanced-server", "2.0.0")
 * .capabilities(new ServerCapabilities(...))
 * // Register tools
 * .tools(
 * new McpServerFeatures.AsyncToolRegistration(calculatorTool,
 * args -> Mono.just(new CallToolResult("Result: " + calculate(args)))),
 * new McpServerFeatures.AsyncToolRegistration(weatherTool,
 * args -> Mono.just(new CallToolResult("Weather: " + getWeather(args))))
 * )
 * // Register resources
 * .resources(
 * new McpServerFeatures.AsyncResourceRegistration(fileResource,
 * req -> Mono.just(new ReadResourceResult(readFile(req)))),
 * new McpServerFeatures.AsyncResourceRegistration(dbResource,
 * req -> Mono.just(new ReadResourceResult(queryDb(req))))
 * )
 * // Add resource templates
 * .resourceTemplates(
 * new ResourceTemplate("file://{path}", "Access files"),
 * new ResourceTemplate("db://{table}", "Access database")
 * )
 * // Register prompts
 * .prompts(
 * new McpServerFeatures.AsyncPromptRegistration(analysisPrompt,
 * req -> Mono.just(new GetPromptResult(generateAnalysisPrompt(req)))),
 * new McpServerFeatures.AsyncPromptRegistration(summaryPrompt,
 * req -> Mono.just(new GetPromptResult(generateSummaryPrompt(req))))
 * )
 * .build();
 * }</pre>
 *
 * @author Christian Tzolov
 * @author Dariusz Jędrzejczyk
 * @see McpAsyncServer
 * @see McpSyncServer
 * @see McpTransport
 */
object McpServer {
  /**
   * Starts building a synchronous MCP server that provides blocking operations.
   * Synchronous servers process each request to completion before handling the next
   * one, making them simpler to implement but potentially less performant for
   * concurrent operations.
   *
   * @param transport The transport layer implementation for MCP communication
   * @return A new instance of {@link SyncSpec} for configuring the server.
   */
  def sync(transport: ServerMcpTransport) = new McpServer.SyncSpec(transport)

  /**
   * Starts building an asynchronous MCP server that provides blocking operations.
   * Asynchronous servers can handle multiple requests concurrently using a functional
   * paradigm with non-blocking server transports, making them more efficient for
   * high-concurrency scenarios but more complex to implement.
   *
   * @param transport The transport layer implementation for MCP communication
   * @return A new instance of {@link SyncSpec} for configuring the server.
   */
  def async(transport: ServerMcpTransport) = new McpServer.AsyncSpec(transport)

  trait McpServer {}
  /**
   * Asynchronous server specification.
   */
  object AsyncSpec {
    private val DEFAULT_SERVER_INFO = new McpSchema.Implementation("mcp-server", "1.0.0")
  }

  class AsyncSpec (private val transport: ServerMcpTransport) {
    Assert.notNull(transport, "Transport must not be null")
    private var serverInfo = AsyncSpec.DEFAULT_SERVER_INFO
    private var serverCapabilities: McpSchema.ServerCapabilities = null
    /**
     * The Model Context Protocol (MCP) allows servers to expose tools that can be
     * invoked by language models. Tools enable models to interact with external
     * systems, such as querying databases, calling APIs, or performing computations.
     * Each tool is uniquely identified by a name and includes metadata describing its
     * schema.
     */
    final private val tools = new ListBuffer[McpServerFeatures.AsyncToolRegistration]
    /**
     * The Model Context Protocol (MCP) provides a standardized way for servers to
     * expose resources to clients. Resources allow servers to share data that
     * provides context to language models, such as files, database schemas, or
     * application-specific information. Each resource is uniquely identified by a
     * URI.
     */
    final private val resources = new mutable.HashMap[String, McpServerFeatures.AsyncResourceRegistration]
    final private val resourceTemplates = new ListBuffer[McpSchema.ResourceTemplate]
    /**
     * The Model Context Protocol (MCP) provides a standardized way for servers to
     * expose prompt templates to clients. Prompts allow servers to provide structured
     * messages and instructions for interacting with language models. Clients can
     * discover available prompts, retrieve their contents, and provide arguments to
     * customize them.
     */
    final private val prompts = new mutable.HashMap[String, McpServerFeatures.AsyncPromptRegistration]
    final private val rootsChangeConsumers = new ListBuffer[Function[List[McpSchema.Root], Mono[Void]]]

    /**
     * Sets the server implementation information that will be shared with clients
     * during connection initialization. This helps with version compatibility,
     * debugging, and server identification.
     *
     * @param serverInfo The server implementation details including name and version.
     *                   Must not be null.
     * @return This builder instance for method chaining
     * @throws IllegalArgumentException if serverInfo is null
     */
    def serverInfo(serverInfo: McpSchema.Implementation): McpServer.AsyncSpec = {
      Assert.notNull(serverInfo, "Server info must not be null")
      this.serverInfo = serverInfo
      this
    }

    /**
     * Sets the server implementation information using name and version strings. This
     * is a convenience method alternative to
     * {@link # serverInfo ( McpSchema.Implementation )}.
     *
     * @param name    The server name. Must not be null or empty.
     * @param version The server version. Must not be null or empty.
     * @return This builder instance for method chaining
     * @throws IllegalArgumentException if name or version is null or empty
     * @see #serverInfo(McpSchema.Implementation)
     */
    def serverInfo(name: String, version: String): McpServer.AsyncSpec = {
      Assert.hasText(name, "Name must not be null or empty")
      Assert.hasText(version, "Version must not be null or empty")
      this.serverInfo = new McpSchema.Implementation(name, version)
      this
    }

    /**
     * Sets the server capabilities that will be advertised to clients during
     * connection initialization. Capabilities define what features the server
     * supports, such as:
     * <ul>
     * <li>Tool execution
     * <li>Resource access
     * <li>Prompt handling
     * <li>Streaming responses
     * <li>Batch operations
     * </ul>
     *
     * @param serverCapabilities The server capabilities configuration. Must not be
     *                           null.
     * @return This builder instance for method chaining
     * @throws IllegalArgumentException if serverCapabilities is null
     */
    def capabilities(serverCapabilities: McpSchema.ServerCapabilities): McpServer.AsyncSpec = {
      this.serverCapabilities = serverCapabilities
      this
    }

    /**
     * Adds a single tool with its implementation handler to the server. This is a
     * convenience method for registering individual tools without creating a
     * {@link McpServerFeatures.AsyncToolRegistration} explicitly.
     *
     * <p>
     * Example usage: <pre>{@code
     * .tool(
     * new Tool("calculator", "Performs calculations", schema),
     * args -> Mono.just(new CallToolResult("Result: " + calculate(args)))
     * )
     * }</pre>
     * @param tool The tool definition including name, description, and schema. Must
     * not be null.
     * @param handler The function that implements the tool's logic. Must not be null.
     * @return This builder instance for method chaining
     * @throws IllegalArgumentException if tool or handler is null
     */
    def tool(tool: McpSchema.Tool, handler: Function[Map[String, AnyRef], Mono[McpSchema.CallToolResult]]): McpServer.AsyncSpec = {
      Assert.notNull(tool, "Tool must not be null")
      Assert.notNull(handler, "Handler must not be null")
      this.tools.append(new McpServerFeatures.AsyncToolRegistration(tool, handler))
      this
    }

    /**
     * Adds multiple tools with their handlers to the server using a List. This method
     * is useful when tools are dynamically generated or loaded from a configuration
     * source.
     *
     * @param toolRegistrations The list of tool registrations to add. Must not be
     *                          null.
     * @return This builder instance for method chaining
     * @throws IllegalArgumentException if toolRegistrations is null
     * @see #tools(McpServerFeatures.AsyncToolRegistration...)
     */
    def tools(toolRegistrations: List[McpServerFeatures.AsyncToolRegistration]): McpServer.AsyncSpec = {
      Assert.notNull(toolRegistrations, "Tool handlers list must not be null")
      this.tools.addAll(toolRegistrations)
      this
    }

    /**
     * Adds multiple tools with their handlers to the server using varargs. This
     * method provides a convenient way to register multiple tools inline.
     *
     * <p>
     * Example usage: <pre>{@code
     * .tools(
     * new McpServerFeatures.AsyncToolRegistration(calculatorTool, calculatorHandler),
     * new McpServerFeatures.AsyncToolRegistration(weatherTool, weatherHandler),
     * new McpServerFeatures.AsyncToolRegistration(fileManagerTool, fileManagerHandler)
     * )
     * }</pre>
     * @param toolRegistrations The tool registrations to add. Must not be null.
     * @return This builder instance for method chaining
     * @throws IllegalArgumentException if toolRegistrations is null
     * @see #tools(List)
     */
    def tools(toolRegistrations: McpServerFeatures.AsyncToolRegistration*): McpServer.AsyncSpec = {
      for (tool <- toolRegistrations) {
        this.tools.append(tool)
      }
      this
    }

    /**
     * Registers multiple resources with their handlers using a Map. This method is
     * useful when resources are dynamically generated or loaded from a configuration
     * source.
     *
     * @param resourceRegsitrations Map of resource name to registration. Must not be
     *                              null.
     * @return This builder instance for method chaining
     * @throws IllegalArgumentException if resourceRegsitrations is null
     * @see #resources(McpServerFeatures.AsyncResourceRegistration...)
     */
    def resources(resourceRegsitrations: mutable.Map[String, McpServerFeatures.AsyncResourceRegistration]): McpServer.AsyncSpec = {
      Assert.notNull(resourceRegsitrations, "Resource handlers map must not be null")
      this.resources.addAll(resourceRegsitrations)
      this
    }

    /**
     * Registers multiple resources with their handlers using a List. This method is
     * useful when resources need to be added in bulk from a collection.
     *
     * @param resourceRegsitrations List of resource registrations. Must not be null.
     * @return This builder instance for method chaining
     * @throws IllegalArgumentException if resourceRegsitrations is null
     * @see #resources(McpServerFeatures.AsyncResourceRegistration...)
     */
    def resources(resourceRegsitrations: List[McpServerFeatures.AsyncResourceRegistration]): McpServer.AsyncSpec = {
      Assert.notNull(resourceRegsitrations, "Resource handlers list must not be null")
//      import scala.collection.JavaConversions.*
      for (resource <- resourceRegsitrations) {
        this.resources.put(resource.resource.uri, resource)
      }
      this
    }

    /**
     * Registers multiple resources with their handlers using varargs. This method
     * provides a convenient way to register multiple resources inline.
     *
     * <p>
     * Example usage: <pre>{@code
     * .resources(
     * new McpServerFeatures.AsyncResourceRegistration(fileResource, fileHandler),
     * new McpServerFeatures.AsyncResourceRegistration(dbResource, dbHandler),
     * new McpServerFeatures.AsyncResourceRegistration(apiResource, apiHandler)
     * )
     * }</pre>
     * @param resourceRegistrations The resource registrations to add. Must not be
     * null.
     * @return This builder instance for method chaining
     * @throws IllegalArgumentException if resourceRegistrations is null
     */
    def resources(resourceRegistrations: McpServerFeatures.AsyncResourceRegistration*): McpServer.AsyncSpec = {
      Assert.notNull(resourceRegistrations, "Resource handlers list must not be null")
      for (resource <- resourceRegistrations) {
        this.resources.put(resource.resource.uri, resource)
      }
      this
    }

    /**
     * Sets the resource templates that define patterns for dynamic resource access.
     * Templates use URI patterns with placeholders that can be filled at runtime.
     *
     * <p>
     * Example usage: <pre>{@code
     * .resourceTemplates(
     * new ResourceTemplate("file://{path}", "Access files by path"),
     * new ResourceTemplate("db://{table}/{id}", "Access database records")
     * )
     * }</pre>
     * @param resourceTemplates List of resource templates. If null, clears existing
     * templates.
     * @return This builder instance for method chaining
     * @see #resourceTemplates(ResourceTemplate...)
     */
    def resourceTemplates(resourceTemplates: List[McpSchema.ResourceTemplate]): McpServer.AsyncSpec = {
      this.resourceTemplates.addAll(resourceTemplates)
      this
    }

    /**
     * Sets the resource templates using varargs for convenience. This is an
     * alternative to {@link # resourceTemplates ( List )}.
     *
     * @param resourceTemplates The resource templates to set.
     * @return This builder instance for method chaining
     * @see #resourceTemplates(List)
     */
    def resourceTemplates(resourceTemplates: McpSchema.ResourceTemplate*): McpServer.AsyncSpec = {
      for (resourceTemplate <- resourceTemplates) {
        this.resourceTemplates.append(resourceTemplate)
      }
      this
    }

    /**
     * Registers multiple prompts with their handlers using a Map. This method is
     * useful when prompts are dynamically generated or loaded from a configuration
     * source.
     *
     * <p>
     * Example usage: <pre>{@code
     * .prompts(Map.of("analysis", new McpServerFeatures.AsyncPromptRegistration(
     * new Prompt("analysis", "Code analysis template"),
     * request -> Mono.just(new GetPromptResult(generateAnalysisPrompt(request)))
     * )));
     * }</pre>
     * @param prompts Map of prompt name to registration. Must not be null.
     * @return This builder instance for method chaining
     * @throws IllegalArgumentException if prompts is null
     */
    def prompts(prompts: mutable.Map[String, McpServerFeatures.AsyncPromptRegistration]): McpServer.AsyncSpec = {
      this.prompts.addAll(prompts)
      this
    }

    /**
     * Registers multiple prompts with their handlers using a List. This method is
     * useful when prompts need to be added in bulk from a collection.
     *
     * @param prompts List of prompt registrations. Must not be null.
     * @return This builder instance for method chaining
     * @throws IllegalArgumentException if prompts is null
     * @see #prompts(McpServerFeatures.AsyncPromptRegistration...)
     */
    def prompts(prompts: List[McpServerFeatures.AsyncPromptRegistration]): McpServer.AsyncSpec = {
//      import scala.collection.JavaConversions.*
      for (prompt <- prompts) {
        this.prompts.put(prompt.prompt.name, prompt)
      }
      this
    }

    /**
     * Registers multiple prompts with their handlers using varargs. This method
     * provides a convenient way to register multiple prompts inline.
     *
     * <p>
     * Example usage: <pre>{@code
     * .prompts(
     * new McpServerFeatures.AsyncPromptRegistration(analysisPrompt, analysisHandler),
     * new McpServerFeatures.AsyncPromptRegistration(summaryPrompt, summaryHandler),
     * new McpServerFeatures.AsyncPromptRegistration(reviewPrompt, reviewHandler)
     * )
     * }</pre>
     * @param prompts The prompt registrations to add. Must not be null.
     * @return This builder instance for method chaining
     * @throws IllegalArgumentException if prompts is null
     */
    def prompts(prompts: McpServerFeatures.AsyncPromptRegistration*): McpServer.AsyncSpec = {
      for (prompt <- prompts) {
        this.prompts.put(prompt.prompt.name, prompt)
      }
      this
    }

    /**
     * Registers a consumer that will be notified when the list of roots changes. This
     * is useful for updating resource availability dynamically, such as when new
     * files are added or removed.
     *
     * @param consumer The consumer to register. Must not be null.
     * @return This builder instance for method chaining
     * @throws IllegalArgumentException if consumer is null
     */
    def rootsChangeConsumer(consumer: Function[List[McpSchema.Root], Mono[Void]]): McpServer.AsyncSpec = {
      Assert.notNull(consumer, "Consumer must not be null")
      this.rootsChangeConsumers.append(consumer)
      this
    }

    /**
     * Registers multiple consumers that will be notified when the list of roots
     * changes. This method is useful when multiple consumers need to be registered at
     * once.
     *
     * @param consumers The list of consumers to register. Must not be null.
     * @return This builder instance for method chaining
     * @throws IllegalArgumentException if consumers is null
     */
    def rootsChangeConsumers(consumers: List[Function[List[McpSchema.Root], Mono[Void]]]): McpServer.AsyncSpec = {
      Assert.notNull(consumers, "Consumers list must not be null")
      this.rootsChangeConsumers.addAll(consumers)
      this
    }

    /**
     * Registers multiple consumers that will be notified when the list of roots
     * changes using varargs. This method provides a convenient way to register
     * multiple consumers inline.
     *
     * @param consumers The consumers to register. Must not be null.
     * @return This builder instance for method chaining
     * @throws IllegalArgumentException if consumers is null
     */
    def rootsChangeConsumers(@SuppressWarnings(Array("unchecked")) consumers: Function[List[McpSchema.Root], Mono[Void]]*): McpServer.AsyncSpec = {
      for (consumer <- consumers) {
        this.rootsChangeConsumers.append(consumer)
      }
      this
    }

    /**
     * Builds an asynchronous MCP server that provides non-blocking operations.
     *
     * @return A new instance of {@link McpAsyncServer} configured with this builder's
     *         settings
     */
    def build = new McpAsyncServer(this.transport, new McpServerFeatures.Async(this.serverInfo, this.serverCapabilities, this.tools.toList, this.resources, this.resourceTemplates.toList, this.prompts, this.rootsChangeConsumers.toList))
  }

  /**
   * Synchronous server specification.
   */
  object SyncSpec {
    private val DEFAULT_SERVER_INFO = new McpSchema.Implementation("mcp-server", "1.0.0")
  }

  class SyncSpec (private val transport: ServerMcpTransport) {
    Assert.notNull(transport, "Transport must not be null")
    private var serverInfo = SyncSpec.DEFAULT_SERVER_INFO
    private var serverCapabilities: McpSchema.ServerCapabilities = null
    /**
     * The Model Context Protocol (MCP) allows servers to expose tools that can be
     * invoked by language models. Tools enable models to interact with external
     * systems, such as querying databases, calling APIs, or performing computations.
     * Each tool is uniquely identified by a name and includes metadata describing its
     * schema.
     */
    final private val tools = new ListBuffer[McpServerFeatures.SyncToolRegistration]
    /**
     * The Model Context Protocol (MCP) provides a standardized way for servers to
     * expose resources to clients. Resources allow servers to share data that
     * provides context to language models, such as files, database schemas, or
     * application-specific information. Each resource is uniquely identified by a
     * URI.
     */
    final private val resources = new mutable.HashMap[String, McpServerFeatures.SyncResourceRegistration]
    final private val resourceTemplates = new ListBuffer[McpSchema.ResourceTemplate]
    /**
     * The Model Context Protocol (MCP) provides a standardized way for servers to
     * expose prompt templates to clients. Prompts allow servers to provide structured
     * messages and instructions for interacting with language models. Clients can
     * discover available prompts, retrieve their contents, and provide arguments to
     * customize them.
     */
    final private val prompts = new mutable.HashMap[String, McpServerFeatures.SyncPromptRegistration]
    final private val rootsChangeConsumers = new ListBuffer[Consumer[List[McpSchema.Root]]]

    /**
     * Sets the server implementation information that will be shared with clients
     * during connection initialization. This helps with version compatibility,
     * debugging, and server identification.
     *
     * @param serverInfo The server implementation details including name and version.
     *                   Must not be null.
     * @return This builder instance for method chaining
     * @throws IllegalArgumentException if serverInfo is null
     */
    def serverInfo(serverInfo: McpSchema.Implementation): McpServer.SyncSpec = {
      Assert.notNull(serverInfo, "Server info must not be null")
      this.serverInfo = serverInfo
      this
    }

    /**
     * Sets the server implementation information using name and version strings. This
     * is a convenience method alternative to
     * {@link # serverInfo ( McpSchema.Implementation )}.
     *
     * @param name    The server name. Must not be null or empty.
     * @param version The server version. Must not be null or empty.
     * @return This builder instance for method chaining
     * @throws IllegalArgumentException if name or version is null or empty
     * @see #serverInfo(McpSchema.Implementation)
     */
    def serverInfo(name: String, version: String): McpServer.SyncSpec = {
      Assert.hasText(name, "Name must not be null or empty")
      Assert.hasText(version, "Version must not be null or empty")
      this.serverInfo = new McpSchema.Implementation(name, version)
      this
    }

    /**
     * Sets the server capabilities that will be advertised to clients during
     * connection initialization. Capabilities define what features the server
     * supports, such as:
     * <ul>
     * <li>Tool execution
     * <li>Resource access
     * <li>Prompt handling
     * <li>Streaming responses
     * <li>Batch operations
     * </ul>
     *
     * @param serverCapabilities The server capabilities configuration. Must not be
     *                           null.
     * @return This builder instance for method chaining
     * @throws IllegalArgumentException if serverCapabilities is null
     */
    def capabilities(serverCapabilities: McpSchema.ServerCapabilities): McpServer.SyncSpec = {
      this.serverCapabilities = serverCapabilities
      this
    }

    /**
     * Adds a single tool with its implementation handler to the server. This is a
     * convenience method for registering individual tools without creating a
     * {@link ToolRegistration} explicitly.
     *
     * <p>
     * Example usage: <pre>{@code
     * .tool(
     * new Tool("calculator", "Performs calculations", schema),
     * args -> new CallToolResult("Result: " + calculate(args))
     * )
     * }</pre>
     * @param tool The tool definition including name, description, and schema. Must
     * not be null.
     * @param handler The function that implements the tool's logic. Must not be null.
     * @return This builder instance for method chaining
     * @throws IllegalArgumentException if tool or handler is null
     */
    def tool(tool: McpSchema.Tool, handler: Function[Map[String, AnyRef], McpSchema.CallToolResult]): McpServer.SyncSpec = {
      Assert.notNull(tool, "Tool must not be null")
      Assert.notNull(handler, "Handler must not be null")
      this.tools.append(new McpServerFeatures.SyncToolRegistration(tool, handler))
      this
    }

    /**
     * Adds multiple tools with their handlers to the server using a List. This method
     * is useful when tools are dynamically generated or loaded from a configuration
     * source.
     *
     * @param toolRegistrations The list of tool registrations to add. Must not be
     *                          null.
     * @return This builder instance for method chaining
     * @throws IllegalArgumentException if toolRegistrations is null
     * @see #tools(McpServerFeatures.SyncToolRegistration...)
     */
    def tools(toolRegistrations: List[McpServerFeatures.SyncToolRegistration]): McpServer.SyncSpec = {
      Assert.notNull(toolRegistrations, "Tool handlers list must not be null")
      this.tools.addAll(toolRegistrations)
      this
    }

    /**
     * Adds multiple tools with their handlers to the server using varargs. This
     * method provides a convenient way to register multiple tools inline.
     *
     * <p>
     * Example usage: <pre>{@code
     * .tools(
     * new ToolRegistration(calculatorTool, calculatorHandler),
     * new ToolRegistration(weatherTool, weatherHandler),
     * new ToolRegistration(fileManagerTool, fileManagerHandler)
     * )
     * }</pre>
     * @param toolRegistrations The tool registrations to add. Must not be null.
     * @return This builder instance for method chaining
     * @throws IllegalArgumentException if toolRegistrations is null
     * @see #tools(List)
     */
    def tools(toolRegistrations: McpServerFeatures.SyncToolRegistration*): McpServer.SyncSpec = {
      for (tool <- toolRegistrations) {
        this.tools.append(tool)
      }
      this
    }

    /**
     * Registers multiple resources with their handlers using a Map. This method is
     * useful when resources are dynamically generated or loaded from a configuration
     * source.
     *
     * @param resourceRegsitrations Map of resource name to registration. Must not be
     *                              null.
     * @return This builder instance for method chaining
     * @throws IllegalArgumentException if resourceRegsitrations is null
     * @see #resources(McpServerFeatures.SyncResourceRegistration...)
     */
    def resources(resourceRegsitrations: mutable.Map[String, McpServerFeatures.SyncResourceRegistration]): McpServer.SyncSpec = {
      Assert.notNull(resourceRegsitrations, "Resource handlers map must not be null")
      this.resources.addAll(resourceRegsitrations)
      this
    }

    /**
     * Registers multiple resources with their handlers using a List. This method is
     * useful when resources need to be added in bulk from a collection.
     *
     * @param resourceRegsitrations List of resource registrations. Must not be null.
     * @return This builder instance for method chaining
     * @throws IllegalArgumentException if resourceRegsitrations is null
     * @see #resources(McpServerFeatures.SyncResourceRegistration...)
     */
    def resources(resourceRegsitrations: List[McpServerFeatures.SyncResourceRegistration]): McpServer.SyncSpec = {
      Assert.notNull(resourceRegsitrations, "Resource handlers list must not be null")
//      import scala.collection.JavaConversions.*
      for (resource <- resourceRegsitrations) {
        this.resources.put(resource.resource.uri, resource)
      }
      this
    }

    /**
     * Registers multiple resources with their handlers using varargs. This method
     * provides a convenient way to register multiple resources inline.
     *
     * <p>
     * Example usage: <pre>{@code
     * .resources(
     * new ResourceRegistration(fileResource, fileHandler),
     * new ResourceRegistration(dbResource, dbHandler),
     * new ResourceRegistration(apiResource, apiHandler)
     * )
     * }</pre>
     * @param resourceRegistrations The resource registrations to add. Must not be
     * null.
     * @return This builder instance for method chaining
     * @throws IllegalArgumentException if resourceRegistrations is null
     */
    def resources(resourceRegistrations: McpServerFeatures.SyncResourceRegistration*): McpServer.SyncSpec = {
      Assert.notNull(resourceRegistrations, "Resource handlers list must not be null")
      for (resource <- resourceRegistrations) {
        this.resources.put(resource.resource.uri, resource)
      }
      this
    }

    /**
     * Sets the resource templates that define patterns for dynamic resource access.
     * Templates use URI patterns with placeholders that can be filled at runtime.
     *
     * <p>
     * Example usage: <pre>{@code
     * .resourceTemplates(
     * new ResourceTemplate("file://{path}", "Access files by path"),
     * new ResourceTemplate("db://{table}/{id}", "Access database records")
     * )
     * }</pre>
     * @param resourceTemplates List of resource templates. If null, clears existing
     * templates.
     * @return This builder instance for method chaining
     * @see #resourceTemplates(ResourceTemplate...)
     */
    def resourceTemplates(resourceTemplates: List[McpSchema.ResourceTemplate]): McpServer.SyncSpec = {
      this.resourceTemplates.addAll(resourceTemplates)
      this
    }

    /**
     * Sets the resource templates using varargs for convenience. This is an
     * alternative to {@link # resourceTemplates ( List )}.
     *
     * @param resourceTemplates The resource templates to set.
     * @return This builder instance for method chaining
     * @see #resourceTemplates(List)
     */
    def resourceTemplates(resourceTemplates: McpSchema.ResourceTemplate*): McpServer.SyncSpec = {
      for (resourceTemplate <- resourceTemplates) {
        this.resourceTemplates.append(resourceTemplate)
      }
      this
    }

    /**
     * Registers multiple prompts with their handlers using a Map. This method is
     * useful when prompts are dynamically generated or loaded from a configuration
     * source.
     *
     * <p>
     * Example usage: <pre>{@code
     * Map<String, PromptRegistration> prompts = new HashMap<>();
     * prompts.put("analysis", new PromptRegistration(
     * new Prompt("analysis", "Code analysis template"),
     * request -> new GetPromptResult(generateAnalysisPrompt(request))
     * ));
     * .prompts(prompts)
     * }</pre>
     * @param prompts Map of prompt name to registration. Must not be null.
     * @return This builder instance for method chaining
     * @throws IllegalArgumentException if prompts is null
     */
    def prompts(prompts: mutable.Map[String, McpServerFeatures.SyncPromptRegistration]): McpServer.SyncSpec = {
      this.prompts.addAll(prompts)
      this
    }

    /**
     * Registers multiple prompts with their handlers using a List. This method is
     * useful when prompts need to be added in bulk from a collection.
     *
     * @param prompts List of prompt registrations. Must not be null.
     * @return This builder instance for method chaining
     * @throws IllegalArgumentException if prompts is null
     * @see #prompts(McpServerFeatures.SyncPromptRegistration...)
     */
    def prompts(prompts: List[McpServerFeatures.SyncPromptRegistration]): McpServer.SyncSpec = {
//      import scala.collection.JavaConversions.*
      for (prompt <- prompts) {
        this.prompts.put(prompt.prompt.name, prompt)
      }
      this
    }

    /**
     * Registers multiple prompts with their handlers using varargs. This method
     * provides a convenient way to register multiple prompts inline.
     *
     * <p>
     * Example usage: <pre>{@code
     * .prompts(
     * new PromptRegistration(analysisPrompt, analysisHandler),
     * new PromptRegistration(summaryPrompt, summaryHandler),
     * new PromptRegistration(reviewPrompt, reviewHandler)
     * )
     * }</pre>
     * @param prompts The prompt registrations to add. Must not be null.
     * @return This builder instance for method chaining
     * @throws IllegalArgumentException if prompts is null
     */
    def prompts(prompts: McpServerFeatures.SyncPromptRegistration*): McpServer.SyncSpec = {
      for (prompt <- prompts) {
        this.prompts.put(prompt.prompt.name, prompt)
      }
      this
    }

    /**
     * Registers a consumer that will be notified when the list of roots changes. This
     * is useful for updating resource availability dynamically, such as when new
     * files are added or removed.
     *
     * @param consumer The consumer to register. Must not be null.
     * @return This builder instance for method chaining
     * @throws IllegalArgumentException if consumer is null
     */
    def rootsChangeConsumer(consumer: Consumer[List[McpSchema.Root]]): McpServer.SyncSpec = {
      Assert.notNull(consumer, "Consumer must not be null")
      this.rootsChangeConsumers.append(consumer)
      this
    }

    /**
     * Registers multiple consumers that will be notified when the list of roots
     * changes. This method is useful when multiple consumers need to be registered at
     * once.
     *
     * @param consumers The list of consumers to register. Must not be null.
     * @return This builder instance for method chaining
     * @throws IllegalArgumentException if consumers is null
     */
    def rootsChangeConsumers(consumers: List[Consumer[List[McpSchema.Root]]]): McpServer.SyncSpec = {
      Assert.notNull(consumers, "Consumers list must not be null")
      this.rootsChangeConsumers.addAll(consumers)
      this
    }

    /**
     * Registers multiple consumers that will be notified when the list of roots
     * changes using varargs. This method provides a convenient way to register
     * multiple consumers inline.
     *
     * @param consumers The consumers to register. Must not be null.
     * @return This builder instance for method chaining
     * @throws IllegalArgumentException if consumers is null
     */
    def rootsChangeConsumers(consumers: Consumer[List[McpSchema.Root]]*): McpServer.SyncSpec = {
      for (consumer <- consumers) {
        this.rootsChangeConsumers.append(consumer)
      }
      this
    }

    /**
     * Builds a synchronous MCP server that provides blocking operations.
     *
     * @return A new instance of {@link McpSyncServer} configured with this builder's
     *         settings
     */
    def build: McpSyncServer = {
      val syncFeatures = new McpServerFeatures.Sync(this.serverInfo, this.serverCapabilities, this.tools.toList, this.resources, this.resourceTemplates.toList, this.prompts, this.rootsChangeConsumers.toList)
      new McpSyncServer(new McpAsyncServer(this.transport, McpServerFeatures.Async.fromSync(syncFeatures)))
    }
  }
}

