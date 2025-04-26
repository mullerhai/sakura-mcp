/*
 * Copyright 2024-2024 the original author or authors.
 */
package torch.modelcontextprotocol.server

import reactor.core.publisher.Mono
import reactor.core.scheduler.Schedulers
import torch.modelcontextprotocol.spec.McpSchema

import java.util.function.{Consumer, Function}
import scala.collection.mutable
import scala.collection.mutable.ListBuffer
import scala.jdk.CollectionConverters.*

/**
 * MCP server features specification that a particular server can choose to support.
 *
 * @author Dariusz Jędrzejczyk
 */
object McpServerFeatures {

  case class AsyncToolRegistration(tool: McpSchema.Tool,
                                   call: Function[Map[String, AnyRef], Mono[McpSchema.CallToolResult]]) {
    def fromSync(tool: McpServerFeatures.SyncToolRegistration): McpServerFeatures.AsyncToolRegistration = {
      // FIXME: This is temporary, proper validation should be implemented
      if (tool == null) return null
      new McpServerFeatures.AsyncToolRegistration(tool.tool,
        (map: Map[String, AnyRef])
        => Mono.fromCallable(() => tool.call.apply(map)).subscribeOn(Schedulers.boundedElastic))
    }
  }

  final case class AsyncResourceRegistration(resource: McpSchema.Resource,
                                             readHandler: Function[McpSchema.ReadResourceRequest, Mono[McpSchema.ReadResourceResult]]) {
    def fromSync(resource: McpServerFeatures.SyncResourceRegistration): McpServerFeatures.AsyncResourceRegistration = {
      // FIXME: This is temporary, proper validation should be implemented
      if (resource == null) return null
      new McpServerFeatures.AsyncResourceRegistration(resource.resource, (req: McpSchema.ReadResourceRequest) => Mono.fromCallable(() => resource.readHandler.apply(req)).subscribeOn(Schedulers.boundedElastic))
    }

  }

  final case class AsyncPromptRegistration(prompt: McpSchema.Prompt,
                                           promptHandler: Function[McpSchema.GetPromptRequest, Mono[McpSchema.GetPromptResult]]) {
    def fromSync(prompt: McpServerFeatures.SyncPromptRegistration): McpServerFeatures.AsyncPromptRegistration = {
      // FIXME: This is temporary, proper validation should be implemented
      if (prompt == null) return null
      new McpServerFeatures.AsyncPromptRegistration(prompt.prompt, (req: McpSchema.GetPromptRequest) =>
        Mono.fromCallable(() => prompt.promptHandler.apply(req)).subscribeOn(Schedulers.boundedElastic))
    }
  }

  final case class Async (serverInfo: McpSchema.Implementation, 
                                                    serverCapabilities: McpSchema.ServerCapabilities,
                          tools: List[McpServerFeatures.AsyncToolRegistration],
                          resources: mutable.Map[String, McpServerFeatures.AsyncResourceRegistration],
                          resourceTemplates: List[McpSchema.ResourceTemplate],
                          prompts: mutable.Map[String, McpServerFeatures.AsyncPromptRegistration],
                          rootsChangeConsumers: List[Function[List[McpSchema.Root], Mono[Void]]])

  final case  class Sync(serverInfo: McpSchema.Implementation, 
                                                   serverCapabilities: McpSchema.ServerCapabilities,
                         tools: List[McpServerFeatures.SyncToolRegistration],
                         resources: mutable.Map[String, McpServerFeatures.SyncResourceRegistration],
                         resourceTemplates: List[McpSchema.ResourceTemplate],
                         prompts: mutable.Map[String, McpServerFeatures.SyncPromptRegistration],
                         rootsChangeConsumers: List[Consumer[List[McpSchema.Root]]])

  /**
   * Registration of a tool with its synchronous handler function. Tools are the primary
   * way for MCP servers to expose functionality to AI models. Each tool represents a
   * specific capability, such as:
   * <ul>
   * <li>Performing calculations
   * <li>Accessing external APIs
   * <li>Querying databases
   * <li>Manipulating files
   * <li>Executing system commands
   * </ul>
   *
   * <p>
   * Example tool registration: <pre>{@code
   * new McpServerFeatures.SyncToolRegistration(
   * new Tool(
   * "calculator",
   * "Performs mathematical calculations",
   * new JsonSchemaObject()
   * .required("expression")
   * .property("expression", JsonSchemaType.STRING)
   * ),
   * args -> {
   * String expr = (String) args.get("expression");
   * return new CallToolResult("Result: " + evaluate(expr));
   * }
   * )
   * }</pre>
   *
   * @param tool The tool definition including name, description, and parameter schema
   * @param call The function that implements the tool's logic, receiving arguments and
   * returning results
   */
  final case class SyncToolRegistration(tool: McpSchema.Tool, call: Function[Map[String, AnyRef], McpSchema.CallToolResult])

