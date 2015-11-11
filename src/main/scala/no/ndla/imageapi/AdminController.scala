package no.ndla.imageapi

import com.typesafe.scalalogging.LazyLogging
import no.ndla.imageapi.integration.AmazonIntegration
import org.json4s.{DefaultFormats, Formats}
import org.scalatra.ScalatraServlet
import org.scalatra.json.NativeJsonSupport

class AdminController extends ScalatraServlet with NativeJsonSupport with LazyLogging  {

  protected implicit override val jsonFormats: Formats = DefaultFormats

  val meta = AmazonIntegration.getImageMeta()
  val search = AmazonIntegration.getSearchMeta()

  def indexDocuments(indexName: String) = {
    val start = System.currentTimeMillis()
    logger.info(s"Indexing ${meta.elements.length} documents into index $indexName")
    search.createIndex(indexName)
    search.indexDocuments(meta.elements, indexName)
    search.useIndex(indexName)
    val result = s"Indexing took ${System.currentTimeMillis() - start} ms."
    logger.info(result)
    s"$result\nnew alias: ${search.IndexName} -> $indexName\n"
  }

  post("/index") {
    val indexName = params.get("indexName")
    indexName match {
      case None => "please specify index name.\n"
      case Some(search.IndexName) => s"index name cannot be the same as alias name (${search.IndexName})\n"
      case Some(idx) => indexDocuments(idx)
    }
  }
}
