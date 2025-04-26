import xerial.sbt.Sonatype.sonatypeCentralHost

ThisBuild / version := "0.1.0"

ThisBuild / scalaVersion := "3.3.4"

lazy val root = (project in file("."))
  .settings(
    name := "storch-mcp"
  )

ThisBuild / tlSonatypeUseLegacyHost := false
ThisBuild / organization := "io.github.mullerhai" //"dev.storch"
ThisBuild / organizationName := "storch.dev"
ThisBuild / startYear := Some(2024)
ThisBuild / licenses := Seq(License.Apache2)
ThisBuild / developers := List(
  // your GitHub handle and name
  tlGitHubDev("mullerhai", "mullerhai")
)
publishTo := sonatypePublishToBundle.value
import scala.collection.immutable.Seq
ThisBuild / sonatypeCredentialHost := sonatypeCentralHost

import ReleaseTransformations._
releaseProcess := Seq[ReleaseStep](
  checkSnapshotDependencies,
  inquireVersions,
  runClean,
  runTest,
  setReleaseVersion,
  commitReleaseVersion,
  tagRelease,
  releaseStepCommandAndRemaining("+publishSigned"),
  releaseStepCommandAndRemaining("sonatypeBundleRelease"),
  setNextVersion,
  commitNextVersion,
  pushChanges,
)

libraryDependencies += "com.fasterxml.jackson.core" % "jackson-databind" % "2.18.3"
// https://mvnrepository.com/artifact/io.projectreactor/reactor-core
libraryDependencies += "io.projectreactor" % "reactor-core" % "3.8.0-M1"
// https://mvnrepository.com/artifact/org.assertj/assertj-core
libraryDependencies += "org.assertj" % "assertj-core" % "4.0.0-M1" % Test
// https://mvnrepository.com/artifact/io.projectreactor.netty/reactor-netty-http
libraryDependencies += "io.projectreactor.netty" % "reactor-netty-http" % "1.3.0-M1"
// https://mvnrepository.com/artifact/org.awaitility/awaitility
libraryDependencies += "org.awaitility" % "awaitility" % "4.3.0" % Test
// https://mvnrepository.com/artifact/ch.qos.logback/logback-classic
libraryDependencies += "ch.qos.logback" % "logback-classic" % "1.5.17"
// https://mvnrepository.com/artifact/jakarta.servlet/jakarta.servlet-api
libraryDependencies += "jakarta.servlet" % "jakarta.servlet-api" % "6.1.0" % "provided"

ThisBuild  / assemblyMergeStrategy := {
  case v if v.contains("module-info.class")   => MergeStrategy.discard
  case v if v.contains("UnusedStub")          => MergeStrategy.first
  case v if v.contains("aopalliance")         => MergeStrategy.first
  case v if v.contains("inject")              => MergeStrategy.first
  case v if v.contains("jline")               => MergeStrategy.discard
  case v if v.contains("scala-asm")           => MergeStrategy.discard
  case v if v.contains("asm")                 => MergeStrategy.discard
  case v if v.contains("scala-compiler")      => MergeStrategy.deduplicate
  case v if v.contains("reflect-config.json") => MergeStrategy.discard
  case v if v.contains("jni-config.json")     => MergeStrategy.discard
  case v if v.contains("git.properties")      => MergeStrategy.discard
  case v if v.contains("io.netty.versions.properties")      => MergeStrategy.discard
  case v if v.contains("reflect.properties")      => MergeStrategy.discard
  case v if v.contains("compiler.properties")      => MergeStrategy.discard
  case v if v.contains("scala-collection-compat.properties")      => MergeStrategy.discard
  case x =>
    val oldStrategy = (assembly / assemblyMergeStrategy).value
    oldStrategy(x)
}