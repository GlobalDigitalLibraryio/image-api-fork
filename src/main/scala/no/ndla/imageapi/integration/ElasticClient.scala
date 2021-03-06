/*
 * Part of NDLA image_api.
 * Copyright (C) 2016 NDLA
 *
 * See LICENSE
 *
 */

package no.ndla.imageapi.integration

import com.amazonaws.regions.{Region, Regions}
import com.sksamuel.elastic4s.ElasticsearchClientUri
import com.sksamuel.elastic4s.aws.Aws4ElasticClient
import com.sksamuel.elastic4s.http.{HttpClient, HttpExecutable, RequestSuccess}
import no.ndla.imageapi.ImageApiProperties
import no.ndla.imageapi.model.GdlSearchException

import scala.concurrent.Await
import scala.concurrent.duration.Duration
import scala.util.{Failure, Success, Try}

trait ElasticClient {
  val esClient: E4sClient
}

case class E4sClient(httpClient: HttpClient) {
  def execute[T, U](request: T)(implicit exec: HttpExecutable[T, U]): Try[RequestSuccess[U]] = {
    val response = Await.ready(httpClient.execute {
      request
    }, Duration(60, "s")).value.get

    response match {
      case Success(either) => either match {
        case Right(result) => Success(result)
        case Left(requestFailure) => Failure(new GdlSearchException(requestFailure))
      }
      case Failure(ex) => Failure(ex)
    }
  }
}

object EsClientFactory {
  def getClient(searchServer: String = ImageApiProperties.SearchServer): E4sClient = {
    ImageApiProperties.RunWithSignedSearchRequests match {
      case true => E4sClient(signingClient(searchServer))
      case false => E4sClient(nonSigningClient(searchServer))
    }
  }

  private def nonSigningClient(searchServer: String): HttpClient = {
    HttpClient(ElasticsearchClientUri(searchServer))
  }

  private def signingClient(searchServer: String): HttpClient = {
    val awsRegion = Option(Regions.getCurrentRegion).getOrElse(Region.getRegion(Regions.EU_CENTRAL_1)).toString
    setEnv("AWS_DEFAULT_REGION", awsRegion)
    Aws4ElasticClient(searchServer)
  }

  private def setEnv(key: String, value: String) = {
    val field = System.getenv().getClass.getDeclaredField("m")
    field.setAccessible(true)
    val map = field.get(System.getenv()).asInstanceOf[java.util.Map[java.lang.String, java.lang.String]]
    map.put(key, value)
  }
}