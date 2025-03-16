/*
 * Copyright 2024-2024 the original author or authors.
 */
package io.modelcontextprotocol.client.transport

import com.fasterxml.jackson.annotation.{JsonInclude, JsonProperty}
import io.modelcontextprotocol.util.Assert
import scala.jdk.CollectionConverters._
import java.util
import java.util.stream.Collectors

/**
 * Server parameters for stdio client.
 *
 * @author Christian Tzolov
 * @author Dariusz Jędrzejczyk
 */
@JsonInclude(JsonInclude.Include.NON_ABSENT) object ServerParameters {
  // Environment variables to inherit by default
  private val DEFAULT_INHERITED_ENV_VARS = if (System.getProperty("os.name").toLowerCase.contains("win")) util.Arrays.asList("APPDATA", "HOMEDRIVE", "HOMEPATH", "LOCALAPPDATA", "PATH", "PROCESSOR_ARCHITECTURE", "SYSTEMDRIVE", "SYSTEMROOT", "TEMP", "USERNAME", "USERPROFILE")
  else util.Arrays.asList("HOME", "LOGNAME", "PATH", "SHELL", "TERM", "USER")

  def builder(command: String) = new ServerParameters.Builder(command)

  class Builder(private var command: String) {
    Assert.notNull(command, "The command can not be null")
    private var args = new util.ArrayList[String]
    private val env = new util.HashMap[String, String]

    def args(args: String*): ServerParameters.Builder = {
      Assert.notNull(args, "The args can not be null")
      val array = new util.ArrayList[String](
        args.map((arg: String) => arg).asJava  
      )
//      val kk =util.Arrays.asList(args)
      this.args = array 
      this
    }

    def args(args: util.List[String]): ServerParameters.Builder = {
      Assert.notNull(args, "The args can not be null")
      this.args = new util.ArrayList[String](args)
      this
    }

    def arg(arg: String): ServerParameters.Builder = {
      Assert.notNull(arg, "The arg can not be null")
      this.args.add(arg)
      this
    }

    def env(env: util.Map[String, String]): ServerParameters.Builder = {
      if (env != null && !env.isEmpty) this.env.putAll(env)
      this
    }

    def addEnvVar(key: String, value: String): ServerParameters.Builder = {
      Assert.notNull(key, "The key can not be null")
      Assert.notNull(value, "The value can not be null")
      this.env.put(key, value)
      this
    }

    def build = new ServerParameters(command, args, env)
  }

  /**
   * Returns a default environment object including only environment variables deemed
   * safe to inherit.
   */
  def getDefaultEnvironment: util.Map[String,String] =
    val envMap = new util.HashMap[String, String]()
    val filterEnv = System.getenv.entrySet.asScala
      .filter((entry: util.Map.Entry[String, String]) =>
        DEFAULT_INHERITED_ENV_VARS.contains(entry.getKey)).
      filter((entry: util.Map.Entry[String, String]) =>
        entry.getValue != null).
      filter((entry: util.Map.Entry[String, String]) =>
        !entry.getValue.startsWith("()"))
      .map(entry=> 
        envMap.put(entry.getKey, entry.getValue))
    envMap
//      .collect(
//        (entry: util.Map.Entry[String, String]) => 
//      
//        Collectors.toMap(util.Map.Entry.getKey, util.Map.Entry.getValue))
}

@JsonInclude(JsonInclude.Include.NON_ABSENT) 
class ServerParameters (@JsonProperty("command") var command: String,
                        @JsonProperty("args")var  args: util.List[String],
                        @JsonProperty("env")var  env: util.Map[String, String]) {
  Assert.notNull(command, "The command can not be null")
  Assert.notNull(args, "The args can not be null")
//  @JsonProperty("args") 
//  private var args = new util.ArrayList[String]
//  @JsonProperty("env")
//  private var env = null
  this.args = args
  this.env = new util.HashMap[String, String](ServerParameters.getDefaultEnvironment)
  if (env != null && !env.isEmpty) this.env.putAll(env)


  def getCommand: String = this.command

  def getArgs: util.List[String] = this.args

  def getEnv: util.Map[String, String] = this.env
}