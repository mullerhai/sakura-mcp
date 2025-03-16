/*
 * Copyright 2024-2024 the original author or authors.
 */
package io.modelcontextprotocol.spec

import com.fasterxml.jackson.annotation.*
import com.fasterxml.jackson.annotation.JsonTypeInfo.As
import com.fasterxml.jackson.core
import com.fasterxml.jackson.core.`type`.TypeReference

import java.io.IOException
import java.util

//type.TypeReference

import com.fasterxml.jackson.databind.ObjectMapper
import org.slf4j.{Logger, LoggerFactory}


class McpSchema {


}
/**
 * Based on the <a href="http://www.jsonrpc.org/specification">JSON-RPC 2.0
 * specification</a> and the <a href=
 * "https://github.com/modelcontextprotocol/specification/blob/main/schema/schema.ts">Model
 * Context Protocol Schema</a>.
 *
 * @author Christian Tzolov
 */
object McpSchema {
  private val logger = LoggerFactory.getLogger(classOf[McpSchema])
  val LATEST_PROTOCOL_VERSION = "2024-11-05"
  val JSONRPC_VERSION = "2.0"
  // Lifecycle Methods
  // ---------------------------
  // Method Names
  // ---------------------------
  val METHOD_INITIALIZE = "initialize"
  val METHOD_NOTIFICATION_INITIALIZED = "notifications/initialized"
  val METHOD_PING = "ping"
  // Tool Methods
  val METHOD_TOOLS_LIST = "tools/list"
  val METHOD_TOOLS_CALL = "tools/call"
  val METHOD_NOTIFICATION_TOOLS_LIST_CHANGED = "notifications/tools/list_changed"
  // Resources Methods
  val METHOD_RESOURCES_LIST = "resources/list"
  val METHOD_RESOURCES_READ = "resources/read"
  val METHOD_NOTIFICATION_RESOURCES_LIST_CHANGED = "notifications/resources/list_changed"
  val METHOD_RESOURCES_TEMPLATES_LIST = "resources/templates/list"
  val METHOD_RESOURCES_SUBSCRIBE = "resources/subscribe"
  val METHOD_RESOURCES_UNSUBSCRIBE = "resources/unsubscribe"
  // Prompt Methods
  val METHOD_PROMPT_LIST = "prompts/list"
  val METHOD_PROMPT_GET = "prompts/get"
  val METHOD_NOTIFICATION_PROMPTS_LIST_CHANGED = "notifications/prompts/list_changed"
  // Logging Methods
  val METHOD_LOGGING_SET_LEVEL = "logging/setLevel"
  val METHOD_NOTIFICATION_MESSAGE = "notifications/message"
  // Roots Methods
  val METHOD_ROOTS_LIST = "roots/list"
  val METHOD_NOTIFICATION_ROOTS_LIST_CHANGED = "notifications/roots/list_changed"
  // Sampling Methods
  val METHOD_SAMPLING_CREATE_MESSAGE = "sampling/createMessage"
  private val OBJECT_MAPPER = new ObjectMapper

  /**
   * Standard error codes used in MCP JSON-RPC responses.
   */
  // ---------------------------
  // JSON-RPC Error Codes
  // ---------------------------
  object ErrorCodes {
    /**
     * Invalid JSON was received by the server.
     */
    val PARSE_ERROR: Int = -32700
    /**
     * The JSON sent is not a valid Request object.
     */
    val INVALID_REQUEST: Int = -32600
    /**
     * The method does not exist / is not available.
     */
    val METHOD_NOT_FOUND: Int = -32601
    /**
     * Invalid method parameter(s).
     */
    val INVALID_PARAMS: Int = -32602
    /**
     * Internal JSON-RPC error.
     */
    val INTERNAL_ERROR: Int = -32603
  }

  trait Request {}

  @JsonInclude(JsonInclude.Include.NON_ABSENT)
  @JsonIgnoreProperties(ignoreUnknown = true)
  final case  class ListRootsResult(@JsonProperty("roots") roots: util.List[McpSchema.Root]) //{
  
  @JsonInclude(JsonInclude.Include.NON_ABSENT)
  @JsonIgnoreProperties(ignoreUnknown = true)
  final private[spec] class JsonSchema private[spec](@JsonProperty("type") `type`: String, @JsonProperty("properties") properties: util.Map[String, AnyRef], @JsonProperty("required") required: util.List[String], @JsonProperty("additionalProperties") additionalProperties: Boolean)


  @JsonInclude(JsonInclude.Include.NON_ABSENT)
  final case class Tool(@JsonProperty("name") name: String, @JsonProperty("description") description: String, @JsonProperty("inputSchema") inputSchema: McpSchema.JsonSchema)

  @JsonInclude(JsonInclude.Include.NON_ABSENT)
  final case class ModelHint(@JsonProperty("name") name: String)

  @JsonInclude(JsonInclude.Include.NON_ABSENT)
  final case class SamplingMessage(@JsonProperty("role") role: McpSchema.Role, @JsonProperty("content") content: McpSchema.Content) // {

  @JsonInclude(JsonInclude.Include.NON_ABSENT)
  final case class ModelPreferences(@JsonProperty("hints") hints: util.List[McpSchema.ModelHint], @JsonProperty("costPriority") costPriority: Double, @JsonProperty("speedPriority") speedPriority: Double, @JsonProperty("intelligencePriority") intelligencePriority: Double)

