package wwatch

import akka.actor.ActorSystem
import akka.pattern.{ ask, pipe }
import akka.http.scaladsl.Http
import akka.http.scaladsl.coding.Gzip
import akka.stream.ActorMaterializer
import akka.stream.scaladsl._
import akka.http.scaladsl.model._
import akka.http.scaladsl.model.headers._
import akka.event.Logging
import akka.util.ByteString
import java.net.InetAddress
import com.typesafe.config._

import scala.concurrent.Future
import scala.concurrent.duration._

import scala.io.StdIn

import Actions._
import Content._
import Instrumentation._

object WebServer extends App {
  
  implicit val actorSystem = ActorSystem()
  implicit val materializer = ActorMaterializer()
  implicit val executionContext = actorSystem.dispatcher
  
  val log = Logging.getLogger(actorSystem, this)
  val workLog = Logging.getLogger(actorSystem, "work")
  
  try {
    
    val config = ConfigFactory.load()
    
    // Load configuration parameters
    val userInfoClientTimeoutMillis = config.getLong("wwatch.userInfo.askTimeoutMillis")
    implicit val askTimeout : akka.util.Timeout = userInfoClientTimeoutMillis + 200 millis
    
    // Start instrumentation
    val instrumentationActor = actorSystem.actorOf(Instrumentation.props, "Instrumentation")
    
    // Start UserInfo retriever
    val userInfoActor = actorSystem.actorOf(UserData.props(instrumentationActor), "UserData")
      
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
    
    // Pre-create all flow specifications
    val decoratorFlows = Content.getObjects
      .filter {case (name, contents) => name.startsWith("targets/")}
      .map {case (name, contents) => (name.replaceFirst("targets/", ""), decoratorFlow(name))}
      
    val requestHandler : (HttpRequest) => Future[HttpResponse] = {
      (req) => {
        val addressHeader = req.header[`Remote-Address`].get.address
        val remoteAddress = addressHeader.getAddress.get.getHostAddress
        val requestTimestamp = System.currentTimeMillis
        val requestedHost = req.header[Host].getOrElse(Host("<none>")).host.toString
        
        log.debug("REQUEST,{},{}", req.hashCode, req.uri)
        
        // Local content
        if(req.uri.path.toString.startsWith(Content.myGUID)){
          workLog.info("ACTION_LOCAL,{},{}", req.hashCode, req.uri)
          instrumentationActor ! ReportRequest(ACTION_LOCAL, requestedHost, remoteAddress)
          Future.successful (HttpResponse(200, entity = Content.getEntity(req.uri.path.toString.replaceFirst(Content.myGUID, ""))))
        }
        else {
          // Request data
          val futureUserInfo = userInfoActor ? UserData.GetUserInfoRequest(remoteAddress) map {v => v.asInstanceOf[UserData.UserInfo]}

          futureUserInfo.flatMap(userInfo => {
            requestAction(req, userInfo.isRedirectAction) match {
              case ACTION_REDIRECT =>
                workLog.info("ACTION_REDIRECT,{},{}", req.hashCode, req.uri)   
                instrumentationActor ! ReportRequest(ACTION_REDIRECT, requestedHost, remoteAddress)
                Future.successful(HttpResponse(200, entity = HttpEntity(Content.getRedirectPage(req.uri.toString)).withContentType(ContentTypes.`text/html(UTF-8)`)))
                
              case ACTION_BLOCK =>
                workLog.info("ACTION_BLOCK,{},{}", req.hashCode, req.uri) 
                instrumentationActor ! ReportRequest(ACTION_BLOCK, requestedHost, remoteAddress)
                Future.successful(HttpResponse(403, entity = "Blocked content"))
                
              case ACTION_PROXY =>
                workLog.info("ACTION_PROXY,{},{}", req.hashCode, req.uri)
                instrumentationActor ! ReportRequest(ACTION_PROXY, requestedHost, remoteAddress)
                
                Http().singleRequest(req.removeHeader("Timeout-Access").addHeader(`X-Forwarded-For`(addressHeader)).removeHeader("Remote-Address")).map(resp => {
                  workLog.info("PROXY REPLY,{}", req.hashCode)
                  instrumentationActor ! ReportProxyResponse(resp.status.intValue)
                  responseAction(resp, Actions.hostInWhiteList(req)) match {
                    case PROXY_DIRECT =>
                      workLog.info("PROXY_DIRECT,{}", req.hashCode)
                      instrumentationActor ! ReportProxyAction(PROXY_DIRECT)
                      resp
               
                    case PROXY_DECORATE =>
                      workLog.info("PROXY_DECORATE,{}", req.hashCode)
                      instrumentationActor ! ReportProxyAction(PROXY_DECORATE)  
                      // Got the client data
                      if(resp.status.intValue == 200) {
                         val decodedResponse = resp.header[`Content-Encoding`] match {
                            case Some(encoding) if(encoding.value == "gzip") =>
                              Gzip.decodeMessage(resp)
                            case _ =>
                            resp
                        }
                        decodedResponse.transformEntityDataBytes(decoratorFlows(userInfo.pubCampaign.get + ".html"))
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
    
    // Start server
    val futureBinding = Http().bind("0.0.0.0", config.getInt("wwatch.server.redirectorListenPort")).to(Sink.foreach{connection => connection.handleWithAsyncHandler(requestHandler, 1)}).run()
    // Wait for key input
    StdIn.readLine()
    
    // Terminate
    futureBinding.onComplete(_ => actorSystem.terminate()) 
  
  } catch {
    case e: Throwable =>
      log.error(e, "Fatal Error")
      actorSystem.terminate
  }
}
