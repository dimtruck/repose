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
package features.services.opentracing

import org.openrepose.framework.test.ReposeValveTest
import org.openrepose.framework.test.mocks.MockTracer
import org.rackspace.deproxy.Deproxy
import org.rackspace.deproxy.Handlers
import org.rackspace.deproxy.MessageChain
import spock.lang.Unroll

/**
 * Tests that sampling rate limiting type is set to 5 rps
 */
class OpenTracingServiceRateLimitedTest extends ReposeValveTest {

    def static originEndpoint
    def static identityEndpoint

    static MockTracer fakeTracer

    static String TRACING_HEADER = "uber-trace-id"

    def static slurper = new groovy.json.JsonSlurper()


    def setupSpec() {

        deproxy = new Deproxy()

        def params = properties.defaultTemplateParams
        repose.configurationProvider.applyConfigs("common", params)
        repose.configurationProvider.applyConfigs("features/services/opentracing/common", params)
        repose.configurationProvider.applyConfigs("features/services/opentracing/enabledratelimiting", params)

        originEndpoint = deproxy.addEndpoint(params.targetPort, 'origin service')

        fakeTracer = new MockTracer(params.tracingPort, true)

        repose.start([waitOnJmxAfterStarting: false])
        repose.waitForNon500FromUrl(reposeEndpoint)
    }

    @Unroll("Should report at most 5 spans per second with #method")
    def "when OpenTracing config is specified and enabled, trace information is passed in trace header"() {
        def traceCount = 0
        def traceList = []

        List<Thread> clientThreads = new ArrayList<Thread>()

        given:
        // sleep 1 second so that the rate limiter is reset
        sleep(1000)

        def startTime = System.currentTimeMillis()
        def thread = Thread.start {
            (0..<10).each {
                def messageChain = deproxy.makeRequest(url: reposeEndpoint, method: method)
                if (messageChain.handlings.get(0).request.headers.contains(TRACING_HEADER)) {
                    traceCount++
                    traceList << URLDecoder.decode(
                        messageChain.handlings.get(0).request.headers.getFirstValue(TRACING_HEADER),
                        "UTF-8")

                }
            }
        }

        when: "10 Requests are sent through repose"
        clientThreads.add(thread)

        then: "Request sent to origin should be rate limited"
        clientThreads*.join()
        traceCount == 10
        def stopTime = System.currentTimeMillis()

        and: "OpenTracingService has logged that span was reported no more than 5 times"
        def numberOfTimesReported = 0

        traceList.each {
            def logLines = reposeLogSearch.searchByString("Span reported: $it")
            if (logLines.size() == 1)
                numberOfTimesReported ++
        }

        System.out.println("Total time spent in milliseconds: ${stopTime - startTime}")
        System.out.println("Total time spent in seconds: ${Math.ceil((stopTime - startTime) / 1000)} -- " +
            "number of reported traces: $numberOfTimesReported")

        // calculate the time spent between start of test and end.  Divide by 1000 to get seconds
        // get the ceiling since the rate limit is calculated per second
        // multiply by 5 since we set the rate limit to 5 requests per second
        // add 1 to get the variance factor
        assert numberOfTimesReported <= Math.ceil((stopTime - startTime) / 1000) * 5 + 1

        where:
        method   | _
        "GET"    | _
        "PUT"    | _
        "POST"   | _
        "PATCH"  | _
        "DELETE" | _
        "TRACE"  | _
        "HEAD"   | _
    }
}