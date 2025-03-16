/*
* Copyright 2024 - 2024 the original author or authors.
*/
package io.modelcontextprotocol.spec
import java.lang.RuntimeException
import io.modelcontextprotocol.spec.McpSchema.JSONRPCResponse
import io.modelcontextprotocol.spec.McpSchema.JSONRPCResponse.JSONRPCError
object McpError {

  def apply(error: String): McpError = {
    super(error.toString)  
//    new McpError(error.toString)
  }  
}
class McpError(var jsonRpcError: JSONRPCResponse.JSONRPCError) extends RuntimeException(jsonRpcError.message) {
//  private var jsonRpcError: JSONRPCResponse.JSONRPCError = _

//  def this(jsonRpcError: JSONRPCResponse.JSONRPCError) =
//    this()
//
////    super.(jsonRpcError.message)
//    super(jsonRpcError.message)
//    this.jsonRpcError = jsonRpcError

  def this(error: AnyRef) = {
//    this(error.toString)
    super(error.toString)
  }

  def getJsonRpcError: JSONRPCResponse.JSONRPCError = jsonRpcError
}