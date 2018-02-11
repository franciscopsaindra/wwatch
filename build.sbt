name := "WWatch"

version := "0.1"

organization := "es.indra"

enablePlugins(JavaAppPackaging)

libraryDependencies ++= 
  Seq(
    "com.typesafe.akka" %% "akka-actor" 	 % "2.5.8",
    "com.typesafe.akka" %% "akka-stream"     % "2.5.8",
    "com.typesafe.akka" %% "akka-slf4j"      % "2.5.8",
    "com.typesafe.akka" %% "akka-testkit"    % "2.5.8",
	"com.typesafe.akka" %% "akka-http"		 % "10.0.11",
	"io.spray"		 	%% "spray-json" 	 % "1.3.3",
    "org.scalatest"     %% "scalatest"       % "3.0.0",
    "ch.qos.logback" 	%  "logback-classic" % "1.2.3"
  )
  
unmanagedClasspath in Compile += baseDirectory.value / "src" / "universal" / "content"
unmanagedClasspath in Runtime += baseDirectory.value / "src" / "universal" / "content"
  
scriptClasspath += "../conf"
scriptClasspath += "../content"

/*
javaOptions in Universal ++= Seq(
    // -J params will be added as jvm parameters
    "-J-Xmx300m",
    "-J-Xss512k"
)
*/

