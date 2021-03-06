package no.ndla.imageapi.service

import java.io.ByteArrayInputStream

import com.typesafe.scalalogging.LazyLogging
import io.digitallibrary.language.model.LanguageTag
import no.ndla.imageapi.auth.User
import no.ndla.imageapi.model.api._
import no.ndla.imageapi.model.domain.{Image, ImageMetaInformation, LanguageField}
import no.ndla.imageapi.model.{ImageNotFoundException, ValidationException, domain}
import no.ndla.imageapi.repository.ImageRepository
import no.ndla.imageapi.service.search.IndexService
import org.scalatra.servlet.FileItem

import scala.util.{Failure, Success, Try}

trait WriteService {
  this: ConverterService with ValidationService with ImageRepository with IndexService with ImageStorageService with Clock with User =>
  val writeService: WriteService

  class WriteService extends LazyLogging {
    def storeImageVariant(imageUrl: String, imageVariant: ImageVariant): Try[ImageVariant] = {
      imageRepository.getImageVariant(imageUrl, imageVariant.ratio) match {
        case None => imageRepository.insertImageVariant(converterService.asDomainImageVariant(imageUrl, imageVariant)).map(converterService.asApiImageVariant)
        case Some(storedVariant) => {
          val toUpdate = storedVariant.copy(topLeftX = imageVariant.x, topLeftY = imageVariant.y, width = imageVariant.width, height = imageVariant.height, revision = imageVariant.revision)
          imageRepository.updateImageVariant(toUpdate).map(converterService.asApiImageVariant)
        }
      }
    }

    def storeNewImage(newImage: NewImageMetaInformationV2, file: FileItem): Try[ImageMetaInformation] = {
      validationService.validateImageFile(file) match {
        case Some(validationMessage) => return Failure(new ValidationException(errors = Seq(validationMessage)))
        case _ =>
      }

      validationService.validateLicense(newImage.copyright.license) match {
        case validationMessage :: rest => return Failure(new ValidationException(errors = validationMessage :: rest))
        case _ =>
      }

      val domainImage = uploadImage(file).map(uploadedImage =>
        converterService.asDomainImageMetaInformationV2(newImage, uploadedImage)) match {
        case Failure(e) => return Failure(e)
        case Success(image) => image
      }

      validationService.validate(domainImage) match {
        case Failure(e) =>
          imageStorage.deleteObject(domainImage.imageUrl)
          return Failure(e)
        case _ =>
      }

      val imageMeta = Try(imageRepository.insertImageMeta(domainImage, Some(newImage.externalId))) match {
        case Success(meta) => meta
        case Failure(e) =>
          imageStorage.deleteObject(domainImage.imageUrl)
          return Failure(e)
      }

      indexService.indexDocument(imageMeta) match {
        case Success(_) => Success(imageMeta)
        case Failure(e) =>
          imageStorage.deleteObject(domainImage.imageUrl)
          imageRepository.delete(imageMeta.id.get)
          Failure(e)
      }
    }

    private[service] def mergeImages(existing: ImageMetaInformation, toMerge: UpdateImageMetaInformation): domain.ImageMetaInformation = {
      val now = clock.now()
      val userId = authUser.userOrClientid()


      existing.copy(
        titles = mergeLanguageFields(existing.titles, toMerge.title.toSeq.map(t => converterService.asDomainTitle(t, LanguageTag(toMerge.language)))),
        alttexts = mergeLanguageFields(existing.alttexts, toMerge.alttext.toSeq.map(a => converterService.asDomainAltText(a, LanguageTag(toMerge.language)))),
        copyright = toMerge.copyright.map(c => converterService.toDomainCopyright(c)).getOrElse(existing.copyright),
        tags = mergeTags(existing.tags, toMerge.tags.toSeq.map(t => converterService.toDomainTag(t, LanguageTag(toMerge.language)))),
        captions = mergeLanguageFields(existing.captions, toMerge.caption.toSeq.map(c => converterService.toDomainCaption(c, LanguageTag(toMerge.language)))),
        updated = now,
        updatedBy = userId
      )
    }

    private def mergeTags(existing: Seq[domain.ImageTag], updated: Seq[domain.ImageTag]): Seq[domain.ImageTag] = {
      val toKeep = existing.filterNot(item => updated.map(_.language).contains(item.language))
      (toKeep ++ updated).filterNot(_.tags.isEmpty)
    }

    def updateImage(imageId: Long, image: UpdateImageMetaInformation): Try[ImageMetaInformationV2] = {
      val updateImage = imageRepository.withId(imageId) match {
        case None => Failure(new ImageNotFoundException(s"Image with id $imageId found"))
        case Some(existing) => {
          image.copyright.map(c => validationService.validateLicense(c.license)) match {
            case Some(validationMessage :: rest) => return Failure(new ValidationException(errors = validationMessage :: rest))
            case _ => Success(mergeImages(existing, image))
          }
        }
      }

      updateImage.flatMap(validationService.validate)
        .map(imageMeta => imageRepository.updateImageMeta(imageMeta, imageId))
        .flatMap(indexService.indexDocument)
        .map(updatedImage => converterService.asApiImageMetaInformationWithDomainUrlAndSingleLanguage(updatedImage, LanguageTag(image.language), imageRepository.getImageVariants(updatedImage.imageUrl)).get)
    }

    private[service] def mergeLanguageFields[A <: LanguageField[String]](existing: Seq[A], updated: Seq[A]): Seq[A] = {
      val toKeep = existing.filterNot(item => updated.map(_.language).contains(item.language))
      (toKeep ++ updated).filterNot(_.value.isEmpty)
    }

    private[service] def getFileExtension(fileName: String): Option[String] = {
      fileName.lastIndexOf(".") match {
        case index: Int if index > -1 => Some(fileName.substring(index))
        case _ => None
      }
    }

    private[service] def uploadImage(file: FileItem): Try[Image] = {
      val contentType = file.getContentType.getOrElse("")
      val bytes = file.get()
      val storageKey = md5Hash(bytes)
      if (imageStorage.objectExists(storageKey)) {
        logger.info(s"$storageKey already exists, skipping upload and using existing image")
        Success(Image(storageKey, file.size, contentType))
      } else {
        imageStorage.uploadFromStream(new ByteArrayInputStream(bytes), storageKey, contentType, file.size).map(filePath => {
          Image(filePath, file.size, contentType)
        })
      }
    }

    def filenameToHashFilename(filename: String, hash: String): String = {
      val extension = filename.lastIndexOf(".") match {
        case index: Int if index > -1 => filename.substring(index + 1)
        case _ => ""
      }
      s"$hash.$extension"
    }

    def md5Hash(bytes: Array[Byte]): String =
      java.security.MessageDigest.getInstance("MD5").digest(bytes).map(0xFF & _).map {
        "%02x".format(_)
      }.foldLeft("") {
        _ + _
      }

  }

}