  @JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "type")
  @JsonSubTypes(Array(new JsonSubTypes.Type(value = classOf[McpSchema.TextContent], name = "text"), new JsonSubTypes.Type(value = classOf[McpSchema.ImageContent], name = "image"), new JsonSubTypes.Type(value = classOf[McpSchema.EmbeddedResource], name = "resource")))
  trait Content {
    def `type`: String = {
      if (this.isInstanceOf[McpSchema.TextContent]) return "text"
      else if (this.isInstanceOf[McpSchema.ImageContent]) return "image"
      else if (this.isInstanceOf[McpSchema.EmbeddedResource]) return "resource"
      throw new IllegalArgumentException("Unknown content type: " + this)
    }
  }

  @JsonInclude(JsonInclude.Include.NON_ABSENT)
  final case class TextContent(@JsonProperty("audience") audience: util.List[McpSchema.Role], @JsonProperty("priority") priority: Double, @JsonProperty("text") text: String) extends McpSchema.Content


  @JsonInclude(JsonInclude.Include.NON_ABSENT)
  final case class ImageContent(@JsonProperty("audience") audience: util.List[McpSchema.Role], @JsonProperty("priority") priority: Double, @JsonProperty("data") data: String, @JsonProperty("mimeType") mimeType: String) extends McpSchema.Content


  @JsonInclude(JsonInclude.Include.NON_ABSENT)
  final case class EmbeddedResource(@JsonProperty("audience") audience: util.List[McpSchema.Role],
                                    @JsonProperty("priority") priority: Double,
                                    @JsonProperty("resource") resource: McpSchema.ResourceContents) extends McpSchema.Content

  @JsonInclude(JsonInclude.Include.NON_ABSENT)
  final case class Root(@JsonProperty("uri") uri: String, @JsonProperty("name") name: String)

  private val MAP_TYPE_REF = new TypeReference[util.HashMap[String, AnyRef]]() {}

  /**
   * Deserializes a JSON string into a JSONRPCMessage object.
   *
   * @param objectMapper The ObjectMapper instance to use for deserialization
   * @param jsonText     The JSON string to deserialize
   * @return A JSONRPCMessage instance using either the {@link JSONRPCRequest},
   *         {@link JSONRPCNotification}, or {@link JSONRPCResponse} classes.
   * @throws IOException              If there's an error during deserialization
   * @throws IllegalArgumentException If the JSON structure doesn't match any known
   *                                  message type
   */
  @throws[IOException]
  def deserializeJsonRpcMessage(objectMapper: ObjectMapper, jsonText: String): McpSchema.JSONRPCMessage = {
    logger.debug("Received JSON message: {}", jsonText)
    val map = objectMapper.readValue(jsonText, MAP_TYPE_REF)
    // Determine message type based on specific JSON structure
    if (map.containsKey("method") && map.containsKey("id")) return objectMapper.convertValue(map, classOf[McpSchema.JSONRPCRequest])
    else if (map.containsKey("method") && !map.containsKey("id")) return objectMapper.convertValue(map, classOf[McpSchema.JSONRPCNotification])
    else if (map.containsKey("result") || map.containsKey("error")) return objectMapper.convertValue(map, classOf[McpSchema.JSONRPCResponse])
    throw new IllegalArgumentException("Cannot deserialize JSONRPCMessage: " + jsonText)
  }

  // ---------------------------
  // JSON-RPC Message Types
  // ---------------------------
  trait JSONRPCMessage {
    def jsonrpc: String
  }

  @JsonInclude(JsonInclude.Include.NON_ABSENT) 
  final case  class JSONRPCRequest(@JsonProperty("jsonrpc") jsonrpc: String,
                                   @JsonProperty("method") method: String,
                                   @JsonProperty("id") id: AnyRef, 
                                   @JsonProperty("params") params: AnyRef) extends McpSchema.JSONRPCMessage 
//                                   {
//    this.jsonrpc = jsonrpc
//    this.method = method
//    this.id = id
//    this.params = params
//    @JsonProperty("jsonrpc") final private val jsonrpc = null
//    @JsonProperty("method") final private val method = null
//    @JsonProperty("id") final private val id = null
//    @JsonProperty("params") final private val params = null
//    // @formatter:on
//  }

  @JsonInclude(JsonInclude.Include.NON_ABSENT) final case  class JSONRPCNotification(
                                                                                      @JsonProperty("jsonrpc") jsonrpc: String, 
                                                                                      @JsonProperty("method") method: String, 
                                                                                      @JsonProperty("params") params: util.Map[String, AnyRef]) extends McpSchema.JSONRPCMessage //{
//    this.jsonrpc = jsonrpc
//    this.method = method
//    this.params = params
//    @JsonProperty("jsonrpc") final private val jsonrpc = null
//    @JsonProperty("method") final private val method = null
//    @JsonProperty("params") final private val params = null
//    // @formatter:on
//  }

  @JsonInclude(JsonInclude.Include.NON_ABSENT) object JSONRPCResponse {


    @JsonInclude(JsonInclude.Include.NON_ABSENT) final case class JSONRPCError(@JsonProperty("code") code: Int,
                                                                          @JsonProperty("message") message: String, 
                                                                          @JsonProperty("data") data: AnyRef) //{
//      this.code = code
//      this.message = message
//      this.data = data
//      @JsonProperty("code") final private val code = 0
//      @JsonProperty("message") final private val message = null
//      @JsonProperty("data") final private val data = null
//    }

  }

  @JsonInclude(JsonInclude.Include.NON_ABSENT) final case  class JSONRPCResponse(@JsonProperty("jsonrpc") jsonrpc: String,
                                                                           @JsonProperty("id") id: AnyRef,
                                                                           @JsonProperty("result") result: AnyRef, 
                                                                           @JsonProperty("error") error: JSONRPCResponse.JSONRPCError) extends McpSchema.JSONRPCMessage //{
//    this.jsonrpc = jsonrpc
//    this.id = id
//    this.result = result
//    this.error = error
//    @JsonProperty("jsonrpc") final private val jsonrpc = null
//    @JsonProperty("id") final private val id = null
//    @JsonProperty("result") final private val result = null
//    @JsonProperty("error") final private val error = null
//    // @formatter:on
//  }

  // ---------------------------
  // Initialization
  // ---------------------------
  @JsonInclude(JsonInclude.Include.NON_ABSENT) final case  class InitializeRequest(@JsonProperty("protocolVersion") protocolVersion: String,
                                                                             @JsonProperty("capabilities") capabilities: McpSchema.ClientCapabilities,
                                                                             @JsonProperty("clientInfo") clientInfo: McpSchema.Implementation) extends McpSchema.Request //{
//    this.protocolVersion = protocolVersion
//    this.capabilities = capabilities
//    this.clientInfo = clientInfo
//    @JsonProperty("protocolVersion") final private val protocolVersion = null
//    @JsonProperty("capabilities") final private val capabilities = null
//    @JsonProperty("clientInfo") final private val clientInfo = null
//    // @formatter:on
//  }

  @JsonInclude(JsonInclude.Include.NON_ABSENT)
  @JsonIgnoreProperties(ignoreUnknown = true) final case  class InitializeResult(@JsonProperty("protocolVersion") protocolVersion: String, 
                                                                                 @JsonProperty("capabilities") capabilities: McpSchema.ServerCapabilities, 
                                                                                 @JsonProperty("serverInfo") serverInfo: McpSchema.Implementation,
                                                                                 @JsonProperty("instructions") instructions: String) //{
