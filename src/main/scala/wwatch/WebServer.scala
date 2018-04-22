package wwatch

import akka.actor.{ ActorSystem, Actor, ActorLogging, ActorRef, Props, PoisonPill }
import akka.pattern.{ ask, pipe }
import akka.http.scaladsl.{Http, ClientTransport}
import akka.http.scaladsl.settings.{ConnectionPoolSettings, ClientConnectionSettings}
import akka.http.scaladsl.coding.Gzip
import akka.stream.ActorMaterializer
import akka.stream.scaladsl._
import akka.http.scaladsl.model._
import akka.http.scaladsl.model.headers._
import akka.event.Logging
import akka.util.ByteString
import java.net.InetAddress
import java.net.InetSocketAddress
import com.typesafe.config._

import scala.concurrent.Future
import scala.concurrent.duration._
import scala.util.{Try, Success, Failure}

import scala.io.StdIn

import UserDataMgr._
import Actions._
import Content._
import Instrumentation._
import TestWebProvider._

object WWatch extends App {
  val actorSystem = ActorSystem("WWatch")
  val webServerActor = actorSystem.actorOf(WebServer.props)
  
  // Wait for key input
  StdIn.readLine
  actorSystem.terminate
}

object WebServer {
  def props() = Props(new WebServer)
}

class WebServer extends Actor {
  
  implicit val actorSystem = context.system
  implicit val materializer = ActorMaterializer()
  implicit val executionContext = actorSystem.dispatcher
  
  val log = Logging.getLogger(actorSystem, this)
  val actionLog = Logging.getLogger(actorSystem, "action")
  val proxyLog = Logging.getLogger(actorSystem, "proxy")
  
  val expiresHeader = Expires(DateTime(0))
    
  val config = ConfigFactory.load()
  
  // Load configuration parameters
  val userDataClientTimeoutMillis = config.getLong("wwatch.userDataMgr.askTimeoutMillis")
  implicit val askTimeout : akka.util.Timeout = userDataClientTimeoutMillis + 200 millis
  
  // Start instrumentation
  val instrumentationActor = actorSystem.actorOf(Instrumentation.props, "Instrumentation")
  
  // Start UserData retriever
  val userDataMgrActor = actorSystem.actorOf(RestUserDataMgr.props(instrumentationActor), "UserData")
  
  // Start mokeup web server
  if(config.getBoolean("wwatch.testWeb.enable")) actorSystem.actorOf(TestWebProvider.props, "TestWebProvider")
    
  // Flow to apply to responses that will be decorated
  def decoratorFlow(targetObject: String) = {
    Flow[ByteString].map(bs => {
    val targetPos = bs.lastIndexOfSlice("</body>")
    if(targetPos == -1) bs
    else {
      val (head, tail) = bs.splitAt(targetPos)
      val toReply = head.concat(Content.getObject(targetObject)).concat(tail)
      toReply
    }
    })
  }
  
  // Pre-create all flow specifications. The name convention is the name of the flows are the file names in content/inline/*
  val decoratorFlows = Content.getObjects
    .filter {case (name, contents) => name.startsWith("inline/") && name.lastIndexOf("/") == "inline/".lastIndexOf("/")}
    .map {case (name, contents) => (name.replaceFirst("inline/", ""), decoratorFlow(name))}
    
  // Connection settings
  // val connectionSettings = akka.http.scaladsl.settings.ConnectionPoolSettings(config)
  var poolSettings = ConnectionPoolSettings(config)
    .withConnectionSettings(ClientConnectionSettings(config))
    
  if(config.getBoolean("wwatch.proxy.enabled")){
    val auth = headers.BasicHttpCredentials(config.getString("wwatch.proxy.userName"), config.getString("wwatch.proxy.password"))
    poolSettings.withTransport(ClientTransport.httpsProxy(InetSocketAddress.createUnresolved(config.getString("wwatch.proxy.hostName"), config.getInt("wwatch.proxy.port")), auth))
  }
  
  def receive = {
    case _ => None
  }
  
