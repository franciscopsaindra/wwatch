package wwatch

import scala.util.{Success, Failure}
import akka.actor.{ ActorSystem, Actor, ActorLogging, ActorRef, Props, PoisonPill }
import akka.event.{ Logging }
import akka.stream.ActorMaterializer
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.model._
import akka.http.scaladsl._
import com.typesafe.config._
import spray.json._
import DefaultJsonProtocol._ 

import Actions._

object Instrumentation {
  def props() = Props(new Instrumentation())
  
  case class Reset()
  case class ReportRequest(action: Int, hostName: String, remoteAddress: String)
  case class ReportProxyResponse(statusCode: Int)
  case class ReportProxyAction(action: Int)
  case class ReportUserInfoRequest()
  case class ReportUserInfoError()
}
  
class Instrumentation extends Actor {
  
  import Instrumentation._
  
  val config = ConfigFactory.load()
  
  val log = Logging.getLogger(context.system, this)
  
  def newMapCounterValue(key: Any): Long = 0
  val requestStats = scala.collection.mutable.Map[Int, Long]() withDefault newMapCounterValue        
  val proxyResponseStats = scala.collection.mutable.Map[Int, Long]() withDefault newMapCounterValue  
  val proxyActionStats = scala.collection.mutable.Map[Int, Long]() withDefault newMapCounterValue
  val hostStats = scala.collection.mutable.Map[String, Long]() withDefault newMapCounterValue
  val clientStats = scala.collection.mutable.Map[String, Long]() withDefault newMapCounterValue
  var userInfoRequests : Long = 0
  var userInfoErrors : Long = 0
  
  def receive = {
    case ReportRequest(action, hostName, remoteAddress) =>
      requestStats.put(action, requestStats(action) + 1)
      hostStats.put(hostName, hostStats(hostName) + 1)
      clientStats.put(remoteAddress, clientStats(remoteAddress) + 1)
      
    case ReportProxyResponse(statusCode) =>
      proxyResponseStats.put(statusCode, proxyResponseStats(statusCode) +1) 
      
    case ReportProxyAction(action) =>
      proxyActionStats.put(action, proxyActionStats(action) + 1)
      
    case ReportUserInfoRequest() =>
      userInfoRequests += 1 
      
    case ReportUserInfoError() =>
      userInfoErrors += 1 
      
    case Reset() =>
      log.info("Reseting stats")
      requestStats.clear
      proxyResponseStats.clear
      proxyActionStats.clear
      userInfoRequests = 0
      userInfoErrors = 0
  }
  
  
  /////////////////////////////////////////////////////////////
  // Web server
  
  /*
   * {
   * 	"requests": {
   * 		"redirect": <counter>,
   * 		"block": <counter>,
   * 		"proxy": <counter>,
   * 		"local": <counter>
   * 	},
   * 	"proxyResponses": {
   * 		<status> : <counter>
   *  },
   *  "proxyActions": {
   *  	"direct": <counter>,
   *  	"decorate": <counter>,
   *  	"replace": <counter>
   * }
   */
  
  val route = path("stats") {
    get {
      val requestMap = requestStats.map{ case (action, counter) => {
        action match {
          case ACTION_REDIRECT => ("redirect", counter)
          case ACTION_BLOCK => ("block", counter)
          case ACTION_PROXY => ("proxy", counter)
          case ACTION_LOCAL => ("local", counter)
        }
      }}

      val proxyActionMap = proxyActionStats.map{ case (action, counter) => {
        action match {
          case PROXY_DIRECT => ("direct", counter)
          case PROXY_DECORATE => ("decorate", counter)
        }
      }}
      
      val proxyResponseMap = proxyResponseStats.map{ case (status, counter) => (status.toString, counter) }
      
      val jsonResponse = JsObject(
          "requests" -> requestMap.toMap.toJson,
          "proxyResponses" -> proxyResponseMap.toMap.toJson,
          "proxyActions" -> proxyActionMap.toMap.toJson
          )
          
      complete(HttpEntity(ContentTypes.`application/json`, jsonResponse.prettyPrint))
    }
  } ~ path("hosts") {
    get { 
      val a = hostStats.toMap
      complete(HttpEntity(ContentTypes.`application/json`, hostStats.toMap.toJson.prettyPrint))
    }
  } ~ path("clients") {
    get {
      complete(HttpEntity(ContentTypes.`application/json`, clientStats.toMap.toJson.prettyPrint))
    }
  } ~ path("userInfo") {
    get {
      val jsonResponse = JsObject(
          "requests" -> JsNumber(userInfoRequests),
          "errors" -> JsNumber(userInfoErrors)
          )
      complete(HttpEntity(ContentTypes.`application/json`, jsonResponse.prettyPrint)) 
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
      case Success(b) =>
        log.info("Bound")
      case Failure(e) =>
         log.error(e.getMessage)
    }
  } else log.info("Instrumentation not bound to external port")
  
}