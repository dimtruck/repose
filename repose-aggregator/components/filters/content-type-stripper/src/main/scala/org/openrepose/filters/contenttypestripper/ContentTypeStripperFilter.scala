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
package org.openrepose.filters.contenttypestripper

import java.io.PushbackInputStream
import javax.inject.Named
import javax.servlet._
import javax.servlet.http.HttpServletRequest

import org.apache.commons.lang3.StringUtils
import org.apache.http.HttpHeaders
import org.openrepose.commons.utils.io.stream.ServletInputStreamWrapper
import org.openrepose.commons.utils.servlet.http.MutableHttpServletRequest

import scala.collection.JavaConversions.enumerationAsScalaIterator

class ContentTypeStripperFilter extends Filter {

  override def init(p1: FilterConfig): Unit = {}

  override def doFilter(request: ServletRequest, response: ServletResponse, chain: FilterChain): Unit = {
    val pushBackRequest = MutableHttpServletRequest.wrap(request.asInstanceOf[HttpServletRequest])
    if (pushBackRequest.getHeaderNames exists (_.equalsIgnoreCase(HttpHeaders.CONTENT_TYPE))) {
      val byteArray: Array[Byte] = new Array[Byte](8)
      val pushbackStream = new PushbackInputStream(pushBackRequest.getInputStream, 8)
      val bytesRead: Int = pushbackStream.read(byteArray, 0, 8)
      if (bytesRead == -1 || StringUtils.isBlank(new String(byteArray.slice(0, bytesRead)))) {
        pushBackRequest.removeHeader(HttpHeaders.CONTENT_TYPE)
      }
      pushbackStream.unread(byteArray.slice(0, bytesRead))
      pushBackRequest.setInputStream(new ServletInputStreamWrapper(pushbackStream))
    }
    chain.doFilter(pushBackRequest, response)
  }

  override def destroy(): Unit = {}
}
