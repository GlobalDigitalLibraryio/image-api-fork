/*
 * Part of NDLA image_api.
 * Copyright (C) 2016 NDLA
 *
 * See LICENSE
 */

package no.ndla.imageapi.service.search

import com.typesafe.scalalogging.LazyLogging
import no.ndla.imageapi.ImageApiProperties
import no.ndla.imageapi.model.domain.{ImageMetaInformation, ReindexResult}
import no.ndla.imageapi.repository.ImageRepository

import scala.util.{Failure, Success, Try}

trait IndexBuilderService {
  this: ImageRepository with IndexService =>
  val indexBuilderService: IndexBuilderService

  class IndexBuilderService extends LazyLogging {

    def indexDocument(imported: ImageMetaInformation): Try[ImageMetaInformation] = {
      for {
        _ <- indexService.aliasTarget.map {
          case Some(index) => Success(index)
          case None => indexService.createIndex().map(newIndex => indexService.updateAliasTarget(None, newIndex))
        }
        imported <- indexService.indexDocument(imported)
      } yield imported
    }

    def indexDocuments: Try[ReindexResult] = {
      synchronized {
        val start = System.currentTimeMillis()
        indexService.createIndex().flatMap(indexName => {
          val operations = for {
            numIndexed <- sendToElastic(indexName)
            aliasTarget <- indexService.aliasTarget
            updatedTarget <- indexService.updateAliasTarget(aliasTarget, indexName)
            deleted <- indexService.deleteIndex(aliasTarget)
          } yield numIndexed

          operations match {
            case Failure(f) => {
              indexService.deleteIndex(Some(indexName))
              Failure(f)
            }
            case Success(totalIndexed) => {
              Success(ReindexResult(totalIndexed, System.currentTimeMillis() - start))
            }
          }
        })
      }
    }

    def sendToElastic(indexName: String): Try[Int] = {
      var numIndexed = 0
      getRanges.map(ranges => {
        ranges.foreach(range => {
          val numberInBulk = indexService.indexDocuments(imageRepository.imagesWithIdBetween(range._1, range._2), indexName)
          numberInBulk match {
            case Success(num) => numIndexed += num
            case Failure(f) => return Failure(f)
          }
        })
        numIndexed
      })
    }

    def getRanges:Try[List[(Long,Long)]] = {
      Try{
        val (minId, maxId) = imageRepository.minMaxId
        Seq.range(minId, maxId).grouped(ImageApiProperties.IndexBulkSize).map(group => (group.head, group.last + 1)).toList
      }
    }
  }
}