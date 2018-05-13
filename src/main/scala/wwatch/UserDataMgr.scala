package wwatch

import akka.actor.Actor

object UserDataMgr {
  
  // Messages
  case class GetUserDataRequest(ipAddress: String)
  case class GetUserDataResponse(userData: UserData)
  case class Evictor()
  
  // Constants
  val POLICY_PASS = 1           // Always proxy (except if in black list)
  val POLICY_UNCONDITIONAL = 2  // Always redirect
  val POLICY_WEAK = 3           // Redirect except if user agent is in list
  
  // Inline --> true: will insert advertising after proxy, false: do not insert anything
  
  // Model
  case class UserData(clientId: String, policy: Int, inline: Boolean, pageName: Option[String]){
    val creationTime = System.currentTimeMillis()
  }
}

trait UserDataMgr extends Actor {
  
}
  