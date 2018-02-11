package wwatch

import akka.http.scaladsl.model._
import akka.http.scaladsl.model.headers._

import scala.io.Source
import scala.util.matching.Regex

object Actions {
  
  val ACTION_REDIRECT = 1
  val ACTION_BLOCK = 2
  val ACTION_PROXY = 3
  val ACTION_LOCAL = 4
  
  val PROXY_DIRECT = 5
  val PROXY_DECORATE = 6
  
  
  // Get the list of hosts to force redirect/block/free
  def getRegexLinesFromFile(initialFile: String, configFile: String) = (Source.fromResource(initialFile).getLines.toSet ++ (if(getClass.getClassLoader.getResource(configFile) == null) Set() else Source.fromResource(configFile).getLines))
        .flatMap{ line => {
          if(!line.isEmpty && ! line.trim.startsWith("#")) Seq(new Regex(line)) else Seq()
        }}
  
  val hostWhiteList = getRegexLinesFromFile("hostWhiteList_initial.txt", "hostWhiteList.txt")
  val hostBlackList = getRegexLinesFromFile("hostBlackList_initial.txt", "hostBlackList.txt")
  val hostFreeList = getRegexLinesFromFile("hostFreeList_initial.txt", "hostFreeList.txt")
  
  private def hostInList(req: HttpRequest, list: Set[Regex]) = {
    req.header[Host] match {
      case Some(host) =>
        // Return true if no match
        ! list.forall(regex => (regex.findFirstIn(host.value) == None))
      case None =>
        false
    }
  }
  
  def hostInWhiteList(req: HttpRequest) = hostInList(req, hostWhiteList)
  def hostInBlackList(req: HttpRequest) = hostInList(req, hostBlackList)
  
  def requestAction(req: HttpRequest, toRedirect: Boolean) : Int = {
    if(hostInBlackList(req)) ACTION_BLOCK
    if(!toRedirect) ACTION_PROXY 
    else {
      val acceptHeader = req.header[Accept].toString
      if(!acceptHeader.contains("*/*") && !acceptHeader.contains("text/html")) ACTION_BLOCK else ACTION_REDIRECT
    } 
  }
  
  def responseAction(res: HttpResponse, inWhiteList: Boolean) : Int = {    
    if(inWhiteList) PROXY_DIRECT
    else {
      // Redirect if content type is text/html
      if(res.status.intValue == 200 && res.entity.contentType.toString().contains("text/html")) PROXY_DECORATE else PROXY_DIRECT
    }
  }
}