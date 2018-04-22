package wwatch

import akka.http.scaladsl.model._
import akka.http.scaladsl.model.headers._

import scala.io.Source
import scala.util.matching.Regex

import UserDataMgr._

object Actions {
  
  val ACTION_REDIRECT = 1
  val ACTION_BLOCK = 2
  val ACTION_PROXY = 3
  val ACTION_LOCAL = 4
  
  val PROXY_DIRECT = 5
  val PROXY_DECORATE = 6
  
  
  // Get the list of hosts to force redirect/block/free
  // The initial list must always exist, to avoid error
  def getRegexLinesFromFile(initialFile: String, configFile: String) = (Source.fromResource(initialFile).getLines.toSet ++ (if(getClass.getClassLoader.getResource(configFile) == null) Set() else Source.fromResource(configFile).getLines))
        .flatMap{ line => {
          if(!line.isEmpty && ! line.trim.startsWith("#")) Seq(new Regex(line)) else Seq()
        }}
  
  val urlWhiteList = getRegexLinesFromFile("URLWhiteList_initial.txt", "URLhostWhiteList.txt")
  val urlBlackList = getRegexLinesFromFile("URLBlackList_initial.txt", "URLhostBlackList.txt")
  val urlDoNotDecorateList = getRegexLinesFromFile("URLDoNotDecorateList_initial.txt", "URLDoNotDecorateList.txt")
  val userAgentWhiteList = getRegexLinesFromFile("userAgentWhiteList_initial.txt", "userAgentWhiteList.txt")
  
  private def urlInList(req: HttpRequest, list: Set[Regex]) = {
    val requestURL = req.uri.toString
    ! list.forall(regex => (regex.findFirstIn(requestURL) == None))
  }
  
  def urlInWhiteList(req: HttpRequest) = urlInList(req, urlWhiteList)
  def urlInBlackList(req: HttpRequest) = urlInList(req, urlBlackList)
  def urlInDoNotDecorateList(req: HttpRequest) = urlInList(req, urlDoNotDecorateList)
  
  private def UAInWhiteList(req: HttpRequest) = {
    req.header[`User-Agent`] match {
      case Some(ua) =>
        // Return true if no match
        ! userAgentWhiteList.forall(regex => (regex.findFirstIn(ua.value) == None))
      case None =>
        false
    }
  }
  
  def requestAction(req: HttpRequest, userData: UserData) : Int = {
    
    // Do proxy (except black list) if ...
    if( userData.policy == POLICY_PASS || 
        urlInWhiteList(req) || 
        userData.inline || 
        (userData.policy == POLICY_WEAK && UAInWhiteList(req)) ) 
          if(!urlInBlackList(req)) ACTION_PROXY else ACTION_BLOCK
          
    // Otherwise, redirect what can be redirected (text/html) and block all the rest   
    else {
      val acceptHeader = req.header[Accept].toString
      if(acceptHeader.contains("*/*") || acceptHeader.contains("text/html")) ACTION_REDIRECT
      else ACTION_BLOCK
    }

  }
  
  def responseAction(res: HttpResponse, req: HttpRequest, userData: UserData) : Int = {    
    // Decorate if content type is text/html
    if(res.status.intValue == 200 && res.entity.contentType.toString().contains("text/html") && userData.inline && !urlInDoNotDecorateList(req)) PROXY_DECORATE else PROXY_DIRECT
  } 
}