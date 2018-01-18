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

package org.openrepose.core.services.serviceclient.akka.impl

import org.junit.runner.RunWith
import org.mockito.Mockito._
import org.openrepose.core.services.config.ConfigurationService
import org.openrepose.core.services.httpclient.{HttpClientContainer, HttpClientService}
import org.openrepose.core.services.opentracing.OpenTracingService
import org.scalatest.junit.JUnitRunner
import org.scalatest.mock.MockitoSugar
import org.scalatest.{FunSpec, Matchers}

@RunWith(classOf[JUnitRunner])
class AkkaServiceClientFactoryImplTest extends FunSpec with Matchers with MockitoSugar {

  val httpClientService = mock[HttpClientService]
  val httpClientContainer = mock[HttpClientContainer]
  val configurationService = mock[ConfigurationService]
  val openTracingService = mock[OpenTracingService]

  describe("the factory will return an instance when") {
    List(null, "", "test_conn_pool").foreach { connectionPoolId =>
      it(s"the specified connection pool id is $connectionPoolId") {
        when(httpClientService.getClient(connectionPoolId)).thenReturn(httpClientContainer)
        val akkaServiceClientFactoryImpl = new AkkaServiceClientFactoryImpl(
          httpClientService, configurationService, openTracingService)

        val akkaServiceClient = akkaServiceClientFactoryImpl.newAkkaServiceClient(connectionPoolId)

        akkaServiceClient should not be null
      }
    }

    it("the default method with no connection pool id is called") {
      when(httpClientService.getClient(null)).thenReturn(httpClientContainer)
      val akkaServiceClientFactoryImpl = new AkkaServiceClientFactoryImpl(
        httpClientService, configurationService, openTracingService)

      val akkaServiceClient = akkaServiceClientFactoryImpl.newAkkaServiceClient()

      akkaServiceClient should not be null
    }
  }
}
