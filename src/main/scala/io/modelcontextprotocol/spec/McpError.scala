/*
* Copyright 2024 - 2024 the original author or authors.
*/
package io.modelcontextprotocol.spec
import io.modelcontextprotocol.spec.McpSchema.JSONRPCResponse
import io.modelcontextprotocol.spec.McpSchema.JSONRPCResponse.JSONRPCError

import java.lang.RuntimeException
object McpError {

  def apply(error: String): McpError = {

    RuntimeException(error).asInstanceOf[McpError]
    //    super(error.toString)  
//    new McpError(error.toString)
  }

  def apply(error: AnyRef): McpError = {
    //    this(error.toString)
    //    super(error.toString)
    RuntimeException(error.toString).asInstanceOf[McpError]
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
  //  def this(error: AnyRef) = RuntimeException(error.toString).asInstanceOf[McpError] //this(error.toString)

  //  def this(error: String) = {
  //
  //    RuntimeException(error).asInstanceOf[McpError]
  //    //    super(error.toString)  
  //    //    new McpError(error.toString)
  //  }

  def getJsonRpcError: JSONRPCResponse.JSONRPCError = jsonRpcError
}