  /**
   * Asynchronous server features specification.
   *
   * @param serverInfo           The server implementation details
   * @param serverCapabilities   The server capabilities
   * @param tools                The list of tool registrations
   * @param resources            The map of resource registrations
   * @param resourceTemplates    The list of resource templates
   * @param prompts              The map of prompt registrations
   * @param rootsChangeConsumers The list of consumers that will be notified when the
   *                             roots list changes
   */
  object Async {
    /**
     * Convert a synchronous specification into an asynchronous one and provide
     * blocking code offloading to prevent accidental blocking of the non-blocking
     * transport.
     *
     * @param syncSpec a potentially blocking, synchronous specification.
     * @return a specification which is protected from blocking calls specified by the
     *         user.
     */
    def fromSync(syncSpec: McpServerFeatures.Sync) = {
      val tools = new ListBuffer[McpServerFeatures.AsyncToolRegistration]
//        import scala.collection.JavaConversions.*
      for (tool <- syncSpec.tools) {
        tools.append(AsyncToolRegistration.fromSync(tool))
        }
      val resources = new mutable.HashMap[String, McpServerFeatures.AsyncResourceRegistration]
      syncSpec.resources.foreach((key: String, resource: McpServerFeatures.SyncResourceRegistration) => {
          resources.put(key, AsyncResourceRegistration.fromSync(resource))
        })
      val prompts = new mutable.HashMap[String, McpServerFeatures.AsyncPromptRegistration]
      syncSpec.prompts.foreach((key: String, prompt: McpServerFeatures.SyncPromptRegistration) => {
          prompts.put(key, AsyncPromptRegistration.fromSync(prompt))
        })
      val rootChangeConsumers = new ListBuffer[Function[List[McpSchema.Root], Mono[Void]]]
//        import scala.collection.JavaConversions.*
      for (rootChangeConsumer <- syncSpec.rootsChangeConsumers) {
        rootChangeConsumers.append((list: List[McpSchema.Root]) => Mono.fromRunnable[Void](() => rootChangeConsumer.accept(list)).subscribeOn(Schedulers.boundedElastic))
        }
      new McpServerFeatures.Async(syncSpec.serverInfo, syncSpec.serverCapabilities, tools.toList, resources, syncSpec.resourceTemplates, prompts, rootChangeConsumers.toList)
      }
  }

//  final case class AsyncToolRegistration(tool: McpSchema.Tool, 
  //                                    call: Function[mutable.map[String, AnyRef], Mono[McpSchema.CallToolResult]])
  //{
//    this.tool = tool
//    this.call = call
//    final private val tool: McpSchema.Tool = null
  //    final private val call: Function[mutable.map[String, AnyRef], Mono[McpSchema.CallToolResult]] = null
//  }

  /**
   * Registration of a resource with its asynchronous handler function. Resources
   * provide context to AI models by exposing data such as:
   * <ul>
   * <li>File contents
   * <li>Database records
   * <li>API responses
   * <li>System information
   * <li>Application state
   * </ul>
   *
   * <p>
   * Example resource registration: <pre>{@code
   * new McpServerFeatures.AsyncResourceRegistration(
   * new Resource("docs", "Documentation files", "text/markdown"),
   * request -> {
   * String content = readFile(request.getPath());
   * return Mono.just(new ReadResourceResult(content));
   * }
   * )
   * }</pre>
   *
   * @param resource The resource definition including name, description, and MIME type
   * @param readHandler The function that handles resource read requests
   */
  object AsyncResourceRegistration {
    def fromSync(resource: McpServerFeatures.SyncResourceRegistration): McpServerFeatures.AsyncResourceRegistration = {
      // FIXME: This is temporary, proper validation should be implemented
      if (resource == null) return null
      new McpServerFeatures.AsyncResourceRegistration(resource.resource, (req: McpSchema.ReadResourceRequest) => Mono.fromCallable(() => resource.readHandler.apply(req)).subscribeOn(Schedulers.boundedElastic))
    }
  }

//  final case class AsyncResourceRegistration(resource: McpSchema.Resource, 
//                                        readHandler: Function[McpSchema.ReadResourceRequest, Mono[McpSchema.ReadResourceResult]]) 

    //  {
//    this.resource = resource
//    this.readHandler = readHandler
//    final private val resource: McpSchema.Resource = null
//    final private val readHandler: Function[McpSchema.ReadResourceRequest, Mono[McpSchema.ReadResourceResult]] = null
//  }

  object AsyncToolRegistration {