//    this.protocolVersion = protocolVersion
//    this.capabilities = capabilities
//    this.serverInfo = serverInfo
//    this.instructions = instructions
//    @JsonProperty("protocolVersion") final private val protocolVersion = null
//    @JsonProperty("capabilities") final private val capabilities = null
//    @JsonProperty("serverInfo") final private val serverInfo = null
//    @JsonProperty("instructions") final private val instructions = null
//    // @formatter:on
//  }

  /**
   * Clients can implement additional features to enrich connected MCP servers with
   * additional capabilities. These capabilities can be used to extend the functionality
   * of the server, or to provide additional information to the server about the
   * client's capabilities.
   *
   * @param experimental WIP
   * @param roots        define the boundaries of where servers can operate within the
   *                     filesystem, allowing them to understand which directories and files they have
   *                     access to.
   * @param sampling     Provides a standardized way for servers to request LLM sampling
   *                     (“completions” or “generations”) from language models via clients.
   *
   */
  @JsonInclude(JsonInclude.Include.NON_ABSENT) object ClientCapabilities {
    /**
     * Roots define the boundaries of where servers can operate within the filesystem,
     * allowing them to understand which directories and files they have access to.
     * Servers can request the list of roots from supporting clients and
     * receive notifications when that list changes.
     *
     * @param listChanged Whether the client would send notification about roots
     *                    has changed since the last time the server checked.
     */
    @JsonInclude(JsonInclude.Include.NON_ABSENT) final case class RootCapabilities(@JsonProperty("listChanged") listChanged: Boolean) 
//    {
//      this.listChanged = listChanged
//      @JsonProperty("listChanged") final private val listChanged = false
//    }

    /**
     * Provides a standardized way for servers to request LLM
     * sampling ("completions" or "generations") from language
     * models via clients. This flow allows clients to maintain
     * control over model access, selection, and permissions
     * while enabling servers to leverage AI capabilities—with
     * no server API keys necessary. Servers can request text or
     * image-based interactions and optionally include context
     * from MCP servers in their prompts.
     */
    @JsonInclude(JsonInclude.Include.NON_ABSENT) final class Sampling {
    }

    def builder = new ClientCapabilities.Builder

    class Builder {
      private var experimental: util.Map[String, AnyRef] = null
      private var roots: ClientCapabilities.RootCapabilities = null
      private var samplings: ClientCapabilities.Sampling = null

      def experimental(experimental: util.Map[String, AnyRef]): ClientCapabilities.Builder = {
        this.experimental = experimental
        this
      }

      def roots(listChanged: Boolean): ClientCapabilities.Builder = {
        this.roots = new ClientCapabilities.RootCapabilities(listChanged)
        this
      }

      def sampling: ClientCapabilities.Builder = {
        this.samplings= new ClientCapabilities.Sampling
        this
      }

      def build = new McpSchema.ClientCapabilities(experimental, roots, samplings)
    }
  }

  @JsonInclude(JsonInclude.Include.NON_ABSENT) final case class ClientCapabilities(@JsonProperty("experimental") experimental: util.Map[String, AnyRef],
                                                                              @JsonProperty("roots") roots: ClientCapabilities.RootCapabilities, 
                                                                              @JsonProperty("sampling") sampling: ClientCapabilities.Sampling) // {
//    this.experimental = experimental
//    this.roots = roots
//    this.sampling = sampling
//    @JsonProperty("experimental") final private val experimental = null
//    @JsonProperty("roots") final private val roots = null
//    @JsonProperty("sampling") final private val sampling = null
//    // @formatter:on
//  }

  @JsonInclude(JsonInclude.Include.NON_ABSENT) object ServerCapabilities {
    @JsonInclude(JsonInclude.Include.NON_ABSENT) final  class LoggingCapabilities {
    }

    @JsonInclude(JsonInclude.Include.NON_ABSENT) final case  class PromptCapabilities(@JsonProperty("listChanged") listChanged: Boolean = false) //{
//      this.listChanged = listChanged
//      @JsonProperty("listChanged") final private val listChanged = false
//    }

    @JsonInclude(JsonInclude.Include.NON_ABSENT) final case class ResourceCapabilities(@JsonProperty("subscribe") subscribe: Boolean = false,
                                                                                  @JsonProperty("listChanged") listChanged: Boolean = false)
   // {
//      this.subscribe = subscribe
//      this.listChanged = listChanged
//      @JsonProperty("subscribe") final private val subscribe = false
//      @JsonProperty("listChanged") final private val listChanged = false
//    }

    @JsonInclude(JsonInclude.Include.NON_ABSENT) final case class ToolCapabilities(@JsonProperty("listChanged") listChanged: Boolean = false)
//    {
//      this.listChanged = listChanged
//      @JsonProperty("listChanged") final private val listChanged = false
//    }

    def builder = new ServerCapabilities.Builder

    class Builder {
      private var experimental: util.Map[String, AnyRef] = null
      private var loggings = new ServerCapabilities.LoggingCapabilities
      private var prompts: ServerCapabilities.PromptCapabilities = null
      private var resources: ServerCapabilities.ResourceCapabilities = null
      private var tools: ServerCapabilities.ToolCapabilities = null

      def experimental(experimental: util.Map[String, AnyRef]): ServerCapabilities.Builder = {
        this.experimental = experimental
        this
      }

      def logging: ServerCapabilities.Builder = {
        this.loggings = new ServerCapabilities.LoggingCapabilities
        this
      }

      def prompts(listChanged: Boolean): ServerCapabilities.Builder = {
        this.prompts = new ServerCapabilities.PromptCapabilities(listChanged)
        this
      }

      def resources(subscribe: Boolean, listChanged: Boolean): ServerCapabilities.Builder = {
        this.resources = new ServerCapabilities.ResourceCapabilities(subscribe, listChanged)
        this
      }

      def tools(listChanged: Boolean): ServerCapabilities.Builder = {
        this.tools = new ServerCapabilities.ToolCapabilities(listChanged)
        this
      }

      def build = new McpSchema.ServerCapabilities(experimental, loggings, prompts, resources, tools)
    }
  }

  @JsonInclude(JsonInclude.Include.NON_ABSENT) final case class ServerCapabilities(@JsonProperty("experimental") experimental: util.Map[String, AnyRef], @JsonProperty("logging") logging: ServerCapabilities.LoggingCapabilities, @JsonProperty("prompts") prompts: ServerCapabilities.PromptCapabilities, @JsonProperty("resources") resources: ServerCapabilities.ResourceCapabilities, @JsonProperty("tools") tools: ServerCapabilities.ToolCapabilities)
//  {
//    this.experimental = experimental
//    this.logging = logging
//    this.prompts = prompts
//    this.resources = resources
//    this.tools = tools
//    @JsonProperty("experimental") final private val experimental = null
//    @JsonProperty("logging") final private val logging = null
//    @JsonProperty("prompts") final private val prompts = null
//    @JsonProperty("resources") final private val resources = null
//    @JsonProperty("tools") final private val tools = null
    // @formatter:on
//  }

  @JsonInclude(JsonInclude.Include.NON_ABSENT) final case  class Implementation(@JsonProperty("name") name: String, @JsonProperty("version") version: String) //{
//    this.name = name
//    this.version = version
//    @JsonProperty("name") final private val name = null
//    @JsonProperty("version") final private val version = null
    // @formatter:on
 // }

  enum Role:
    case USER, ASSISTANT
  // Existing Enums and Base Types (from previous implementation)
