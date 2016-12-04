/*
 * _=_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_=
 * Repose
 * _-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-
 * Copyright (C) 2010 - 2015 Rackspace US, Inc.
 * _-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 * =_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_=_
 */
package org.openrepose.filters.cors

import java.net.{URI, URL}
import java.util.regex.Pattern
import javax.inject.{Inject, Named}
import javax.servlet._
import javax.servlet.http.{HttpServletRequest, HttpServletResponse}
import javax.ws.rs.HttpMethod
import javax.ws.rs.core.MediaType

import com.google.common.net.InetAddresses
import com.typesafe.scalalogging.slf4j.LazyLogging
import org.apache.http.client.utils.URIBuilder
import org.openrepose.commons.config.manager.UpdateListener
import org.openrepose.commons.utils.http.{CommonHttpHeader, CorsHttpHeader, HeaderConstant}
import org.openrepose.commons.utils.servlet.http.{HttpServletRequestWrapper, HttpServletResponseWrapper, ResponseMode}
import org.openrepose.core.filter.FilterConfigHelper
import org.openrepose.core.services.config.ConfigurationService
import org.openrepose.filters.cors.config.CorsConfig

import scala.collection.JavaConverters._
import scala.language.implicitConversions
import scala.util.Try
import scala.util.matching.Regex

