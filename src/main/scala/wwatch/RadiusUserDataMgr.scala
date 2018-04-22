package wwatch

import akka.actor.{ ActorSystem, Actor, ActorLogging, ActorRef, Props, PoisonPill, Cancellable }
import akka.stream.ActorMaterializer
import akka.event.{ Logging }
import akka.http.scaladsl.model._
import akka.http.scaladsl._
import spray.json._
import com.typesafe.config._

import scala.util.{Try, Success, Failure}
import scala.concurrent.Future
import scala.concurrent.duration._

import akka.io.{IO, Udp}
import java.net.InetSocketAddress

object RadiusUserDataMgr {
  def props(instrumentationActor: ActorRef) = Props(new RadiusUserDataMgr(instrumentationActor))
}

class RadiusUserDataMgr(instrumentationActor: ActorRef) extends UserDataMgr {
  
  import UserDataMgr._
  import context.system
  
  implicit val executionContext = context.dispatcher
  
  val log = Logging.getLogger(context.system, this)
  
  val config = ConfigFactory.load()
  
  val timeoutSeconds = config.getLong("wwatch.radiusUserDataMgr.timeoutSeconds")
  val radiusListenAddress = config.getString("wwatch.radiusUserDataMgr.listenAddress")
  val radiusListenPort = config.getInt("wwatch.radiusUserDataMgr.listenPort")
  val radiusSecret = config.getString("wwatch.radiusUserDataMgr.secret")
  
  // Returned in case of end-point not configured or error
  val defaultUserData = UserData(
      config.getString("wwatch.defaultUserData.clientId"), 
      config.getInt("wwatch.defaultUserData.policy"), 
      config.getBoolean("wwatch.defaultUserData.inline"), 
      Some(config.getString("wwatch.defaultUserData.pageName")))
  
  // Holds the client cache
  case class TimedUserData(userData: UserData, timer: Cancellable)
  val ipAddressMap = scala.collection.mutable.Map[String, TimedUserData]()
  
  IO(Udp) ! Udp.Bind(self, new InetSocketAddress(radiusListenAddress, radiusListenPort))
  
  
  def receive = {
    case Udp.Bound(localAddress: InetSocketAddress) =>
    log.info(s"Radius socket bound to $localAddress")
    context.become(ready(sender))
    
    // TODO: Add case for error
  }
  
  def ready(udpEndPoint: ActorRef): Receive = {
    case Udp.Received(data, remote) =>


    case GetUserDataRequest(ipAddress) =>   
  }
}