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
package features.filters.valkyrie

import framework.ReposeValveTest
import framework.mocks.MockIdentityService
import framework.mocks.MockValkyrie
import org.rackspace.deproxy.Deproxy
import org.rackspace.deproxy.MessageChain
import spock.lang.Unroll

class BasicValkyrieTest extends ReposeValveTest {
    def static originEndpoint
    def static identityEndpoint
    def static valkyrieEndpoint

    def static MockIdentityService fakeIdentityService
    def static MockValkyrie fakeValkyrie
    def static Map params = [:]

    def static random = new Random()

    def setupSpec() {
        deproxy = new Deproxy()

        params = properties.getDefaultTemplateParams()
        repose.configurationProvider.cleanConfigDirectory()
        repose.configurationProvider.applyConfigs("common", params);
        repose.configurationProvider.applyConfigs("features/filters/valkyrie", params);

        repose.start()

        originEndpoint = deproxy.addEndpoint(properties.targetPort, 'origin service')
        fakeIdentityService = new MockIdentityService(properties.identityPort, properties.targetPort)
        identityEndpoint = deproxy.addEndpoint(properties.identityPort, 'identity service', null, fakeIdentityService.handler)
        fakeIdentityService.checkTokenValid = true

        fakeValkyrie = new MockValkyrie(properties.valkyriePort)
        valkyrieEndpoint = deproxy.addEndpoint(properties.valkyriePort, 'valkyrie service', null, fakeValkyrie.handler)
    }

    def setup() {
        fakeIdentityService.resetHandlers()
        fakeIdentityService.resetDefaultParameters()
        fakeValkyrie.resetHandlers()
        fakeValkyrie.resetParameters()
    }

    def cleanupSpec() {
        if (deproxy) {
            deproxy.shutdown()
        }

        if (repose) {
            repose.stop()
        }
    }


    @Unroll("permission: #permission for #method with tenant: #tenantID and deviceID: #deviceID should return a #responseCode")
    def "Test fine grain access of resources based on Valkyrie permissions (no rbac)"() {
        given: "A device ID with a particular permission level defined in Valkyrie"

        fakeIdentityService.with {
            client_apikey = UUID.randomUUID().toString()
            client_token = UUID.randomUUID().toString()
            client_tenant = tenantID
        }

        fakeValkyrie.with {
            device_id = deviceID
            device_perm = permission
        }

        when: "a request is made against a device with Valkyrie set permissions"
        MessageChain mc = deproxy.makeRequest(url: reposeEndpoint + "/resource/" + deviceID, method: method,
                headers: [
                        'content-type': 'application/json',
                        'X-Auth-Token': fakeIdentityService.client_token,
                ]
        )

        then: "check response"
        mc.receivedResponse.code == responseCode
        //**This for tracing header on failed response REP-2147
        mc.receivedResponse.headers.contains("x-trans-id")
        //**This part for tracing header test REP-1704**
        // any requests send to identity also include tracing header
        mc.orphanedHandlings.each {
            e -> assert e.request.headers.contains("x-trans-id")
        }


        where:
        method   | tenantID                   | deviceID | permission      | responseCode
        "GET"    | randomTenant()             | "520707" | "view_product"  | "200"
        "HEAD"   | randomTenant()             | "520707" | "view_product"  | "200"
        "GET"    | randomTenant() - "hybrid:" | "520707" | "view_product"  | "403"
        "PUT"    | randomTenant()             | "520707" | "view_product"  | "403"
        "POST"   | randomTenant()             | "520707" | "view_product"  | "403"
        "DELETE" | randomTenant()             | "520707" | "view_product"  | "403"
        "PATCH"  | randomTenant()             | "520707" | "view_product"  | "403"
        "GET"    | randomTenant()             | "520707" | "admin_product" | "200"
        "HEAD"   | randomTenant()             | "520707" | "admin_product" | "200"
        "PUT"    | randomTenant()             | "520707" | "admin_product" | "200"
        "POST"   | randomTenant()             | "520707" | "admin_product" | "200"
        "PATCH"  | randomTenant()             | "520707" | "admin_product" | "200"
        "DELETE" | randomTenant()             | "520707" | "admin_product" | "200"
        "GET"    | randomTenant()             | "520707" | "edit_product"  | "200"
        "HEAD"   | randomTenant()             | "520707" | "edit_product"  | "200"
        "PUT"    | randomTenant()             | "520707" | "edit_product"  | "200"
        "POST"   | randomTenant()             | "520707" | "edit_product"  | "200"
        "PATCH"  | randomTenant()             | "520707" | "edit_product"  | "200"
        "DELETE" | randomTenant()             | "520707" | "edit_product"  | "200"
        "GET"    | randomTenant()             | "520707" | ""              | "403"
        "HEAD"   | randomTenant()             | "520707" | ""              | "403"
        "PUT"    | randomTenant()             | "520707" | ""              | "403"
        "POST"   | randomTenant()             | "520707" | ""              | "403"
        "PATCH"  | randomTenant()             | "520707" | ""              | "403"
        "DELETE" | randomTenant()             | "520707" | ""              | "403"
        "GET"    | randomTenant()             | "520707" | "shazbot_prod"  | "403"
        "HEAD"   | randomTenant()             | "520707" | "prombol"       | "403"
        "PUT"    | randomTenant()             | "520707" | "hezmol"        | "403"
        "POST"   | randomTenant()             | "520707" | "_22_reimer"    | "403"
        "PATCH"  | randomTenant()             | "520707" | "blah"          | "403"
        "DELETE" | randomTenant()             | "520707" | "blah"          | "403"

    }

