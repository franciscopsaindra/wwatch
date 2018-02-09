package wwatch

import akka.util.ByteString
import com.typesafe.config.ConfigFactory
import akka.http.scaladsl.model._
import scala.io.Source
import java.io.File
import java.nio.file.Files
import java.nio.file.Paths

object Content { 
  
  // Requests to local content will be preceded by this
  val myGUID = "/947008c4-20ab-426d-9087-10c1b04266e7/"
  
  // Does nothing. Just make sure the object gets initialized
  def init = Unit
  
  val config = ConfigFactory.load()
  
  // Will hold the absolute path of the content directory
  val contentDirectory = new File(config.getString("wwatch.content.directory"))
  // Map(String -> (ByteString, ContentType)]
  val objectMap = getObjectMap(contentDirectory, contentDirectory)
  
  /**
   * Get iteratively the contents of the files to serve as a map of paths to ByteStrings
   */
  def getObjectMap(contentDir: File, baseDir : File) : Map[String, (ByteString, ContentType)] = {
    val basePath = baseDir.toURI
    val maps = for {
      file <- contentDir.listFiles.toList
      partialMap = if(file.isFile) Map(file.toURI.toString.replaceFirst(basePath.toString, "") -> (ByteString.fromArray(Files.readAllBytes(Paths.get(file.getPath))), getContentType(file.getPath)  )   ) else getObjectMap(file, baseDir)
    } yield partialMap
    maps.foldLeft(Map[String, (ByteString, ContentType)]())((acc, item) => (acc ++ item))
  }
  
  // Gets the object bytes as a ByteString
  def getObject(objectName: String) = objectMap(objectName)._1
  
  // Gets the object as an HttpEntity
  def getEntity(objectName: String) = HttpEntity(objectMap(objectName)._1).withContentType(objectMap(objectName)._2)
  
  // Gets all objects
  def getObjects = objectMap
  
  // Baked redirect page, replacing the $url by the passed parameter
  def getRedirectPage(url: String) = {
    val page = getObject("redirect.html")
    
    // Replace $url in the page content
    val targetPos = page.indexOfSlice("$url")
      if(targetPos == -1) page
      else {
        val (head, tail) = page.splitAt(targetPos)
        val toReply = head.concat(ByteString(url)).concat(tail.drop(4))
        toReply
      } 
  }
  
  private def getContentType(file: String) : ContentType = {
    val dotPosition = file.lastIndexOf(".")
    if(dotPosition == -1) ContentTypes.`application/octet-stream`
    else {
      file.substring(dotPosition + 1) match {
        case "html" => ContentTypes.`text/html(UTF-8)`
        case "js" => MediaTypes.`application/javascript`.toContentTypeWithMissingCharset
        case "css" =>  MediaTypes.`text/css`.toContentTypeWithMissingCharset
        case "jpg" => MediaTypes.`image/jpeg`.toContentType
        case "png" => MediaTypes.`image/png`.toContentType
        case "gif" => MediaTypes.`image/gif`.toContentType
        case _ => ContentTypes.`application/octet-stream`
      }
    }
  }
}