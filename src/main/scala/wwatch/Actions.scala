package wwatch

import akka.http.scaladsl.model._
import akka.http.scaladsl.model.headers._

import scala.io.Source

object Actions {
  
  val ACTION_REDIRECT = 1
  val ACTION_BLOCK = 2
  val ACTION_PROXY = 3
  val ACTION_LOCAL = 4
  
  val PROXY_DIRECT = 5
  val PROXY_DECORATE = 6
  
  
  // Get the list of hosts to force redirect/block/free
  val hostBlackList = Source.fromResource("hostBlackList_initial.txt").getLines.filter(line => !line.isEmpty() && !line.trim.startsWith("#")).toSet ++ Source.fromResource("hostBlackList.txt").getLines.filter(line => !line.isEmpty()).toSet
  val hostWhiteList = Source.fromResource("hostWhiteList_initial.txt").getLines.filter(line => !line.isEmpty() && !line.trim.startsWith("#")).toSet ++ Source.fromResource("hostWhiteList.txt").getLines.filter(line => !line.isEmpty()).toSet
  // Support not yet implemented
  val hostFreeList = Source.fromResource("hostFreeList_initial.txt").getLines.filter(line => !line.isEmpty() && !line.trim.startsWith("#")).toSet ++ Source.fromResource("hostFreeList.txt").getLines.filter(line => !line.isEmpty()).toSet
  
  def hostInWhiteList(req: HttpRequest) = req.header[Host].map(h => hostWhiteList.contains(h.value)).getOrElse(false)
  
  def requestAction(req: HttpRequest, toRedirect: Boolean) : Int = {
    if(req.header[Host].map(h => hostBlackList.contains(h.value)).getOrElse(false)) ACTION_BLOCK
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