@Named
class CorsFilter @Inject()(configurationService: ConfigurationService)
  extends Filter with UpdateListener[CorsConfig] with LazyLogging {

  import CorsFilter._

  private var configurationFile: String = DEFAULT_CONFIG
  private var initialized = false
  private var allowedOrigins: Seq[Regex] = _
  private var allowedMethods: Seq[String] = _
  private var resources: Seq[Resource] = _

  override def init(filterConfig: FilterConfig): Unit = {
    logger.trace("CORS filter initializing...")
    configurationFile = new FilterConfigHelper(filterConfig).getFilterConfig(DEFAULT_CONFIG)

    logger.info(s"Initializing CORS Filter using config $configurationFile")
    val xsdUrl: URL = getClass.getResource(SCHEMA_FILE_NAME)
    configurationService.subscribeTo(filterConfig.getFilterName, configurationFile, xsdUrl, this, classOf[CorsConfig])

    logger.trace("CORS filter initialized.")
  }

  override def doFilter(servletRequest: ServletRequest, servletResponse: ServletResponse, filterChain: FilterChain): Unit = {
    if (!isInitialized) {
      logger.error("Filter has not yet initialized... Please check your configuration files and your artifacts directory.")
      servletResponse.asInstanceOf[HttpServletResponse].sendError(HttpServletResponse.SC_SERVICE_UNAVAILABLE)
    } else {
      val httpServletRequest = new HttpServletRequestWrapper(servletRequest.asInstanceOf[HttpServletRequest])
      val httpServletResponse = new HttpServletResponseWrapper(servletResponse.asInstanceOf[HttpServletResponse],
        ResponseMode.MUTABLE, ResponseMode.MUTABLE)

      val requestType = determineRequestType(httpServletRequest)

      val validationResult = requestType match {
        case NonCorsRequest(_) => Pass(Seq.empty)
        case InvalidCorsRequest(message, _) => BadRequest(message)
        case PreflightCorsRequest(origin, method, _) => validateCorsRequest(origin, method, httpServletRequest.getRequestURI)
        case ActualCorsRequest(origin, _) => validateCorsRequest(origin, httpServletRequest.getMethod, httpServletRequest.getRequestURI)
      }

      validationResult match {
        case Pass(validMethods) =>
          requestType match {
            case NonCorsRequest(_) => filterChain.doFilter(httpServletRequest, httpServletResponse)
            case PreflightCorsRequest(origin, _, _) =>
              logger.trace("Allowing preflight request.")
              httpServletResponse.setHeader(CorsHttpHeader.ACCESS_CONTROL_ALLOW_CREDENTIALS, true.toString)
              httpServletResponse.setHeader(CorsHttpHeader.ACCESS_CONTROL_ALLOW_ORIGIN, origin)
              httpServletRequest.getSplittableHeaderScala(CorsHttpHeader.ACCESS_CONTROL_REQUEST_HEADERS) foreach {
                httpServletResponse.appendHeader(CorsHttpHeader.ACCESS_CONTROL_ALLOW_HEADERS, _)
              }
              validMethods foreach {
                httpServletResponse.appendHeader(CorsHttpHeader.ACCESS_CONTROL_ALLOW_METHODS, _)
              }
              httpServletResponse.setStatus(HttpServletResponse.SC_OK)
            case ActualCorsRequest(origin, _) =>
              logger.trace("Allowing actual request.")
              filterChain.doFilter(httpServletRequest, httpServletResponse)
              httpServletResponse.setHeader(CorsHttpHeader.ACCESS_CONTROL_ALLOW_CREDENTIALS, true.toString)
              httpServletResponse.setHeader(CorsHttpHeader.ACCESS_CONTROL_ALLOW_ORIGIN, origin)

              // clone the list of header names so we can add headers while we iterate through it
              (List.empty ++ httpServletResponse.getHeaderNames.asScala) foreach {
                httpServletResponse.appendHeader(CorsHttpHeader.ACCESS_CONTROL_EXPOSE_HEADERS, _)
              }
          }
        case OriginNotAllowed(origin) =>
          logger.debug("Request rejected because origin '{}' is not allowed.", origin)
          httpServletResponse.setStatus(HttpServletResponse.SC_FORBIDDEN)
        case MethodNotAllowed(origin, method, resource) =>
          logger.debug("Request rejected because method '{}' is not allowed for resource '{}'.", method, resource)
          httpServletResponse.setHeader(CorsHttpHeader.ACCESS_CONTROL_ALLOW_ORIGIN, origin)
          httpServletResponse.setStatus(HttpServletResponse.SC_FORBIDDEN)
        case BadRequest(message) =>
          // TODO: update to httpServletResponse.sendError(HttpServletResponse.SC_BAD_REQUEST, message)
          httpServletResponse.setStatus(HttpServletResponse.SC_BAD_REQUEST)
          httpServletResponse.setOutput(null)
          httpServletResponse.setContentType(MediaType.TEXT_PLAIN)
          httpServletResponse.getOutputStream.print(message)
      }

      // always add the Vary header
      httpServletResponse.addHeader(CommonHttpHeader.VARY, CorsHttpHeader.ORIGIN)
      if (requestType.isOptions) {
        httpServletResponse.addHeader(CommonHttpHeader.VARY, CorsHttpHeader.ACCESS_CONTROL_REQUEST_HEADERS)
        httpServletResponse.addHeader(CommonHttpHeader.VARY, CorsHttpHeader.ACCESS_CONTROL_REQUEST_METHOD)
      }

      httpServletResponse.commitToResponse()
    }
  }

  override def destroy(): Unit = {
    logger.trace("CORS filter destroying...")
    configurationService.unsubscribeFrom(configurationFile, this.asInstanceOf[UpdateListener[_]])
    logger.trace("CORS filter destroyed.")
  }

  override def configurationUpdated(config: CorsConfig): Unit = {
    allowedOrigins = config.getAllowedOrigins.getOrigin.asScala.map { origin =>
      if (origin.isRegex) origin.getValue.r else Pattern.quote(origin.getValue).r
    }

    allowedMethods = Option(config.getAllowedMethods).map(_.getMethod.asScala).getOrElse(List())

    resources = Option(config.getResources)
      .map(_.getResource.asScala).getOrElse(List())
      .map { configResource =>
      Resource(configResource.getPath.r,
        Option(configResource.getAllowedMethods).map(_.getMethod.asScala).getOrElse(List()))}

    initialized = true
  }

  override def isInitialized: Boolean = initialized

  def determineRequestType(request: HttpServletRequestWrapper): RequestType = {
    val originHeader = request.getHeader(CorsHttpHeader.ORIGIN)
    val isOptionsRequest = request.getMethod == HttpMethod.OPTIONS
    val preflightRequestedMethod = request.getHeader(CorsHttpHeader.ACCESS_CONTROL_REQUEST_METHOD)
    lazy val originUri = getOriginUri(originHeader)
    lazy val hostUri = getHostUri(request)

    (Option(originHeader), isOptionsRequest, Option(preflightRequestedMethod)) match {
      case (None, isOptions, _) => NonCorsRequest(isOptions)
      case (Some(origin), true, Some(requestedMethod)) => PreflightCorsRequest(origin, requestedMethod)
      case (Some(_), isOptions, _) if originUri.isFailure => InvalidCorsRequest("Bad Origin header", isOptions)
      case (Some(_), isOptions, _) if originUri.get == hostUri => NonCorsRequest(isOptions)
      case (Some(origin), isOptions, _) => ActualCorsRequest(origin, isOptions)
    }
  }

  def validateCorsRequest(origin: String, method: String, requestUri: String): CorsValidationResult =
    (isOriginAllowed(origin), getValidMethodsForResource(requestUri)) match {
      case (true, validMethods) if validMethods.contains(method) => Pass(validMethods)
      case (false, _) => OriginNotAllowed(origin)
      case (true, _) => MethodNotAllowed(origin, method, requestUri)
    }

  def isOriginAllowed(requestOrigin: String): Boolean = allowedOrigins.exists(_.findFirstIn(requestOrigin).isDefined)

  def getValidMethodsForResource(path: String): Seq[String] = {
    allowedMethods ++ (resources.find(_.path.findFirstIn(path).isDefined) match {
      case Some(matchedResource) =>
        logger.trace("Matched path '{}' with configured resource '{}'.", path, matchedResource)
        matchedResource.methods
      case None =>
        logger.trace("Did not find a configured resource matching path '{}'.", path)
        Nil
    })
  }

  def getHostUri(request: HttpServletRequestWrapper): URI = {
    Try(request.getSplittableHeaderScala(CommonHttpHeader.X_FORWARDED_HOST).headOption
      .map(forwardedHost => new URIBuilder(s"${request.getScheme}://$forwardedHost"))
      .map(uri => uri.setPort(normalizePort(uri.getPort, uri.getScheme)).setHost(normalizeUriHost(uri.getHost)).build()))
      .getOrElse(None)
      .getOrElse(new URIBuilder().setScheme(request.getScheme).setHost(normalizeHost(request.getServerName))
        .setPort(normalizePort(request.getServerPort, request.getScheme)).build())
  }

  def getOriginUri(origin: String): Try[URI] = Try(new URIBuilder(origin))
    .map(originUri => originUri
      .setPort(normalizePort(originUri.getPort, originUri.getScheme))
      .setHost(normalizeUriHost(originUri.getHost))
      .build())

  def normalizeHost(host: String): String =
    if (InetAddresses.isInetAddress(host)) InetAddresses.forString(host).getHostAddress else host

  def normalizeUriHost(host: String): String =
    if (InetAddresses.isUriInetAddress(host)) InetAddresses.forUriString(host).getHostAddress else host

  def normalizePort(port: Int, scheme: String): Int = (port, scheme.toLowerCase) match {
    case (p, _) if p > 0 => p
    case (_, s) if s == "http" => 80
    case (_, s) if s == "https" => 443
    case _ => port
  }
}

object CorsFilter {
  private final val DEFAULT_CONFIG = "cors.cfg.xml"
  private final val SCHEMA_FILE_NAME = "/META-INF/schema/config/cors-configuration.xsd"

  implicit def autoHeaderToString(hc: HeaderConstant): String = hc.toString

  sealed trait RequestType {
    def isOptions: Boolean
  }

  case class NonCorsRequest(isOptions: Boolean) extends RequestType
  case class PreflightCorsRequest(origin: String, requestedMethod: String, isOptions: Boolean = true) extends RequestType
  case class ActualCorsRequest(origin: String, isOptions: Boolean) extends RequestType
  case class InvalidCorsRequest(message: String, isOptions: Boolean) extends RequestType

  sealed trait CorsValidationResult
  case class Pass(validMethods: Seq[String]) extends CorsValidationResult
  case class OriginNotAllowed(origin: String) extends CorsValidationResult
  case class MethodNotAllowed(origin: String, method: String, resource: String) extends CorsValidationResult
  case class BadRequest(message: String) extends CorsValidationResult

  case class Resource(path: Regex, methods: Seq[String])
}