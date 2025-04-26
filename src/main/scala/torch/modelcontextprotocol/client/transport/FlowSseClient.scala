/*
* Copyright 2024 - 2024 the original author or authors.
*/
package torch.modelcontextprotocol.client.transport

import java.net.URI
import java.net.http.{HttpClient, HttpRequest, HttpResponse}
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.{CompletableFuture, Flow}
import java.util.function.Function
import java.util.regex.Pattern

/**
 * A Server-Sent Events (SSE) client implementation using Java's Flow API for reactive
 * stream processing. This client establishes a connection to an SSE endpoint and
 * processes the incoming event stream, parsing SSE-formatted messages into structured
 * events.
 *
 * <p>
 * The client supports standard SSE event fields including:
 * <ul>
 * <li>event - The event type (defaults to "message" if not specified)</li>
 * <li>id - The event ID</li>
 * <li>data - The event payload data</li>
 * </ul>
 *
 * <p>
 * Events are delivered to a provided {@link SseEventHandler} which can process events and
 * handle any errors that occur during the connection.
 *
 * @author Christian Tzolov
 * @see SseEventHandler
 * @see SseEvent
 */
object FlowSseClient {
  /**
   * Pattern to extract the data content from SSE data field lines. Matches lines
   * starting with "data:" and captures the remaining content.
   */
    private val EVENT_DATA_PATTERN = Pattern.compile("^data:(.+)$", Pattern.MULTILINE)
  /**
   * Pattern to extract the event ID from SSE id field lines. Matches lines starting
   * with "id:" and captures the ID value.
   */
  private val EVENT_ID_PATTERN = Pattern.compile("^id:(.+)$", Pattern.MULTILINE)
  /**
   * Pattern to extract the event type from SSE event field lines. Matches lines
   * starting with "event:" and captures the event type.
   */
  private val EVENT_TYPE_PATTERN = Pattern.compile("^event:(.+)$", Pattern.MULTILINE)

  /**
   * Record class representing a Server-Sent Event with its standard fields.
   *
   * @param id   the event ID (may be null)
   * @param type the event type (defaults to "message" if not specified in the stream)
   * @param data the event payload data
   */
  case  class SseEvent(id: String, `type`: String, data: String)
//  {
//    this.id = id
//    this.`type` = `type`
//    this.data = data
//    final private val id: String = null
//    final private val `type`: String = null
//    final private val data: String = null
//  }

  /**
   * Interface for handling SSE events and errors. Implementations can process received
   * events and handle any errors that occur during the SSE connection.
   */
  trait SseEventHandler {
    /**
     * Called when an SSE event is received.
     *
     * @param event the received SSE event containing id, type, and data
     */
    def onEvent(event: FlowSseClient.SseEvent): Unit

    /**
     * Called when an error occurs during the SSE connection.
     *
     * @param error the error that occurred
     */
    def onError(error: Throwable): Unit
  }
}

class FlowSseClient(private val httpClient: HttpClient){

/**
 * Creates a new FlowSseClient with the specified HTTP client.
 *
 * @param httpClient the {@link HttpClient} instance to use for SSE connections
 */ 
  /**
   * Subscribes to an SSE endpoint and processes the event stream.
   *
   * <p>
   * This method establishes a connection to the specified URL and begins processing the
   * SSE stream. Events are parsed and delivered to the provided event handler. The
   * connection remains active until either an error occurs or the server closes the
   * connection.
   *
   * @param url          the SSE endpoint URL to connect to
   * @param eventHandler the handler that will receive SSE events and error
   *                     notifications
   * @throws RuntimeException if the connection fails with a non-200 status code
   */
  def subscribe(url: String, eventHandler: FlowSseClient.SseEventHandler): Unit = {
    val request = HttpRequest.newBuilder.uri(URI.create(url)).header("Accept", "text/event-stream").header("Cache-Control", "no-cache").GET.build
    val eventBuilder = new StringBuilder()
    val currentEventId = new AtomicReference[String]
    val currentEventType = new AtomicReference[String]("message")
    val lineSubscriber = new Flow.Subscriber[String]() {
      private var subscription: Flow.Subscription = _ // null

      override def onSubscribe(subscription: Flow.Subscription): Unit = {
        this.subscription = subscription
        subscription.request(Long.MaxValue)
      }

      override def onNext(line: String): Unit = {
        if (line.isEmpty) {
          // Empty line means end of event
          if (eventBuilder.length > 0) {
            val eventData = eventBuilder.toString
            val event = new FlowSseClient.SseEvent(currentEventId.get, currentEventType.get, eventData.trim)
            eventHandler.onEvent(event)
            eventBuilder.setLength(0)
          }
        }
        else if (line.startsWith("data:")) {
          val matcher = FlowSseClient.EVENT_DATA_PATTERN.matcher(line)
          if (matcher.find) eventBuilder.append(matcher.group(1).trim).append("\n")
        }
        else if (line.startsWith("id:")) {
          val matcher = FlowSseClient.EVENT_ID_PATTERN.matcher(line)
          if (matcher.find) currentEventId.set(matcher.group(1).trim)
        }
        else if (line.startsWith("event:")) {
          val matcher = FlowSseClient.EVENT_TYPE_PATTERN.matcher(line)
          if (matcher.find) currentEventType.set(matcher.group(1).trim)
        }
        subscription.request(1)
      }

      override def onError(throwable: Throwable): Unit = {
        eventHandler.onError(throwable)
      }

      override def onComplete(): Unit = {
        // Handle any remaining event data
        if (eventBuilder.length > 0) {
          val eventData = eventBuilder.toString
          val event = new FlowSseClient.SseEvent(currentEventId.get, currentEventType.get, eventData.trim)
          eventHandler.onEvent(event)
        }
      }
    }
    val subscriberFactory = (subscriber: Flow.Subscriber[String]) => HttpResponse.BodySubscribers.fromLineSubscriber(subscriber)
    val future = this.httpClient.sendAsync(request, (info: HttpResponse.ResponseInfo) => subscriberFactory.apply(lineSubscriber))
    future.thenAccept((response: HttpResponse[Void]) => {
      val status = response.statusCode
      if (status != 200 && status != 201 && status != 202 && status != 206) throw new RuntimeException("Failed to connect to SSE stream. Unexpected status code: " + status)
    }).exceptionally((throwable: Throwable) => {
      eventHandler.onError(throwable)
      null
    })
  }
}