    @Unroll("tenant missing prefix 'hybrid': #tenantID, permission: #permission for #method and deviceID: #deviceID should return a #responseCode")
    def "Repose return 403 if tenant coming from identity prefix 'hybrid' is missing"() {
        given: "A device ID with a particular permission level defined in Valkyrie"

        fakeIdentityService.with {
            client_apikey = UUID.randomUUID().toString()
            client_token = UUID.randomUUID().toString()
            client_tenant = tenantID
        }
        fakeValkyrie.with {
            device_id = deviceID
            device_perm = permission
        }

        when: "a request is made against a device with Valkyrie set permissions"
        MessageChain mc = deproxy.makeRequest(url: reposeEndpoint + "/resource/" + deviceID, method: method,
                headers: [
                        'content-type': 'application/json',
                        'X-Auth-Token': fakeIdentityService.client_token,
                ]
        )

        then: "check response"
        mc.receivedResponse.code == responseCode

        where:
        method   | tenantID                        | deviceID | permission      | responseCode
        "GET"    | random.nextInt()                | "520707" | "view_product"  | "403"
        "HEAD"   | random.nextInt()                | "520707" | "view_product"  | "403"
        "GET"    | random.nextInt()                | "520707" | "admin_product" | "403"
        "HEAD"   | random.nextInt()                | "520707" | "admin_product" | "403"
        "PUT"    | random.nextInt()                | "520707" | "admin_product" | "403"
        "POST"   | random.nextInt()                | "520707" | "admin_product" | "403"
        "PATCH"  | random.nextInt()                | "520707" | "admin_product" | "403"
        "DELETE" | random.nextInt()                | "520707" | "admin_product" | "403"
        "GET"    | random.nextInt()                | "520707" | "edit_product"  | "403"
        "HEAD"   | random.nextInt()                | "520707" | "edit_product"  | "403"
        "PUT"    | random.nextInt()                | "520707" | "edit_product"  | "403"
        "POST"   | random.nextInt()                | "520707" | "edit_product"  | "403"
        "PATCH"  | random.nextInt()                | "520707" | "edit_product"  | "403"
        "DELETE" | "dedicated:" + random.nextInt() | "520707" | "edit_product"  | "403"
        "GET"    | "dedicated:" + random.nextInt() | "520707" | "view_product"  | "403"
        "HEAD"   | "dedicated:" + random.nextInt() | "520707" | "view_product"  | "403"
        "GET"    | "dedicated:" + random.nextInt() | "520707" | "admin_product" | "403"
        "HEAD"   | "dedicated:" + random.nextInt() | "520707" | "admin_product" | "403"
        "PUT"    | "dedicated:" + random.nextInt() | "520707" | "admin_product" | "403"
        "POST"   | "dedicated:" + random.nextInt() | "520707" | "admin_product" | "403"
        "PATCH"  | "dedicated:" + random.nextInt() | "520707" | "admin_product" | "403"
        "DELETE" | "dedicated:" + random.nextInt() | "520707" | "admin_product" | "403"
        "GET"    | "dedicated:" + random.nextInt() | "520707" | "edit_product"  | "403"
        "HEAD"   | "dedicated:" + random.nextInt() | "520707" | "edit_product"  | "403"
        "PUT"    | "dedicated:" + random.nextInt() | "520707" | "edit_product"  | "403"
        "POST"   | "dedicated:" + random.nextInt() | "520707" | "edit_product"  | "403"
        "PATCH"  | "dedicated:" + random.nextInt() | "520707" | "edit_product"  | "403"
        "DELETE" | "dedicated:" + random.nextInt() | "520707" | "edit_product"  | "403"
    }

