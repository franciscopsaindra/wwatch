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
  
  // The content directory will be the one where the resource "redirect.html" is located, or the one specified
  val contentDirectory = {
    if(config.hasPath("wwatch.content.directory") && new File(config.getString("wwatch.content.directory")).exists) new File(config.getString("wwatch.content.directory"))
    else new File(getClass.getClassLoader.getResource("redirect.html").getPath).getParentFile
  }
  
  // Map(String -> (ByteString, ContentType)]
  val objectMap = getObjectMap(contentDirectory, contentDirectory).toMap
  
  /**
   * Get iteratively the contents of the Content directory
   */
  def getObjectMap(contentDir: File, baseDir : File) : Seq[(String, (ByteString, ContentType))] = {
    val basePath = baseDir.toURI
    val files = contentDir.listFiles.toList
    files.flatMap { file => {
        if(file.isFile) Seq(  file.toURI.toString.replaceFirst(basePath.toString, "") -> (ByteString.fromArray(Files.readAllBytes(Paths.get(file.getPath))), getContentType(file.getPath)))
        else getObjectMap(file, baseDir)
      }
    }
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
        head.concat(ByteString(url)).concat(tail.drop(4))
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