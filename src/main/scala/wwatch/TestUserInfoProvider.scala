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

object TestUserInfoProvider {
  def props() = Props(new TestUserInfoProvider())
}
  
class TestUserInfoProvider extends Actor {
  
  val log = Logging.getLogger(context.system, this)
  
  def receive = {
    case _ => None
  }
  
  val route = path("userInfo") {
    get {
      log.debug("Asked for UserInfo")
      val jsonResponse = JsObject(
          "isBlocked" -> false.toJson,
          //"specialService" -> proxyResponseMap.toMap.toJson,
          "pubCampaign" -> JsString("adviser")
          )
          
      complete(HttpEntity(ContentTypes.`application/json`, jsonResponse.prettyPrint))
    }
  } 

  implicit val actorSystem = context.system
  implicit val materializer = ActorMaterializer()
  implicit val executionContext = actorSystem.dispatcher
  val bindFuture = Http().bindAndHandle(route, "0.0.0.0", 11111)
  
  bindFuture.onComplete {
    case Success(b) =>
      log.info("Bound")
    case Failure(e) =>
       log.error(e.getMessage)
  }
}