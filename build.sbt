ThisBuild / version := "0.1.0-SNAPSHOT"

ThisBuild / scalaVersion := "3.3.4"

lazy val root = (project in file("."))
  .settings(
    name := "mcp"
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
