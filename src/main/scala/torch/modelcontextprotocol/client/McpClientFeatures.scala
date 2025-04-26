/*
 * Copyright 2024-2024 the original author or authors.
 */
package torch.modelcontextprotocol.client

import reactor.core.publisher.Mono
import reactor.core.scheduler.Schedulers
import torch.modelcontextprotocol.spec.McpSchema

import java.util
import java.util.function.Consumer
import scala.collection.mutable
import scala.collection.mutable.ListBuffer
import scala.jdk.CollectionConverters.*

/**
 * Representation of features and capabilities for Model Context Protocol (MCP) clients.
 * This class provides two record types for managing client features:
 * <ul>
 * <li>{@link Async} for non-blocking operations with Project Reactor's Mono responses
 * <li>{@link Sync} for blocking operations with direct responses
 * </ul>
 *
 * <p>
 * Each feature specification includes:
 * <ul>
 * <li>Client implementation information and capabilities
 * <li>Root URI mappings for resource access
 * <li>Change notification handlers for tools, resources, and prompts
 * <li>Logging message consumers
 * <li>Message sampling handlers for request processing
 * </ul>
 *
 * <p>
 * The class supports conversion between synchronous and asynchronous specifications
 * through the {@link Async# fromSync} method, which ensures proper handling of blocking
 * operations in non-blocking contexts by scheduling them on a bounded elastic scheduler.
 *
 * @author Dariusz Jędrzejczyk
 * @see McpClient
 * @see McpSchema.Implementation
 * @see McpSchema.ClientCapabilities
 */
object McpClientFeatures {
  final case  class Sync(clientInfo: McpSchema.Implementation,
                         clientCapabilities: McpSchema.ClientCapabilities,
                         roots: mutable.Map[String, McpSchema.Root],
                         toolsChangeConsumers: List[Consumer[List[McpSchema.Tool]]],
                         resourcesChangeConsumers: List[Consumer[List[McpSchema.Resource]]],
                         promptsChangeConsumers: List[Consumer[List[McpSchema.Prompt]]],
                         loggingConsumers: List[Consumer[McpSchema.LoggingMessageNotification]],
                         samplingHandler: Function[McpSchema.CreateMessageRequest, McpSchema.CreateMessageResult])

  final case  class Async(clientInfo: McpSchema.Implementation, 
                                    clientCapabilities: McpSchema.ClientCapabilities,
                          roots: mutable.Map[String, McpSchema.Root],
                          toolsChangeConsumers: List[Function[List[McpSchema.Tool], Mono[Void]]],
                          resourcesChangeConsumers: List[Function[List[McpSchema.Resource], Mono[Void]]],
                          promptsChangeConsumers: List[Function[List[McpSchema.Prompt], Mono[Void]]],
                          loggingConsumers: List[Function[McpSchema.LoggingMessageNotification, Mono[Void]]],
                                    samplingHandler: Function[McpSchema.CreateMessageRequest, Mono[McpSchema.CreateMessageResult]]) 
  
  /**
   * Asynchronous client features specification providing the capabilities and request
   * and notification handlers.
   *
   * @param clientInfo               the client implementation information.
   * @param clientCapabilities       the client capabilities.
   * @param roots                    the roots.
   * @param toolsChangeConsumers     the tools change consumers.
   * @param resourcesChangeConsumers the resources change consumers.
   * @param promptsChangeConsumers   the prompts change consumers.
   * @param loggingConsumers         the logging consumers.
   * @param samplingHandler          the sampling handler.
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
      def fromSync(syncSpec: McpClientFeatures.Sync): McpClientFeatures.Async = {
        val toolsChangeConsumers = new ListBuffer[Function[List[McpSchema.Tool], Mono[Void]]]
//        import scala.collection.JavaConversions.*
        for (consumer <- syncSpec.toolsChangeConsumers) {
          toolsChangeConsumers.append((t: List[McpSchema.Tool]) => Mono.fromRunnable[Void](() => consumer.accept(t)).subscribeOn(Schedulers.boundedElastic))
        }
        val resourcesChangeConsumers = new ListBuffer[Function[List[McpSchema.Resource], Mono[Void]]]
//        import scala.collection.JavaConversions.*
        for (consumer <- syncSpec.resourcesChangeConsumers) {
          resourcesChangeConsumers.append((r: List[McpSchema.Resource]) => Mono.fromRunnable[Void](() => consumer.accept(r)).subscribeOn(Schedulers.boundedElastic))
        }
        val promptsChangeConsumers = new ListBuffer[Function[List[McpSchema.Prompt], Mono[Void]]]
//        import scala.collection.JavaConversions.*
        for (consumer <- syncSpec.promptsChangeConsumers) {
          promptsChangeConsumers.append((p: List[McpSchema.Prompt]) => Mono.fromRunnable[Void](() => consumer.accept(p)).subscribeOn(Schedulers.boundedElastic))
        }
        val loggingConsumers = new ListBuffer[Function[McpSchema.LoggingMessageNotification, Mono[Void]]]
//        import scala.collection.JavaConversions.*
        for (consumer <- syncSpec.loggingConsumers) {
          loggingConsumers.append((l: McpSchema.LoggingMessageNotification) => Mono.fromRunnable[Void](() => consumer.accept(l)).subscribeOn(Schedulers.boundedElastic))
        }
        def samplingHandler:Function[McpSchema.CreateMessageRequest, Mono[McpSchema.CreateMessageResult]] = (r: McpSchema.CreateMessageRequest) => Mono.fromCallable(() => syncSpec.samplingHandler.apply(r)).subscribeOn(Schedulers.boundedElastic)

        new McpClientFeatures.Async(syncSpec.clientInfo, syncSpec.clientCapabilities, syncSpec.roots, toolsChangeConsumers.toList, resourcesChangeConsumers.toList, promptsChangeConsumers.toList, loggingConsumers.toList, samplingHandler)
      }
  }
  /**
   * Create an instance and validate the arguments.
   *
   * @param clientCapabilities       the client capabilities.
   * @param roots                    the roots.
   * @param toolsChangeConsumers     the tools change consumers.
   * @param resourcesChangeConsumers the resources change consumers.
   * @param promptsChangeConsumers   the prompts change consumers.
   * @param loggingConsumers         the logging consumers.
   * @param samplingHandler          the sampling handler.
   */ //{
//    Assert.notNull(clientInfo, "Client info must not be null")
//    this.clientCapabilities = if (clientCapabilities != null) clientCapabilities
//    else new McpSchema.ClientCapabilities(null, if (!Utils.isEmpty(roots)) new ClientCapabilities.RootCapabilities(false)
//    else null, if (samplingHandler != null) new ClientCapabilities.Sampling
//    else null)
//    this.roots = if (roots != null) new ConcurrentHashMap[String, McpSchema.Root](roots)
//    else new ConcurrentHashMap[String, McpSchema.Root]
//    this.toolsChangeConsumers = if (toolsChangeConsumers != null) toolsChangeConsumers
  //    else List.of
//    this.resourcesChangeConsumers = if (resourcesChangeConsumers != null) resourcesChangeConsumers
  //    else List.of
//    this.promptsChangeConsumers = if (promptsChangeConsumers != null) promptsChangeConsumers
  //    else List.of
//    this.loggingConsumers = if (loggingConsumers != null) loggingConsumers
  //    else List.of
//    final private var clientCapabilities: McpSchema.ClientCapabilities = null
  //    final private var roots: mutable.map[String, McpSchema.Root] = null
  //    final private var toolsChangeConsumers: List[Function[List[McpSchema.Tool], Mono[Void]]] = null
  //    final private var resourcesChangeConsumers: List[Function[List[McpSchema.Resource], Mono[Void]]] = null
  //    final private var promptsChangeConsumers: List[Function[List[McpSchema.Prompt], Mono[Void]]] = null
  //    final private var loggingConsumers: List[Function[McpSchema.LoggingMessageNotification, Mono[Void]]] = null
 // }