  val requestHandler : (HttpRequest) => Future[HttpResponse] = {
      (req) => {
        val requestTimestamp = System.currentTimeMillis
        val addressHeader = req.header[`Remote-Address`].get.address
        val remoteAddress = addressHeader.getAddress.get.getHostAddress
        val requestedHost = req.header[Host].getOrElse(Host("<none>")).host.toString
        val userAgent = req.header[`User-Agent`].getOrElse(`User-Agent`("<unknown>")).value
        
        log.debug("{}, Received request: {},{}", req.hashCode, req.uri, userAgent)
        
        // Local content
        if(req.uri.path.toString.startsWith(Content.myGUID)){
          log.debug("{}, Serving local content {}", req.hashCode, req.uri)
          // ActionLog
          // <ACTION>,<CLIENT_ID>,<IP-ADDRESS>,<URI>,<USER-AGENT>
          actionLog.info("ACTION_LOCAL,,{},{},{}", remoteAddress, req.uri, userAgent)
          instrumentationActor ! ReportAction(ACTION_LOCAL)
          Future.successful (HttpResponse(200, entity = Content.getEntity(req.uri.path.toString.replaceFirst(Content.myGUID, ""))))
        }
        else {
          // Request data
          log.debug("{}, Requesting client data for IP address {}", req.hashCode, remoteAddress)
          val futureUserData = userDataMgrActor ? UserDataMgr.GetUserDataRequest(remoteAddress) map {v => v.asInstanceOf[UserData]}

          futureUserData.flatMap(userData => {
            log.debug("{}, Got client data: {}", req.hashCode, userData)
            requestAction(req, userData) match {
              case ACTION_REDIRECT =>
                log.debug("{}, Redirecting to {}", req.hashCode, "redirect.html")
                actionLog.info("ACTION_REDIRECT,{},{},{},{}", userData.clientId, remoteAddress, req.uri, userAgent)  
                instrumentationActor ! ReportAction(ACTION_REDIRECT)
                Future.successful(HttpResponse(200, entity = HttpEntity(Content.getRedirectPage(req.uri.toString)).withContentType(ContentTypes.`text/html(UTF-8)`)))
                
              case ACTION_BLOCK =>
                log.debug("{}, Forbidden", req.hashCode)
                actionLog.info("ACTION_BLOCK,{},{},{},{}", userData.clientId, remoteAddress, req.uri, userAgent)  
                instrumentationActor ! ReportAction(ACTION_BLOCK)
                Future.successful(HttpResponse(403, entity = "Blocked content"))
                
              case ACTION_PROXY =>
                log.debug("{}, Proxying request {}", req.hashCode, req.uri)
                proxyLog.info("REQUEST,{},{},{},{}", req.hashCode, userData.clientId, remoteAddress, req.uri) 
                instrumentationActor ! ReportProxyRequest
                
                Http().singleRequest(req.removeHeader("Timeout-Access").addHeader(`X-Forwarded-For`(addressHeader)).removeHeader("Remote-Address"), settings = poolSettings).map(resp => {
                  log.debug("{}, Got proxy reply {}", req.hashCode, resp.status)
                  proxyLog.info("RESPONSE,{},{},{},{}", req.hashCode, userData.clientId, remoteAddress +"," + req.uri, resp.status) 
                  instrumentationActor ! ReportProxyResponse(resp.status.intValue)
                  responseAction(resp, req, userData) match {
                    case PROXY_DIRECT =>
                      log.debug("{}, Sending unmodified response", req.hashCode)
                      actionLog.info("PROXY_DIRECT,{},{},{},{}", userData.clientId, remoteAddress, req.uri, userAgent) 
                      instrumentationActor ! ReportAction(PROXY_DIRECT)
                      resp
               
                    case PROXY_DECORATE =>
                      log.debug("{}, Sending decorated response {}", req.hashCode, userData.pageName)
                      actionLog.info("PROXY_DECORATE,{},{},{},{}", userData.clientId, remoteAddress, req.uri, userAgent)
                      instrumentationActor ! ReportAction(PROXY_DECORATE)  
                      // Got the client data
                      if(resp.status.intValue == 200) {
                         val decodedResponse = resp.header[`Content-Encoding`] match {
                            case Some(encoding) if(encoding.value == "gzip") =>
                              Gzip.decodeMessage(resp)
                            case _ =>
                            resp
                        }
                        decodedResponse.removeHeader("Expires").addHeader(expiresHeader)
                          .transformEntityDataBytes(decoratorFlows(userData.pageName.get))
                      }
                      // Error getting user data. Send proxy reply unchanged
                      else resp 
                  }
                }
              ) // proxy request
            }
          })
        } // else is remote content
      }
    }
  
  try {
    // Start server
    val bindPort = sys.env.getOrElse("PORT", config.getString("wwatch.server.redirectorListenPort")).toInt
    
    val futureBinding = Http().bindAndHandleAsync(requestHandler, "0.0.0.0", bindPort)
    futureBinding.onComplete {
      case Success(binding) =>
        log.info("Proxy server bound to port " + binding.localAddress)
      case Failure(e) =>
        log.error(e, "Proxy server not started")
    }
  
  } catch {
    case e: Throwable =>
      log.error(e, "Fatal Error")
      actorSystem.terminate
  }
}
