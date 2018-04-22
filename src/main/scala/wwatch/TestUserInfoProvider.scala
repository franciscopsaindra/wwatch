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
import UserDataMgr.UserData

object TestUserDataProvider {
  def props(defaultUserData: UserData) = Props(new TestUserDataProvider(defaultUserData))
}
  
class TestUserDataProvider(defaultUserData: UserData) extends Actor {
  
  val log = Logging.getLogger(context.system, this)
  
  def receive = {
    case _ => None
  }
  
  val route = path("userData") {
    get {
      log.debug("UserData request received")
      val jsonResponse = JsObject(
      "clientId" -> JsString(defaultUserData.clientId),
      "policy" -> JsNumber(defaultUserData.policy),
      "inline" -> JsBoolean(defaultUserData.inline),
      "pageName" -> JsString(defaultUserData.pageName.getOrElse(""))
      )
      complete(HttpEntity(ContentTypes.`application/json`, jsonResponse.prettyPrint))
    }
  } 

  implicit val actorSystem = context.system
  implicit val materializer = ActorMaterializer()
  implicit val executionContext = actorSystem.dispatcher
  val bindFuture = Http().bindAndHandle(route, "0.0.0.0", 11111)
  
  bindFuture.onComplete {
    case Success(binding) =>
      log.info("TestUserDataProvider bound to " + binding.localAddress)
    case Failure(e) =>
       log.error(e.getMessage)
  }
}