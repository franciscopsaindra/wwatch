package wwatch

import akka.actor.{ ActorSystem, Actor, ActorLogging, ActorRef, Props, PoisonPill }
import akka.stream.ActorMaterializer
import akka.event.{ Logging }
import com.typesafe.config._
import akka.http.scaladsl.model._
import akka.http.scaladsl._
import scala.util.{Try, Success, Failure}
import scala.concurrent.Future

import UserDataMgr._
import spray.json._

import scala.concurrent.duration._

object MyJsonProtocol extends DefaultJsonProtocol {
  implicit val myFormat = jsonFormat4(UserData)
}


object RestUserDataMgr {
  def props(instrumentationActor: ActorRef) = Props(new RestUserDataMgr(instrumentationActor))
}

class RestUserDataMgr(instrumentationActor: ActorRef) extends UserDataMgr {
  
  import UserDataMgr._
  import MyJsonProtocol._
  import akka.http.scaladsl.unmarshalling.Unmarshal
  
  implicit val executionContext = context.dispatcher
  implicit val materializer = ActorMaterializer()
  
  val log = Logging.getLogger(context.system, this)
  
  val config = ConfigFactory.load()
  
  val clientCacheEvictorPeriodMillis = config.getLong("wwatch.restUserDataMgr.clientCacheEvictorPeriodMillis")
  val userCacheTimeMillis = config.getLong("wwatch.restUserDataMgr.userCacheTimeMillis")
  val userDataURL = config.getString("wwatch.restUserDataMgr.userDataURL")
  
  // Returned in case of end-point not configured or error
  val defaultUserData = UserData(
      config.getString("wwatch.defaultUserData.clientId"), 
      config.getInt("wwatch.defaultUserData.policy"), 
      config.getBoolean("wwatch.defaultUserData.inline"), 
      Some(config.getString("wwatch.defaultUserData.pageName")))
  
  // Start test actor if needed
  if(config.getBoolean("wwatch.restUserDataMgr.mokeupUserDataServer")){
    log.info("Starting mokeup UserData Server")
    val userDataProviderActor = context.actorOf(TestUserDataProvider.props(defaultUserData), "TestUserData")
  } else log.info("Not starting mokeup UserData Server")
  
  // Holds the client cache
  val ipAddressMap = scala.collection.mutable.Map[String, UserData]()
  
  override def preStart = {
    // Send first cleaning
    context.system.scheduler.scheduleOnce(clientCacheEvictorPeriodMillis milliseconds, self, Evictor())
  }
  
  def receive = {
    case Evictor() =>
      // Periodically clean the userData cache
      val targetTimestamp = System.currentTimeMillis() - userCacheTimeMillis
      ipAddressMap.retain((k, v) => v.creationTime > targetTimestamp)
      val timer = context.system.scheduler.scheduleOnce(clientCacheEvictorPeriodMillis milliseconds, self, Evictor())
      
    case GetUserDataRequest(ipAddress) =>
      val msgSender = sender
        
      (ipAddressMap.get(ipAddress) match {
        case None =>
          if(userDataURL.contains("http")){   
            (for {
              response <- Http(context.system).singleRequest(HttpRequest(uri = userDataURL.replaceAll("$ipAddress", ipAddress)))
              jsonString <- Unmarshal(response).to[String]
              ud = jsonString.parseJson.convertTo[UserData]
            } yield ud).recover {
              // Fill in case of error
              case t: Throwable => 
                log.warning("Error retrieving UserData for IPAddress {}: {}", ipAddress, t.getMessage)
                defaultUserData
            } map ( userData => {
              // Push to cache
              ipAddressMap.put(ipAddress, userData)
              userData
            })
          }
          // UserData service not configured
          else Future.successful(defaultUserData)
          
        case Some(userData) =>
          Future.successful(userData)
          
      } ) onComplete {
        case Success(userData) => 
          msgSender ! userData
        case Failure(e) =>
          // This will never happen
          msgSender ! defaultUserData
      }
  }
}