package wwatch

import akka.actor.{ ActorSystem, Actor, ActorLogging, ActorRef, Props, PoisonPill }
import akka.event.{ Logging }
import akka.http.scaladsl.model._
import akka.http.scaladsl._
import akka.stream.ActorMaterializer
import spray.json._
import com.typesafe.config._
import scala.concurrent.duration._
import scala.concurrent.Future
import scala.util.{Try, Success, Failure}
import Instrumentation._

object UserData {
  def props(instrumentationActor: ActorRef) = Props(new UserData(instrumentationActor))
  
  // Messages
  case class GetUserInfoRequest(ipAddress: String)
  case class GetUserInfoResponse(userInfo: UserInfo)
  case class Evictor()
  
  // Model
  case class UserInfo(isBlocked: Boolean, specialService: Option[String], pubCampaign: Option[String]){
    
    val creationTime = System.currentTimeMillis()
    
    def isRedirectAction : Boolean = {
      // If a user is not redirected but is here, send it to the captive portal
      // So, only users with campaign will be not blocked
      if(pubCampaign == None) true else false
    }
  }
  
  /*
   * Expects JSON like this
   * {
   * 	isBlocked: true|false,
   * 	specialService: acs|reject|pcautiv|betatester
   * 	pubCampaign: <campaign-name>
   * }
   * 
   * A custom JsonProtocol is needed because the case class to serialize includes fields and methods not in the constructor
   */
  object MyJsonProtocol extends DefaultJsonProtocol {
    implicit object userInfoFormat extends RootJsonFormat[UserInfo]  {
      def write(v: UserInfo) = {
        JsObject()
      }
      
      def read(v: JsValue) = {
        val jObject = v.asJsObject
        UserInfo(
          jObject.getFields("isBlocked") match { 
            case Seq(JsBoolean(isBlocked)) => 
              isBlocked
          },
          jObject.getFields("specialService") match { 
            case Seq(JsString(specialService)) => 
              Some(specialService)
            case _ =>
              None
          },
          jObject.getFields("pubCampaign") match { 
            case Seq(JsString(pubCampaign)) => 
              Some(pubCampaign)
            case _ =>
              None
          }
        )
      }
    }
  }
}

class UserData(instrumentationActor: ActorRef) extends Actor {
  
  import UserData._
  import MyJsonProtocol._
  import akka.http.scaladsl.unmarshalling.Unmarshal
  
  //implicit val userInfoFormat = jsonFormat3(UserInfo)
  
  implicit val executionContext = context.dispatcher
  implicit val materializer = ActorMaterializer()
  
  val log = Logging.getLogger(context.system, this)
  
  val config = ConfigFactory.load()
  
  val clientCacheEvictorPeriodMillis = config. getLong("wwatch.userInfo.clientCacheEvictorPeriodMillis")
  val userCacheTimeMillis = config.getLong("wwatch.userInfo.userCacheTimeMillis")
  val userInfoURL = config.getString("wwatch.userInfo.userInfoURL")
  
  // Start test actor if needed
  if(config.getBoolean("wwatch.userInfo.mokeupUserInfoServer")){
    log.info("Starting mokeup UserInfo Server")
    val userInfoProviderActor = context.actorOf(TestUserInfoProvider.props, "TestUserInfo")
  } else log.info("Not starting mokeup UserInfo Server")
  
  // Holds the client cache
  val ipAddressMap = scala.collection.mutable.Map[String, UserInfo]()
  
  override def preStart = {
    // Send first cleaning
    context.system.scheduler.scheduleOnce(clientCacheEvictorPeriodMillis milliseconds, self, Evictor())
  }
  
  def receive = {
    case Evictor() =>
      // Periodically clean the userData cache
      val targetTimestamp = System.currentTimeMillis() - userCacheTimeMillis
      ipAddressMap.retain((k, v) => v.creationTime > targetTimestamp)
      context.system.scheduler.scheduleOnce(clientCacheEvictorPeriodMillis milliseconds, self, Evictor())
      
    case GetUserInfoRequest(ipAddress) =>
      val msgSender = sender
        
      (ipAddressMap.get(ipAddress) match {
        case None =>
          if(userInfoURL.contains("http")){
            instrumentationActor ! ReportUserInfoRequest()
            (for {
              response <- Http(context.system).singleRequest(HttpRequest(uri = userInfoURL.replaceAll("$ipAddress", ipAddress)))
              jsonString <- Unmarshal(response).to[String]
              ui = jsonString.parseJson.convertTo[UserInfo]
            } yield ui).recover {
              // Fill in case of error
              case _ => 
                log.warning("Error retrieving ClientInfo for IPAddress {}", ipAddress)
                instrumentationActor ! ReportUserInfoError()
                UserInfo(false, None, None)
            } map ( userInfo => {
              // Push to cache
              ipAddressMap.put(ipAddress, userInfo)
              userInfo
            })
          }
          // In mokeup userInfo the campaign is "adviser"
          else Future.successful(UserInfo(false, None, Some("adviser")))
          
        case Some(userInfo) =>
          Future.successful(userInfo)
          
      } ) onComplete {
        case Success(userInfo) => 
          msgSender ! userInfo
        case Failure(e) =>
          // This will never happen
          msgSender ! UserInfo(false, None, None)
      }
  }
}