    @Unroll("Without tenantId - permission: #permission for #method and deviceID: #deviceID should return a #responseCode")
    def "Repose return 403 if missing tenantId"() {
        given: "A device ID with a particular permission level defined in Valkyrie"

        fakeIdentityService.with {
            client_apikey = UUID.randomUUID().toString()
            client_token = UUID.randomUUID().toString()
            client_tenant = ""
        }
        fakeValkyrie.with {
            device_id = deviceID
            device_perm = permission
        }

        when: "a request is made against a device with Valkyrie set permissions"
        MessageChain mc = deproxy.makeRequest(url: reposeEndpoint + "/resource/" + deviceID, method: method,
                headers: [
                        'content-type': 'application/json',
                        'X-Auth-Token': fakeIdentityService.client_token,
                ]
        )

        then: "check response"
        mc.receivedResponse.code == responseCode

        where:
        method   | deviceID | permission      | responseCode
        "GET"    | "520707" | "view_product"  | "401"
        "HEAD"   | "520707" | "view_product"  | "401"
        "GET"    | "520707" | "admin_product" | "401"
        "HEAD"   | "520707" | "admin_product" | "401"
        "PUT"    | "520707" | "admin_product" | "401"
        "POST"   | "520707" | "admin_product" | "401"
        "PATCH"  | "520707" | "admin_product" | "401"
        "DELETE" | "520707" | "admin_product" | "401"
        "GET"    | "520707" | "edit_product"  | "401"
        "HEAD"   | "520707" | "edit_product"  | "401"
        "PUT"    | "520707" | "edit_product"  | "401"
        "POST"   | "520707" | "edit_product"  | "401"
        "PATCH"  | "520707" | "edit_product"  | "401"
        "DELETE" | "520707" | "edit_product"  | "401"
    }

    @Unroll("ContactId missing: #tenantID, permission: #permission for #method and deviceID: #deviceID should return a #responseCode")
    def "Repose return 403 if contact id missing"() {
        given: "A device ID with a particular permission level defined in Valkyrie"

        fakeIdentityService.with {
            client_apikey = UUID.randomUUID().toString()
            client_token = UUID.randomUUID().toString()
            client_tenant = tenantID
            contact_id = ""
        }
        fakeValkyrie.with {
            device_id = deviceID
            device_perm = permission
        }

        when: "a request is made against a device with Valkyrie set permissions"
        MessageChain mc = deproxy.makeRequest(url: reposeEndpoint + "/resource/" + deviceID, method: method,
                headers: [
                        'content-type': 'application/json',
                        'X-Auth-Token': fakeIdentityService.client_token,
                ]
        )

        then: "check response"
        mc.receivedResponse.code == responseCode

        where:
        method   | tenantID       | deviceID | permission      | responseCode
        "GET"    | randomTenant() | "520707" | "view_product"  | "401"
        "HEAD"   | randomTenant() | "520707" | "view_product"  | "401"
        "GET"    | randomTenant() | "520707" | "admin_product" | "401"
        "HEAD"   | randomTenant() | "520707" | "admin_product" | "401"
        "PUT"    | randomTenant() | "520707" | "admin_product" | "401"
        "POST"   | randomTenant() | "520707" | "admin_product" | "401"
        "PATCH"  | randomTenant() | "520707" | "admin_product" | "401"
        "DELETE" | randomTenant() | "520707" | "admin_product" | "401"
        "GET"    | randomTenant() | "520707" | "edit_product"  | "401"
        "HEAD"   | randomTenant() | "520707" | "edit_product"  | "401"
        "PUT"    | randomTenant() | "520707" | "edit_product"  | "401"
        "POST"   | randomTenant() | "520707" | "edit_product"  | "401"
        "PATCH"  | randomTenant() | "520707" | "edit_product"  | "401"
        "DELETE" | randomTenant() | "520707" | "edit_product"  | "401"
    }

    // REP-2670: Ded Auth Changes
    // Currently, without a default tenantID, we do not make the Valkyrie call.
    // We will remove the requirement for a default tenantID so that when we don’t have a default URI,
    // we will rely on a tenantID from the validate token call
    // apply for this case dedicated user
    @Unroll("Dedicated User test permission: #permission for #method with tenant: #tenantID and deviceID: #deviceID should return a #responseCode")
    def "Test with dedicatedUser make sure make call to valkyrie"() {
        given: "A device ID with a particular permission level defined in Valkyrie"

        fakeIdentityService.with {
            client_apikey = UUID.randomUUID().toString()
            client_token = "dedicatedUser"
            client_tenant = tenantID
        }

        fakeValkyrie.with {
            device_id = deviceID
            device_perm = permission
        }

        sleep(2000)

        when: "a request is made against a device with Valkyrie set permissions"
        MessageChain mc = deproxy.makeRequest(url: reposeEndpoint + "/resource/" + deviceID, method: method,
                headers: [
                        'content-type': 'application/json',
                        'X-Auth-Token': fakeIdentityService.client_token,
                ]
        )

        then: "check response"
        mc.receivedResponse.code == responseCode

        where:
        method   | tenantID                   | deviceID | permission     | responseCode
        "GET"    | randomTenant()             | "520707" | "view_product" | "200"
        "HEAD"   | randomTenant()             | "520707" | "view_product" | "200"
        "GET"    | randomTenant() - "hybrid:" | "520707" | "view_product" | "403"
        "PUT"    | randomTenant()             | "520707" | "view_product" | "403"
        "POST"   | randomTenant()             | "520707" | "view_product" | "403"
        "DELETE" | randomTenant()             | "520707" | "view_product" | "403"
        "PATCH"  | randomTenant()             | "520707" | "view_product" | "403"
    }

    def String randomTenant() {
        "hybrid:" + random.nextInt()
    }
}