//   class Role extends Enumeration {
//    type Role = Value
////    val // @formatter:off
//    val USER,ASSISTANT = Value
//// @formatter:on
//  }

  /**
   * Base for objects that include optional annotations for the client. The client can
   * use annotations to inform how objects are used or displayed
   */
  // ---------------------------
  // Resource Interfaces
  // ---------------------------
  trait Annotated {
    def annotations: McpSchema.Annotations
  }

  /**
   * Optional annotations for the client. The client can use annotations to inform how
   * objects are used or displayed.
   *
   * @param audience Describes who the intended customer of this object or data is. It
   *                 can include multiple entries to indicate content useful for multiple audiences
   *                 (e.g., `["user", "assistant"]`).
   * @param priority Describes how important this data is for operating the server. A
   *                 value of 1 means "most important," and indicates that the data is effectively
   *                 required, while 0 means "least important," and indicates that the data is entirely
   *                 optional. It is a number between 0 and 1.
   */
  @JsonInclude(JsonInclude.Include.NON_ABSENT) final case  class Annotations(@JsonProperty("audience") audience: util.List[McpSchema.Role], @JsonProperty("priority") priority: Double)
  //{
//    this.audience = audience
//    this.priority = priority
//    @JsonProperty("audience") final private val audience = null
//    @JsonProperty("priority") final private val priority = .0
    // @formatter:on
 // }

  /**
   * A known resource that the server is capable of reading.
   *
   * @param uri         the URI of the resource.
   * @param name        A human-readable name for this resource. This can be used by clients to
   *                    populate UI elements.
   * @param description A description of what this resource represents. This can be used
   *                    by clients to improve the LLM's understanding of available resources. It can be
   *                    thought of like a "hint" to the model.
   * @param mimeType    The MIME type of this resource, if known.
   * @param annotations Optional annotations for the client. The client can use
   *                    annotations to inform how objects are used or displayed.
   */
  @JsonInclude(JsonInclude.Include.NON_ABSENT)
  @JsonIgnoreProperties(ignoreUnknown = true) final case  class Resource(@JsonProperty("uri") uri: String, @JsonProperty("name") name: String, @JsonProperty("description") description: String, @JsonProperty("mimeType") mimeType: String, @JsonProperty("annotations") annotations: McpSchema.Annotations) extends McpSchema.Annotated
  //{
//    this.uri = uri
//    this.name = name
//    this.description = description
//    this.mimeType = mimeType
//    this.annotations = annotations
//    @JsonProperty("uri") final private val uri = null
//    @JsonProperty("name") final private val name = null
//    @JsonProperty("description") final private val description = null
//    @JsonProperty("mimeType") final private val mimeType = null
//    @JsonProperty("annotations") final private val annotations = null
    // @formatter:on
 // }

  /**
   * Resource templates allow servers to expose parameterized resources using URI
   * templates.
   *
   * @param uriTemplate A URI template that can be used to generate URIs for this
   *                    resource.
   * @param name        A human-readable name for this resource. This can be used by clients to
   *                    populate UI elements.
   * @param description A description of what this resource represents. This can be used
   *                    by clients to improve the LLM's understanding of available resources. It can be
   *                    thought of like a "hint" to the model.
   * @param mimeType    The MIME type of this resource, if known.
   * @param annotations Optional annotations for the client. The client can use
   *                    annotations to inform how objects are used or displayed.
   * @see <a href="https://datatracker.ietf.org/doc/html/rfc6570">RFC 6570</a>
   */
  @JsonInclude(JsonInclude.Include.NON_ABSENT) final case  class ResourceTemplate(@JsonProperty("uriTemplate") uriTemplate: String, @JsonProperty("name") name: String, @JsonProperty("description") description: String, @JsonProperty("mimeType") mimeType: String, @JsonProperty("annotations") annotations: McpSchema.Annotations) extends McpSchema.Annotated
  // {
//    this.uriTemplate = uriTemplate
//    this.name = name
//    this.description = description
//    this.mimeType = mimeType
//    this.annotations = annotations
//    @JsonProperty("uriTemplate") final private val uriTemplate = null
//    @JsonProperty("name") final private val name = null
//    @JsonProperty("description") final private val description = null
//    @JsonProperty("mimeType") final private val mimeType = null
//    @JsonProperty("annotations") final private val annotations = null
    // @formatter:on
//  }

  @JsonInclude(JsonInclude.Include.NON_ABSENT)
  @JsonIgnoreProperties(ignoreUnknown = true) final case  class ListResourcesResult(@JsonProperty("resources") resources: util.List[McpSchema.Resource], @JsonProperty("nextCursor") nextCursor: String) //{
//    this.resources = resources
//    this.nextCursor = nextCursor
//    @JsonProperty("resources") final private val resources = null
//    @JsonProperty("nextCursor") final private val nextCursor = null
    // @formatter:on
 // }

  @JsonInclude(JsonInclude.Include.NON_ABSENT)
  @JsonIgnoreProperties(ignoreUnknown = true) final case class ListResourceTemplatesResult(@JsonProperty("resourceTemplates") resourceTemplates: util.List[McpSchema.ResourceTemplate], @JsonProperty("nextCursor") nextCursor: String) // {
//    this.resourceTemplates = resourceTemplates
//    this.nextCursor = nextCursor
//    @JsonProperty("resourceTemplates") final private val resourceTemplates = null
//    @JsonProperty("nextCursor") final private val nextCursor = null
    // @formatter:on
 // }

  @JsonInclude(JsonInclude.Include.NON_ABSENT) final case  class ReadResourceRequest(@JsonProperty("uri") uri: String) // {
//    this.uri = uri
//    @JsonProperty("uri") final private val uri = null
    // @formatter:on
  //}

  @JsonInclude(JsonInclude.Include.NON_ABSENT)
  @JsonIgnoreProperties(ignoreUnknown = true) final case  class ReadResourceResult(@JsonProperty("contents") contents: util.List[McpSchema.ResourceContents]) // {
//    this.contents = contents
//    @JsonProperty("contents") final private val contents = null
    // @formatter:on
  //}

  /**
   * Sent from the client to request resources/updated notifications from the server
   * whenever a particular resource changes.
   *
   * @param uri the URI of the resource to subscribe to. The URI can use any protocol;
   *            it is up to the server how to interpret it.
   */
  @JsonInclude(JsonInclude.Include.NON_ABSENT) final case class SubscribeRequest(@JsonProperty("uri") uri: String)  //{
//    this.uri = uri
//    @JsonProperty("uri") final private val uri = null
    // @formatter:on
  //}

  @JsonInclude(JsonInclude.Include.NON_ABSENT) final case class UnsubscribeRequest(@JsonProperty("uri") uri: String) // {