    val tools = new ListBuffer[McpSchema.Tool]()
    def fromSync(tool: McpServerFeatures.SyncToolRegistration): McpServerFeatures.AsyncToolRegistration = {
      // FIXME: This is temporary, proper validation should be implemented
      if (tool == null) return null
      new McpServerFeatures.AsyncToolRegistration(tool.tool,
        (map: Map[String, AnyRef])
        => Mono.fromCallable(() => tool.call.apply(map)).subscribeOn(Schedulers.boundedElastic))
    }


  }

//  final case  class AsyncPromptRegistration(prompt: McpSchema.Prompt,
//                                      promptHandler: Function[McpSchema.GetPromptRequest, Mono[McpSchema.GetPromptResult]]) 

    //{
//    this.prompt = prompt
//    this.promptHandler = promptHandler
//    final private val prompt: McpSchema.Prompt = null
//    final private val promptHandler: Function[McpSchema.GetPromptRequest, Mono[McpSchema.GetPromptResult]] = null
  //}

  /**
   * Registration of a prompt template with its asynchronous handler function. Prompts
   * provide structured templates for AI model interactions, supporting:
   * <ul>
   * <li>Consistent message formatting
   * <li>Parameter substitution
   * <li>Context injection
   * <li>Response formatting
   * <li>Instruction templating
   * </ul>
   *
   * <p>
   * Example prompt registration: <pre>{@code
   * new McpServerFeatures.AsyncPromptRegistration(
   * new Prompt("analyze", "Code analysis template"),
   * request -> {
   * String code = request.getArguments().get("code");
   * return Mono.just(new GetPromptResult(
   * "Analyze this code:\n\n" + code + "\n\nProvide feedback on:"
   * ));
   * }
   * )
   * }</pre>
   *
   * @param prompt The prompt definition including name and description
   * @param promptHandler The function that processes prompt requests and returns
   * formatted templates
   */
  object AsyncPromptRegistration {
    //    val prompt:McpSchema.Prompt= null //new ListBuffer[McpSchema.Prompt]()
    def fromSync(prompt: McpServerFeatures.SyncPromptRegistration): McpServerFeatures.AsyncPromptRegistration = {
      // FIXME: This is temporary, proper validation should be implemented
      if (prompt == null) return null
      new McpServerFeatures.AsyncPromptRegistration(prompt.prompt, (req: McpSchema.GetPromptRequest) =>
        Mono.fromCallable(() => prompt.promptHandler.apply(req)).subscribeOn(Schedulers.boundedElastic))
    }
  }
  //{
//    this.tool = tool
//    this.call = call
//    final private val tool: McpSchema.Tool = null
  //    final private val call: Function[mutable.map[String, AnyRef], McpSchema.CallToolResult] = null
//  }

  /**
   * Registration of a resource with its synchronous handler function. Resources provide
   * context to AI models by exposing data such as:
   * <ul>
   * <li>File contents
   * <li>Database records
   * <li>API responses
   * <li>System information
   * <li>Application state
   * </ul>
   *
   * <p>
   * Example resource registration: <pre>{@code
   * new McpServerFeatures.SyncResourceRegistration(
   * new Resource("docs", "Documentation files", "text/markdown"),
   * request -> {
   * String content = readFile(request.getPath());
   * return new ReadResourceResult(content);
   * }
   * )
   * }</pre>
   *
   * @param resource The resource definition including name, description, and MIME type
   * @param readHandler The function that handles resource read requests
   */
  final case  class SyncResourceRegistration(resource: McpSchema.Resource, readHandler: Function[McpSchema.ReadResourceRequest, McpSchema.ReadResourceResult]) 
  //{
//    this.resource = resource
//    this.readHandler = readHandler
//    final private val resource: McpSchema.Resource = null
//    final private val readHandler: Function[McpSchema.ReadResourceRequest, McpSchema.ReadResourceResult] = null
//  }

  /**
   * Registration of a prompt template with its synchronous handler function. Prompts
   * provide structured templates for AI model interactions, supporting:
   * <ul>
   * <li>Consistent message formatting
   * <li>Parameter substitution
   * <li>Context injection
   * <li>Response formatting
   * <li>Instruction templating
   * </ul>
   *
   * <p>
   * Example prompt registration: <pre>{@code
   * new McpServerFeatures.SyncPromptRegistration(
   * new Prompt("analyze", "Code analysis template"),
   * request -> {
   * String code = request.getArguments().get("code");
   * return new GetPromptResult(
   * "Analyze this code:\n\n" + code + "\n\nProvide feedback on:"
   * );
   * }
   * )
   * }</pre>
   *
   * @param prompt The prompt definition including name and description
   * @param promptHandler The function that processes prompt requests and returns
   * formatted templates
   */
  final case  class SyncPromptRegistration(prompt: McpSchema.Prompt, promptHandler: Function[McpSchema.GetPromptRequest, McpSchema.GetPromptResult]) 
  //{
//    this.prompt = prompt
//    this.promptHandler = promptHandler
//    final private val prompt: McpSchema.Prompt = null
//    final private val promptHandler: Function[McpSchema.GetPromptRequest, McpSchema.GetPromptResult] = null
 // }
}

class McpServerFeatures {}