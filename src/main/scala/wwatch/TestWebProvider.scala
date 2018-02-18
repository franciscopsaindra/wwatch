package wwatch

import scala.util.{Success, Failure}
import akka.actor.{ ActorSystem, Actor, ActorLogging, ActorRef, Props, PoisonPill }
import akka.event.{ Logging }
import akka.stream.ActorMaterializer
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.model._
import akka.http.scaladsl._
import akka.pattern.ask
import com.typesafe.config._
import scala.concurrent.duration._
import scala.concurrent.Future


import Actions._

/**
 * Will answer all requests with an image (test.jpg) after waiting wwatch. to simulate a delay
 */
object TestWebProvider {
  def props() = Props(new TestWebProvider())
  
  case class TestRequest()
  case class TestResponse()
  case class SendTestResponse(actorRef: ActorRef)
}
  
class TestWebProvider extends Actor {
  
  import TestWebProvider._
  
  implicit val actorSystem = context.system
  implicit val materializer = ActorMaterializer()
  implicit val executionContext = actorSystem.dispatcher

  val log = Logging.getLogger(context.system, this)
  val config = ConfigFactory.load() 
  val answerDelay = config.getInt("wwatch.testWeb.answerDelayMillis")
  implicit val askTimeout : akka.util.Timeout = 2000 millis
  
  def receive = {
    case TestRequest =>
      log.debug("Received TestRequest from " + sender)
      context.system.scheduler.scheduleOnce(answerDelay milliseconds, self, SendTestResponse(sender))
      log.debug("Scheduled response to " + sender)
    
    case SendTestResponse(actorRef) =>
      log.debug("Received TestResponse to be sent to " + actorRef)
      actorRef ! TestResponse
  }
  
  val route = path("delay") {
    get {
      onComplete(self ? TestRequest) {
        case Success(_) =>
          complete(HttpEntity(MediaTypes.`image/jpeg`.toContentType, Content.getObject("test.jpg")))
        case Failure(ex) =>
          log.error(ex, ex.getMessage)
          complete(StatusCodes.ServiceUnavailable)
      }
    }
  } ~ 
  get {
      complete(HttpEntity(MediaTypes.`image/jpeg`.toContentType, Content.getObject("test.jpg")))
  }

  val bindFuture = Http().bindAndHandle(route, "0.0.0.0", config.getInt("wwatch.testWeb.listenPort"))
  
  bindFuture.onComplete {
    case Success(binding) =>
      log.info("TestWebProvider bound to " + binding.localAddress)
    case Failure(e) =>
       log.error(e.getMessage)
  }
}