//    this.uri = uri
//    @JsonProperty("uri") final private val uri = null
    // @formatter:on
 // }

  /**
   * The contents of a specific resource or sub-resource.
   */
  @JsonTypeInfo(use = JsonTypeInfo.Id.DEDUCTION, include = As.PROPERTY)
  @JsonSubTypes(Array(new JsonSubTypes.Type(value = classOf[McpSchema.TextResourceContents], name = "text"), new JsonSubTypes.Type(value = classOf[McpSchema.BlobResourceContents], name = "blob")))
  trait ResourceContents {
    /**
     * The URI of this resource.
     *
     * @return the URI of this resource.
     */
    def uri: String

    /**
     * The MIME type of this resource.
     *
     * @return the MIME type of this resource.
     */
    def mimeType: String
  }

  /**
   * Text contents of a resource.
   *
   * @param uri      the URI of this resource.
   * @param mimeType the MIME type of this resource.
   * @param text     the text of the resource. This must only be set if the resource can
   *                 actually be represented as text (not binary data).
   */
  @JsonInclude(JsonInclude.Include.NON_ABSENT)
  @JsonIgnoreProperties(ignoreUnknown = true)
  final case class TextResourceContents(@JsonProperty("uri") uri: String, @JsonProperty("mimeType") mimeType: String, @JsonProperty("text") text: String) extends McpSchema.ResourceContents //{
//    this.uri = uri
//    this.mimeType = mimeType
//    this.text = text
//    @JsonProperty("uri") final private val uri = null
//    @JsonProperty("mimeType") final private val mimeType = null
//    @JsonProperty("text") final private val text = null
    // @formatter:on
 // }

  /**
   * Binary contents of a resource.
   *
   * @param uri      the URI of this resource.
   * @param mimeType the MIME type of this resource.
   * @param blob     a base64-encoded string representing the binary data of the resource.
   *                 This must only be set if the resource can actually be represented as binary data
   *                 (not text).
   */
  @JsonInclude(JsonInclude.Include.NON_ABSENT)
  @JsonIgnoreProperties(ignoreUnknown = true)
  final case  class BlobResourceContents(@JsonProperty("uri") uri: String, @JsonProperty("mimeType") mimeType: String, @JsonProperty("blob") blob: String) extends McpSchema.ResourceContents // {
//    this.uri = uri
//    this.mimeType = mimeType
//    this.blob = blob
//    @JsonProperty("uri") final private val uri = null
//    @JsonProperty("mimeType") final private val mimeType = null
//    @JsonProperty("blob") final private val blob = null
    // @formatter:on
//  }

  /**
   * A prompt or prompt template that the server offers.
   *
   * @param name        The name of the prompt or prompt template.
   * @param description An optional description of what this prompt provides.
   * @param arguments   A list of arguments to use for templating the prompt.
   */
  // ---------------------------
  // Prompt Interfaces
  // ---------------------------
  @JsonInclude(JsonInclude.Include.NON_ABSENT)
  final case class Prompt(@JsonProperty("name") name: String, @JsonProperty("description") description: String, @JsonProperty("arguments") arguments: util.List[McpSchema.PromptArgument]) //{
//    this.name = name
//    this.description = description
//    this.arguments = arguments
//    @JsonProperty("name") final private val name = null
//    @JsonProperty("description") final private val description = null
//    @JsonProperty("arguments") final private val arguments = null
    // @formatter:on
 // }

  /**
   * Describes an argument that a prompt can accept.
   *
   * @param name        The name of the argument.
   * @param description A human-readable description of the argument.
   * @param required    Whether this argument must be provided.
   */
  @JsonInclude(JsonInclude.Include.NON_ABSENT)
  final case class PromptArgument(@JsonProperty("name") name: String, @JsonProperty("description") description: String, @JsonProperty("required") required: Boolean = false) //{
//    this.name = name
//    this.description = description
//    this.required = required
//    @JsonProperty("name") final private val name = null
//    @JsonProperty("description") final private val description = null
//    @JsonProperty("required") final private val required = false
    // @formatter:on
 // }

  /**
   * Describes a message returned as part of a prompt.
   *
   * This is similar to `SamplingMessage`, but also supports the embedding of resources
   * from the MCP server.
   *
   * @param role    The sender or recipient of messages and data in a conversation.
   * @param content The content of the message of type {@link Content}.
   */
  @JsonInclude(JsonInclude.Include.NON_ABSENT)
  final case  class PromptMessage(@JsonProperty("role") role: McpSchema.Role, @JsonProperty("content") content: McpSchema.Content)
  //{
//    this.role = role
//    this.content = content
//    @JsonProperty("role") final private val role = null
//    @JsonProperty("content") final private val content = null
    // @formatter:on
 // }

  /**
   * The server's response to a prompts/list request from the client.
   *
   * @param prompts    A list of prompts that the server provides.
   * @param nextCursor An optional cursor for pagination. If present, indicates there
   *                   are more prompts available.
   */
  @JsonInclude(JsonInclude.Include.NON_ABSENT)
  @JsonIgnoreProperties(ignoreUnknown = true)
  final case class ListPromptsResult(@JsonProperty("prompts") prompts: util.List[McpSchema.Prompt], @JsonProperty("nextCursor") nextCursor: String)
  //{
//    this.prompts = prompts
//    this.nextCursor = nextCursor
//    @JsonProperty("prompts") final private val prompts = null
//    @JsonProperty("nextCursor") final private val nextCursor = null
    // @formatter:on
 // }

  /**
   * Used by the client to get a prompt provided by the server.
   *
   * @param name      The name of the prompt or prompt template.
   * @param arguments Arguments to use for templating the prompt.
   */
  @JsonInclude(JsonInclude.Include.NON_ABSENT)
  final case  class GetPromptRequest(@JsonProperty("name") name: String, @JsonProperty("arguments") arguments: util.Map[String, AnyRef]) extends McpSchema.Request // {
//    this.name = name
//    this.arguments = arguments
//    @JsonProperty("name") final private val name = null
//    @JsonProperty("arguments") final private val arguments = null
    // @formatter:off
//}
/**
	 * The server's response to a prompts/get request from the client.
	 *
	 * @param description An optional description for the prompt.
	 * @param messages A list of messages to display as part of the prompt.
	 */
