/*
 * Copyright 2024-2024 the original author or authors.
 */
package torch.modelcontextprotocol.client.transport

import com.fasterxml.jackson.annotation.{JsonInclude, JsonProperty}
import torch.modelcontextprotocol.util.Assert

import java.util
import scala.collection.mutable
import scala.collection.mutable.ListBuffer
import scala.jdk.CollectionConverters.*

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

  /**
   * Returns a default environment object including only environment variables deemed
   * safe to inherit.
   */
  def getDefaultEnvironment: mutable.Map[String, String] =
    val envMap = new mutable.HashMap[String, String]()
    System.getenv.asScala.
      filter((k, v) => DEFAULT_INHERITED_ENV_VARS.contains(k)).
      filter((k, v) => v != null).
      filter((k, v) => !v.startsWith("()")).
      foreach((k, v) => envMap.put(k, v))
    //    val filterEnv = System.getenv.asScala.entrySet
    //      .filter((entry: mutable.Map.Entry[String, String]) =>
    //        DEFAULT_INHERITED_ENV_VARS.contains(entry.getKey)).
    //      filter((entry: mutable.Map.Entry[String, String]) =>
    //        entry.getValue != null).
    //      filter((entry: mutable.Map.Entry[String, String]) =>
    //        !entry.getValue.startsWith("()"))
    //      .map(entry=> 
    //        envMap.put(entry.getKey, entry.getValue))
    envMap

  class Builder(private var command: String) {
    Assert.notNull(command, "The command can not be null")
    private val env = new mutable.HashMap[String, String]
    private var args = new ListBuffer[String]

    def args(args: String*): ServerParameters.Builder = {
      Assert.notNull(args, "The args can not be null")
      val array = new ListBuffer[String]()
      array.addAll(args)
      //        args.map((arg: String) => arg).asJava
//      val kk =util.Arrays.asList(args)
      this.args = array 
      this
    }

    def args(args: List[String]): ServerParameters.Builder = {
      Assert.notNull(args, "The args can not be null")
      this.args = new ListBuffer[String]()

      this.args.appendedAll(args)
      this
    }

    def arg(arg: String): ServerParameters.Builder = {
      Assert.notNull(arg, "The arg can not be null")
      this.args.append(arg)
      this
    }

    def env(env: mutable.Map[String, String]): ServerParameters.Builder = {
      if (env != null && env.nonEmpty) this.env.addAll(env)
      this
    }

    def addEnvVar(key: String, value: String): ServerParameters.Builder = {
      Assert.notNull(key, "The key can not be null")
      Assert.notNull(value, "The value can not be null")
      this.env.put(key, value)
      this
    }

    def build = new ServerParameters(command, args.toList, env)
  }
//      .collect(
  //        (entry: mutable.map.Entry[String, String]) =>
//      
  //        Collectors.toMap(mutable.map.Entry.getKey, mutable.map.Entry.getValue))
}

@JsonInclude(JsonInclude.Include.NON_ABSENT) 
class ServerParameters (@JsonProperty("command") var command: String,
                        @JsonProperty("args") var args: List[String],
                        @JsonProperty("env") var env: mutable.Map[String, String]) {
  Assert.notNull(command, "The command can not be null")
  Assert.notNull(args, "The args can not be null")
//  @JsonProperty("args") 
  //  private var args = new ListBuffer[String]
//  @JsonProperty("env")
//  private var env = null
  this.args = args
  this.env = new mutable.HashMap[String, String]()
  env.addAll(ServerParameters.getDefaultEnvironment)
  if (env != null && env.nonEmpty) this.env.addAll(env)


  def getCommand: String = this.command

  def getArgs: List[String] = this.args

  def getEnv: Map[String, String] = this.env.toMap
}