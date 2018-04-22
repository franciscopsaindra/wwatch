package wwatch

import scala.util.{Success, Failure}
import akka.actor.{ ActorSystem, Actor, ActorLogging, ActorRef, Props, PoisonPill }
import akka.event.{ Logging }
import akka.stream.ActorMaterializer
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.model._
import akka.http.scaladsl._
import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport
import com.typesafe.config._
import spray.json._
import DefaultJsonProtocol._ 

import Actions._

object Instrumentation {
  def props() = Props(new Instrumentation())
  
  case class Reset()
  case class ReportAction(actionInt: Int)
  case class ReportProxyRequest()
  case class ReportProxyResponse(statusCode: Int)
}
  
class Instrumentation extends Actor with SprayJsonSupport {
  
  import Instrumentation._
  
  val config = ConfigFactory.load()
  
  val log = Logging.getLogger(context.system, this)
  
  def newMapCounterValue(key: Any): Long = 0
  val actionStats = scala.collection.mutable.Map[Int, Long]() withDefault newMapCounterValue        
  val proxyResponseStats = scala.collection.mutable.Map[Int, Long]() withDefault newMapCounterValue
  var proxyRequestStats: Long = 0  
  
  def receive = {
    case ReportAction(action) =>
      actionStats.put(action, actionStats(action) + 1)
      
    case ReportProxyRequest() =>
     proxyRequestStats = proxyRequestStats + 1
      
    case ReportProxyResponse(statusCode) =>
      proxyResponseStats.put(statusCode, proxyResponseStats(statusCode) + 1)
      
    case Reset() =>
      log.info("Reseting stats")
      actionStats.clear
      proxyResponseStats.clear
      proxyRequestStats = 0
  }
  
  
  /////////////////////////////////////////////////////////////
  // Web server
  
  /*
   * {
   * 	"actions": {
   * 		"redirect": <counter>,
   * 		"block": <counter>,
   * 		"proxyDirect": <counter>,
   *    "proxyDecorate": <counter>,
   * 		"local": <counter>
   * 	},
   *  "proxyRequests": <value>,
   * 	"proxyResponses": {
   * 		<status> : <counter>
   *  }
   */
  
  val route = path("stats") {
    get {
      val actionMap = actionStats.map{ case (action, counter) => {
        action match {
          case ACTION_REDIRECT => ("redirect", counter)
          case ACTION_BLOCK => ("block", counter)
          case PROXY_DIRECT => ("proxyDirect", counter)
          case PROXY_DECORATE => ("proxyDecorate", counter)
          case ACTION_LOCAL => ("local", counter)
        }
      }}
      
      val proxyResponseMap = proxyResponseStats.map{ case (status, counter) => (status.toString, counter) }
      
      val jsonResponse = JsObject(
          "actions" -> actionMap.toMap.toJson,
          "proxyRequests" -> proxyRequestStats.toJson,
          "proxyResponses" -> proxyResponseMap.toMap.toJson
          )
          
      complete(jsonResponse)
    }
  }
  
  implicit val actorSystem = context.system
  implicit val materializer = ActorMaterializer()
  implicit val executionContext = actorSystem.dispatcher
  
  // Disable this if run in Heroku
  val bindPort = config.getInt("wwatch.server.instrumentationListenPort")
  if(bindPort != 0) {
  val bindFuture = Http().bindAndHandle(route, "0.0.0.0", config.getInt("wwatch.server.instrumentationListenPort"))
    bindFuture.onComplete {
      case Success(binding) =>
        log.info("Instrumentation bound to " + binding.localAddress)
      case Failure(e) =>
         log.error(e.getMessage)
    }
  } else log.info("Instrumentation not bound to external port")
  
}