@JsonInclude(JsonInclude.Include.NON_ABSENT)
@JsonIgnoreProperties(ignoreUnknown = true)  final case  class GetPromptResult  (@JsonProperty("description")  description: String, @JsonProperty("messages")  messages: util.List[McpSchema.PromptMessage]) //{
//  this.description = description
//}
//this.messages = messages
//@JsonProperty("description")  final private val description  = null
//@JsonProperty("messages")  final private val messages  = null
// @formatter:on
//}

  /**
   * The server's response to a tools/list request from the client.
   *
   * @param tools      A list of tools that the server provides.
   * @param nextCursor An optional cursor for pagination. If present, indicates there
   *                   are more tools available.
   */
  // ---------------------------
  // Tool Interfaces
  // ---------------------------
  @JsonInclude(JsonInclude.Include.NON_ABSENT)
  @JsonIgnoreProperties(ignoreUnknown = true)
  final case  class ListToolsResult(@JsonProperty("tools") tools: util.List[McpSchema.Tool], @JsonProperty("nextCursor") nextCursor: String) // {
//    this.tools = tools
//    this.nextCursor = nextCursor
//    @JsonProperty("tools") final private val tools = null
//    @JsonProperty("nextCursor") final private val nextCursor = null
    // @formatter:on
 // }

//  @JsonInclude(JsonInclude.Include.NON_ABSENT)
//  @JsonIgnoreProperties(ignoreUnknown = true)
//  final private[spec] class JsonSchema private[spec](@JsonProperty("type") `type`: String, @JsonProperty("properties") properties: util.Map[String, AnyRef], @JsonProperty("required") required: util.List[String], @JsonProperty("additionalProperties") additionalProperties: Boolean) // {

//    this.`type` = `type`
//    this.properties = properties
//    this.required = required
//    this.additionalProperties = additionalProperties
//    @JsonProperty("type") final private val `type` = null
//    @JsonProperty("properties") final private val properties = null
//    @JsonProperty("required") final private val required = null
//    @JsonProperty("additionalProperties") final private val additionalProperties = false
    // @formatter:on
  //}

  /**
   * Represents a tool that the server provides. Tools enable servers to expose
   * executable functionality to the system. Through these tools, you can interact with
   * external systems, perform computations, and take actions in the real world.
   *
   * @param name        A unique identifier for the tool. This name is used when calling the
   *                    tool.
   * @param description A human-readable description of what the tool does. This can be
   *                    used by clients to improve the LLM's understanding of available tools.
   * @param inputSchema A JSON Schema object that describes the expected structure of
   *                    the arguments when calling this tool. This allows clients to validate tool
   *                    arguments before sending them to the server.
   */
//  @JsonInclude(JsonInclude.Include.NON_ABSENT)
//  final case class Tool(@JsonProperty("name") name: String, @JsonProperty("description") description: String, @JsonProperty("inputSchema") inputSchema: McpSchema.JsonSchema)
//
  //{
//    this.name = name
//    this.description = description
//    this.inputSchema = inputSchema
//    @JsonProperty("name") final private val name = null
//    @JsonProperty("description") final private val description = null
//    @JsonProperty("inputSchema") final private val inputSchema = null

//    def this(name: String, description: String, schema: String) {
//      this(name, description, parseSchema(schema))
//    }
    // @formatter:on
 // }

  def parseSchema(schema: String,objectMapper: ObjectMapper) =
      try objectMapper.readValue(schema, classOf[McpSchema.JsonSchema])
      catch {
        case e: IOException =>
          throw new IllegalArgumentException("Invalid schema: " + schema, e)
      }

  /**
   * Used by the client to call a tool provided by the server.
   *
   * @param name      The name of the tool to call. This must match a tool name from
   *                  tools/list.
   * @param arguments Arguments to pass to the tool. These must conform to the tool's
   *                  input schema.
   */
  @JsonInclude(JsonInclude.Include.NON_ABSENT)
  @JsonIgnoreProperties(ignoreUnknown = true)
  final case class CallToolRequest(@JsonProperty("name") name: String, @JsonProperty("arguments") arguments: util.Map[String, AnyRef]) extends McpSchema.Request
  //{
//    this.name = name
//    this.arguments = arguments
//    @JsonProperty("name") final private val name = null
//    @JsonProperty("arguments") final private val arguments = null
    // @formatter:off
//}
/**
	 * The server's response to a tools/call request from the client.
	 *
	 * @param content A list of content items representing the tool's output. Each item can be text, an image,
	 *                or an embedded resource.
	 * @param isError If true, indicates that the tool execution failed and the content contains error information.
	 *                If false or absent, indicates successful execution.
	 */
@JsonInclude(JsonInclude.Include.NON_ABSENT)  @JsonIgnoreProperties(ignoreUnknown = true)
final case class CallToolResult  (@JsonProperty("content")  content: util.List[McpSchema.Content], @JsonProperty("isError")  isError: Boolean) // {
//  this.content = content
//this.isError = isError
//@JsonProperty("content")  final private val content  = null
//@JsonProperty("isError")  final private val isError  = false
// @formatter:on
//}

  // ---------------------------
  // Sampling Interfaces
  // ---------------------------
//  @JsonInclude(JsonInclude.Include.NON_ABSENT)
//  final case  class ModelPreferences(@JsonProperty("hints") hints: util.List[McpSchema.ModelHint], @JsonProperty("costPriority") costPriority: Double, @JsonProperty("speedPriority") speedPriority: Double, @JsonProperty("intelligencePriority") intelligencePriority: Double)
//
  //{
//    this.hints = hints
//    this.costPriority = costPriority
//    this.speedPriority = speedPriority
//    this.intelligencePriority = intelligencePriority
//    @JsonProperty("hints") final private val hints = null
//    @JsonProperty("costPriority") final private val costPriority = .0
//    @JsonProperty("speedPriority") final private val speedPriority = .0
//    @JsonProperty("intelligencePriority") final private val intelligencePriority = .0
    // @formatter:on
 // }

//  @JsonInclude(JsonInclude.Include.NON_ABSENT)
//  final case class ModelHint(@JsonProperty("name") name: String) //{
////    this.name = name
////    @JsonProperty("name") final private val name = null
//  //}
//
//  @JsonInclude(JsonInclude.Include.NON_ABSENT)
//  final case class SamplingMessage(@JsonProperty("role") role: McpSchema.Role, @JsonProperty("content") content: McpSchema.Content) // {
//    this.role = role
//    this.content = content
//    @JsonProperty("role") final private val role = null
//    @JsonProperty("content") final private val content = null
    // @formatter:on
 // }

  // Sampling and Message Creation
  @JsonInclude(JsonInclude.Include.NON_ABSENT) object CreateMessageRequest {
    enum ContextInclusionStrategy:
      case NONE, THIS_SERVER, ALL_SERVERS
//    object ContextInclusionStrategy extends Enumeration {
//      type ContextInclusionStrategy = Value
//      val NONE, THIS_SERVER, ALL_SERVERS = Value
//    }
  }

  @JsonInclude(JsonInclude.Include.NON_ABSENT)
  final class CreateMessageRequest(@JsonProperty("messages") messages: util.List[McpSchema.SamplingMessage],
                                        @JsonProperty("modelPreferences") modelPreferences: McpSchema.ModelPreferences,
                                        @JsonProperty("systemPrompt") systemPrompt: String,
                                        @JsonProperty("includeContext") includeContext: CreateMessageRequest.ContextInclusionStrategy,
                                        @JsonProperty("temperature") temperature: Double,
                                        @JsonProperty("maxTokens") maxTokens: Int,
                                        @JsonProperty("stopSequences") stopSequences: util.List[String],
                                        @JsonProperty("metadata") metadata: util.Map[String, AnyRef]) extends McpSchema.Request
