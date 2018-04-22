package wwatch

import akka.actor.Actor

object UserDataMgr {
  
  // Messages
  case class GetUserDataRequest(ipAddress: String)
  case class GetUserDataResponse(userData: UserData)
  case class Evictor()
  
  // Constants
  val POLICY_PASS = 1
  val POLICY_UNCONDITIONAL = 2
  val POLICY_WEAK = 3
  
  // Model
  case class UserData(clientId: String, policy: Int, inline: Boolean, pageName: Option[String]){
    val creationTime = System.currentTimeMillis()
  }
}

trait UserDataMgr extends Actor {
  
}
  