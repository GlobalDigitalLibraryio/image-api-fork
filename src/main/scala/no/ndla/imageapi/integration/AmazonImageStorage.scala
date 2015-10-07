/*
 * Part of NDLA Image-API. API for searching and downloading images from NDLA.
 * Copyright (C) 2015 NDLA
 *
 * See LICENSE
 */
package no.ndla.imageapi.integration

import java.io.{ByteArrayInputStream, File, InputStream}
import java.net.URL

import com.amazonaws.AmazonServiceException
import com.amazonaws.services.s3.AmazonS3Client
import com.amazonaws.services.s3.model._
import com.typesafe.scalalogging.LazyLogging
import no.ndla.imageapi.business.ImageStorage
import no.ndla.imageapi.model.{Image, ImageMetaInformation}

class AmazonImageStorage(imageStorageName: String, s3Client: AmazonS3Client) extends ImageStorage with LazyLogging {

  def get(imageKey: String): Option[(String, InputStream)] = {
    try{
      val s3Object = s3Client.getObject(new GetObjectRequest(imageStorageName, imageKey))
      Option(
        s3Object.getObjectMetadata().getContentType,
        s3Object.getObjectContent)
    } catch {
      case e:Exception => {
        logger.warn("Could not get image with key {}", imageKey, e)
        None
      }
    }
  }

  def upload(imageMetaInformation: ImageMetaInformation, imageDirectory: String) = {
    imageMetaInformation.images.small.foreach(small => {
      val thumbResult = s3Client.putObject(new PutObjectRequest(imageStorageName, small.url, new File(imageDirectory + small.url)))
    })

    imageMetaInformation.images.full.foreach(full => {
      s3Client.putObject(new PutObjectRequest(imageStorageName, full.url, new File(imageDirectory + full.url)))
    })

  }

  def uploadFromByteArray(image: Image, storageKey:String, bytes:Array[Byte]): Unit = {
    val metadata = new ObjectMetadata()
    metadata.setContentType(image.contentType)
    metadata.setContentLength(image.size.toLong)

    val request = new PutObjectRequest(imageStorageName, storageKey, new ByteArrayInputStream(bytes), metadata)
    val putResult = s3Client.putObject(request)
  }

  override def uploadFromUrl(image: Image, storageKey:String, urlOfImage: String): Unit = {
    val imageStream = new URL(urlOfImage).openStream()
    val metadata = new ObjectMetadata()
    metadata.setContentType(image.contentType)
    metadata.setContentLength(image.size.toLong)

    val request = new PutObjectRequest(imageStorageName, storageKey, imageStream, metadata)
    val putResult = s3Client.putObject(request)
  }

  def contains(storageKey: String): Boolean = {
      try {
        val s3Object = Option(s3Client.getObject(new GetObjectRequest(imageStorageName, storageKey)))
        s3Object match {
          case Some(obj) => {
            obj.close()
            true
          }
          case None => false
        }
      } catch {
        case ase: AmazonServiceException => if (ase.getErrorCode == "NoSuchKey") false else throw ase
      }
  }

  def create() = {
    s3Client.createBucket(new CreateBucketRequest(imageStorageName))
  }

  def exists(): Boolean = {
    s3Client.doesBucketExist(imageStorageName)
  }
}