//{
//    this.messages = messages
//    this.modelPreferences = modelPreferences
//    this.systemPrompt = systemPrompt
//    this.includeContext = includeContext
//    this.temperature = temperature
//    this.maxTokens = maxTokens
//    this.stopSequences = stopSequences
//    this.metadata = metadata
//    @JsonProperty("messages") final private val messages = null
//    @JsonProperty("modelPreferences") final private val modelPreferences = null
//    @JsonProperty("systemPrompt") final private val systemPrompt = null
//    @JsonProperty("includeContext") final private val includeContext = null
//    @JsonProperty("temperature") final private val temperature = .0
//    @JsonProperty("maxTokens") final private val maxTokens = 0
//    @JsonProperty("stopSequences") final private val stopSequences = null
//    @JsonProperty("metadata") final private val metadata = null
    // @formatter:on
 // }

  @JsonInclude(JsonInclude.Include.NON_ABSENT)
  @JsonIgnoreProperties(ignoreUnknown = true)
  object CreateMessageResult {
    enum StopReason:
      case END_TURN, STOP_SEQUENCE, MAX_TOKENS
//    object StopReason extends Enumeration {
//      type StopReason = Value
//      val END_TURN, STOP_SEQUENCE, MAX_TOKENS = Value
//    }

    def builder = new CreateMessageResult.Builder

    class Builder {
      private var role = Role.ASSISTANT
      private var content: McpSchema.Content = null
      private var model: String = null
      private var stopReason = StopReason.END_TURN

      def role(role: McpSchema.Role): CreateMessageResult.Builder = {
        this.role = role
        this
      }

      def content(content: McpSchema.Content): CreateMessageResult.Builder = {
        this.content = content
        this
      }

      def model(model: String): CreateMessageResult.Builder = {
        this.model = model
        this
      }

      def stopReason(stopReason: CreateMessageResult.StopReason): CreateMessageResult.Builder = {
        this.stopReason = stopReason
        this
      }

//      @JsonInclude(JsonInclude.Include.NON_ABSENT)
//      final case class TextContent(@JsonProperty("audience") audience: util.List[McpSchema.Role], @JsonProperty("priority") priority: Double, @JsonProperty("text") text: String) extends McpSchema.Content

      def message(message: String): CreateMessageResult.Builder = {
        this.content = new McpSchema.TextContent(null,0,message)
        this
      }

      def build = new McpSchema.CreateMessageResult(role, content, model, stopReason)
    }
  }

  @JsonInclude(JsonInclude.Include.NON_ABSENT)
  @JsonIgnoreProperties(ignoreUnknown = true)
  final case  class CreateMessageResult(@JsonProperty("role") role: McpSchema.Role,
                                        @JsonProperty("content") content: McpSchema.Content,
                                        @JsonProperty("model") model: String,
                                        @JsonProperty("stopReason") stopReason: CreateMessageResult.StopReason)
  //{
//    this.role = role
//    this.content = content
//    this.model = model
//    this.stopReason = stopReason
//    @JsonProperty("role") final private val role = null
//    @JsonProperty("content") final private val content = null
//    @JsonProperty("model") final private val model = null
//    @JsonProperty("stopReason") final private val stopReason = null
    // @formatter:on
 // }

  // ---------------------------
  // Pagination Interfaces
  // ---------------------------
  @JsonInclude(JsonInclude.Include.NON_ABSENT) final case class PaginatedRequest(@JsonProperty("cursor") cursor: String)
  //{
//    this.cursor = cursor
//    @JsonProperty("cursor") final private val cursor = null
//  }

  @JsonInclude(JsonInclude.Include.NON_ABSENT)
  @JsonIgnoreProperties(ignoreUnknown = true) final case  class PaginatedResult(@JsonProperty("nextCursor") nextCursor: String)
  //{
//    this.nextCursor = nextCursor
//    @JsonProperty("nextCursor") final private val nextCursor = null
 // }

  // ---------------------------
  // Progress and Logging
  // ---------------------------
  final case  class ProgressNotification(@JsonProperty("progressToken") progressToken: String,
                                         @JsonProperty("progress") progress: Double,
                                         @JsonProperty("total") total: Double)
  //{
//    this.progressToken = progressToken
//    this.progress = progress
//    this.total = total
//    @JsonProperty("progressToken") final private val progressToken = null
//    @JsonProperty("progress") final private val progress = .0
//    @JsonProperty("total") final private val total = .0
    // @formatter:on
 // }

  /**
   * The Model Context Protocol (MCP) provides a standardized way for servers to send
   * structured log messages to clients. Clients can control logging verbosity by
   * setting minimum log levels, with servers sending notifications containing severity
   * levels, optional logger names, and arbitrary JSON-serializable data.
   *
   * @param level  The severity levels. The mimimum log level is set by the client.
   * @param logger The logger that generated the message.
   * @param data   JSON-serializable logging data.
   */
  object LoggingMessageNotification {
    def builder = new LoggingMessageNotification.Builder

    class Builder {
      private var level = LoggingLevel.INFO
      private var logger = "server"
      private var data: String = null

      def level(level: McpSchema.LoggingLevel): LoggingMessageNotification.Builder = {
        this.level = level
        this
      }

      def logger(logger: String): LoggingMessageNotification.Builder = {
        this.logger = logger
        this
      }

      def data(data: String): LoggingMessageNotification.Builder = {
        this.data = data
        this
      }

      def build = new McpSchema.LoggingMessageNotification(level, logger, data)
    }
  }

  final case  class LoggingMessageNotification(@JsonProperty("level") level: McpSchema.LoggingLevel, @JsonProperty("logger") logger: String, @JsonProperty("data") data: String) // {
//    this.level = level
//    this.logger = logger
//    this.data = data
//    @JsonProperty("level") final private val level = null
//    @JsonProperty("logger") final private val logger = null
//    @JsonProperty("data") final private val data = null
    // @formatter:on
 // }

  enum LoggingLevel:
    case DEBUG,INFO,NOTICE,WARNING,ERROR,CRITICAL,ALERT,EMERGENCY