  /**
   * Synchronous client features specification providing the capabilities and request
   * and notification handlers.
   *
   * @param clientInfo               the client implementation information.
   * @param clientCapabilities       the client capabilities.
   * @param roots                    the roots.
   * @param toolsChangeConsumers     the tools change consumers.
   * @param resourcesChangeConsumers the resources change consumers.
   * @param promptsChangeConsumers   the prompts change consumers.
   * @param loggingConsumers         the logging consumers.
   * @param samplingHandler          the sampling handler.
   */
//  final case  class Sync(clientInfo: McpSchema.Implementation, 
//                   clientCapabilities: McpSchema.ClientCapabilities,
  //                   roots: mutable.map[String, McpSchema.Root],
  //                   toolsChangeConsumers: List[Consumer[List[McpSchema.Tool]]],
  //                   resourcesChangeConsumers: List[Consumer[List[McpSchema.Resource]]],
  //                   promptsChangeConsumers: List[Consumer[List[McpSchema.Prompt]]],
  //                   loggingConsumers: List[Consumer[McpSchema.LoggingMessageNotification]],
//                   private val samplingHandler: Function[McpSchema.CreateMessageRequest, McpSchema.CreateMessageResult])

  /**
   * Create an instance and validate the arguments.
   *
   * @param clientInfo               the client implementation information.
   * @param clientCapabilities       the client capabilities.
   * @param roots                    the roots.
   * @param toolsChangeConsumers     the tools change consumers.
   * @param resourcesChangeConsumers the resources change consumers.
   * @param promptsChangeConsumers   the prompts change consumers.
   * @param loggingConsumers         the logging consumers.
   * @param samplingHandler          the sampling handler.
   */ //{
//    Assert.notNull(clientInfo, "Client info must not be null")
//    this.clientCapabilities = if (clientCapabilities != null) clientCapabilities
//    else new McpSchema.ClientCapabilities(null, if (!Utils.isEmpty(roots)) new ClientCapabilities.RootCapabilities(false)
//    else null, if (samplingHandler != null) new ClientCapabilities.Sampling
//    else null)
//    this.roots = if (roots != null) new util.HashMap[String, McpSchema.Root](roots)
//    else new util.HashMap[String, McpSchema.Root]
//    this.toolsChangeConsumers = if (toolsChangeConsumers != null) toolsChangeConsumers
  //    else List.of
//    this.resourcesChangeConsumers = if (resourcesChangeConsumers != null) resourcesChangeConsumers
  //    else List.of
//    this.promptsChangeConsumers = if (promptsChangeConsumers != null) promptsChangeConsumers
  //    else List.of
//    this.loggingConsumers = if (loggingConsumers != null) loggingConsumers
  //    else List.of
//    final private var clientCapabilities: McpSchema.ClientCapabilities = null
  //    final private var roots: mutable.map[String, McpSchema.Root] = null
  //    final private var toolsChangeConsumers: List[Consumer[List[McpSchema.Tool]]] = null
  //    final private var resourcesChangeConsumers: List[Consumer[List[McpSchema.Resource]]] = null
  //    final private var promptsChangeConsumers: List[Consumer[List[McpSchema.Prompt]]] = null
  //    final private var loggingConsumers: List[Consumer[McpSchema.LoggingMessageNotification]] = null
 // }
}