//  object LoggingLevel extends Enumeration {
//    type LoggingLevel = Value
//    val // @formatter:off
//    DEBUG,INFO,NOTICE,WARNING,ERROR,CRITICAL,ALERT,EMERGENCY = Value
//    private val level  = 0
//    def this(level: Int) { this()
//    this.level = level
//}

    def level: Int = level // @formatter:on
  }

  // ---------------------------
  // Autocomplete
  // ---------------------------
  object CompleteRequest {
    trait PromptOrResourceReference {
      def `type`: String
    }

    final case class PromptReference(@JsonProperty("type") `type`: String, @JsonProperty("name") name: String) extends CompleteRequest.PromptOrResourceReference  //{
//      this.`type` = `type`
//      this.name = name
//      @JsonProperty("type") final private val `type` = null
//      @JsonProperty("name") final private val name = null
      // @formatter:on
  //  }

    final case class ResourceReference(@JsonProperty("type") `type`: String, @JsonProperty("uri") uri: String) extends CompleteRequest.PromptOrResourceReference //{
//      this.`type` = `type`
//      this.uri = uri
//      @JsonProperty("type") final private val `type` = null
//      @JsonProperty("uri") final private val uri = null
      // @formatter:on
    //}

    final case class CompleteArgument(@JsonProperty("name") name: String, @JsonProperty("value") value: String) //{
//      this.name = name
//      this.value = value
//      @JsonProperty("name") final private val name = null
//      @JsonProperty("value") final private val value = null
      // @formatter:on
   // }
  }

  final case  class CompleteRequest(ref: CompleteRequest.PromptOrResourceReference, argument: CompleteRequest.CompleteArgument) extends McpSchema.Request // {
//    this.ref = ref
//    this.argument = argument
//    final private val ref: CompleteRequest.PromptOrResourceReference = null
//    final private val argument: CompleteRequest.CompleteArgument = null
 // }

  object CompleteResult {
    final case class CompleteCompletion(@JsonProperty("values") values: util.List[String],
                                   @JsonProperty("total") total: Integer,
                                   @JsonProperty("hasMore") hasMore: Boolean)  //{
//      this.values = values
//      this.total = total
//      this.hasMore = hasMore
//      @JsonProperty("values") final private val values = null
//      @JsonProperty("total") final private val total = null
//      @JsonProperty("hasMore") final private val hasMore = false
      // @formatter:on
   // }
  }

  final case  class CompleteResult(completion: CompleteResult.CompleteCompletion) //{
//    this.completion = completion
//    final private val completion: CompleteResult.CompleteCompletion = null
 // }

  // ---------------------------
  // Content Types
  // ---------------------------
//  @JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "type")
//  @JsonSubTypes(Array(new JsonSubTypes.Type(value = classOf[McpSchema.TextContent], name = "text"), new JsonSubTypes.Type(value = classOf[McpSchema.ImageContent], name = "image"), new JsonSubTypes.Type(value = classOf[McpSchema.EmbeddedResource], name = "resource")))
//  trait Content {
//    def `type`: String = {
//      if (this.isInstanceOf[McpSchema.TextContent]) return "text"
//      else if (this.isInstanceOf[McpSchema.ImageContent]) return "image"
//      else if (this.isInstanceOf[McpSchema.EmbeddedResource]) return "resource"
//      throw new IllegalArgumentException("Unknown content type: " + this)
//    }
//  }
//
//  @JsonInclude(JsonInclude.Include.NON_ABSENT)
//  final case class TextContent(@JsonProperty("audience") audience: util.List[McpSchema.Role], @JsonProperty("priority") priority: Double, @JsonProperty("text") text: String) extends McpSchema.Content
//  //// {
////    this.audience = audience
////    this.priority = priority
////    this.text = text
////    @JsonProperty("audience") final private val audience = null
////    @JsonProperty("priority") final private val priority = .0
////    @JsonProperty("text") final private val text = null
//
//    // @formatter:on
////    def this(content: String) {
////      this(null, null, content)
////    }
//  //}
//
//  @JsonInclude(JsonInclude.Include.NON_ABSENT)
//  final case  class ImageContent(@JsonProperty("audience") audience: util.List[McpSchema.Role], @JsonProperty("priority") priority: Double, @JsonProperty("data") data: String, @JsonProperty("mimeType") mimeType: String) extends McpSchema.Content
//  //{
////    this.audience = audience
////    this.priority = priority
////    this.data = data
////    this.mimeType = mimeType
////    @JsonProperty("audience") final private val audience = null
////    @JsonProperty("priority") final private val priority = .0
////    @JsonProperty("data") final private val data = null
////    @JsonProperty("mimeType") final private val mimeType = null
//    // @formatter:on
//  //}
//
//  @JsonInclude(JsonInclude.Include.NON_ABSENT)
//  final case  class EmbeddedResource(@JsonProperty("audience") audience: util.List[McpSchema.Role],
//                                     @JsonProperty("priority") priority: Double,
//                                     @JsonProperty("resource") resource: McpSchema.ResourceContents) extends McpSchema.Content //{
//    this.audience = audience
//    this.priority = priority
//    this.resource = resource
//    @JsonProperty("audience") final private val audience = null
//    @JsonProperty("priority") final private val priority = .0
//    @JsonProperty("resource") final private val resource = null
    // @formatter:on
 // }

  /**
   * Represents a root directory or file that the server can operate on.
   *
   * @param uri  The URI identifying the root. This *must* start with file:// for now.
   *             This restriction may be relaxed in future versions of the protocol to allow other
   *             URI schemes.
   * @param name An optional name for the root. This can be used to provide a
   *             human-readable identifier for the root, which may be useful for display purposes or
   *             for referencing the root in other parts of the application.
   */
  // ---------------------------
  // Roots
  // ---------------------------
//  @JsonInclude(JsonInclude.Include.NON_ABSENT)
//  final case class Root(@JsonProperty("uri") uri: String, @JsonProperty("name") name: String)
  //{
//    this.uri = uri
//    this.name = name
//    @JsonProperty("uri") final private val uri = null
//    @JsonProperty("name") final private val name = null
    // @formatter:on
 // }

  /**
   * The client's response to a roots/list request from the server. This result contains
   * an array of Root objects, each representing a root directory or file that the
   * server can operate on.
   *
   * @param roots An array of Root objects, each representing a root directory or file
   *              that the server can operate on.
   */
//  @JsonInclude(JsonInclude.Include.NON_ABSENT)
//  @JsonIgnoreProperties(ignoreUnknown = true)
//  final case  class ListRootsResult(@JsonProperty("roots") roots: util.List[McpSchema.Root]) //{


//    this.roots = roots
//    @JsonProperty("roots") final private val roots = null
    // @formatter:on
  //}
//}

//final class McpSchema private {
//}