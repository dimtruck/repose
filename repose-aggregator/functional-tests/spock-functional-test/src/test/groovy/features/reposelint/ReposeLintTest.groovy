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
package features.reposelint

import framework.ReposeConfigurationProvider
import framework.ReposeLintLauncher
import framework.ReposeLogSearch
import framework.TestProperties
import groovy.json.JsonSlurper
import spock.lang.Shared
import spock.lang.Specification
import spock.lang.Unroll

import static org.junit.Assert.assertTrue

class ReposeLintTest extends Specification {
    @Shared
    ReposeLintLauncher reposeLintLauncher
    @Shared
    TestProperties testProperties
    @Shared
    ReposeLogSearch reposeLogSearch
    @Shared
    ReposeConfigurationProvider reposeConfigurationProvider

    def
    static String allowedWithoutAuthorizationDesc = "Users with the 'foyer' Identity role WILL pass through this component BUT authorization checks will not be performed"
    def
    static String allowedWithAuthorizationDesc = "Users with the 'foyer' Identity role WILL pass through this component IF AND ONLY IF their Identity service catalog contains an endpoint required by the authorization component"
    def static String allowedDesc = "Users with the 'foyer' Identity role WILL pass through this component"
    def static String notAllowedDesc = "Users with the 'foyer' Identity role WILL NOT pass through this component"

    def setupSpec() {
        this.testProperties = new TestProperties()

        this.reposeConfigurationProvider = new ReposeConfigurationProvider(testProperties)

        this.reposeLintLauncher = new ReposeLintLauncher(reposeConfigurationProvider, testProperties)
        this.reposeLintLauncher.enableDebug()

        this.reposeLogSearch = new ReposeLogSearch(testProperties.getReposeLintLogFile())
    }

    def setup() {
        reposeLogSearch.cleanLog()
    }

    def cleanup() {
        reposeLintLauncher.stop()
    }

    def "Test missing config"() {
        given:
        def params = testProperties.getDefaultTemplateParams()
        reposeConfigurationProvider.cleanConfigDirectory()
        reposeConfigurationProvider.applyConfigs("features/reposelint/missingconfig", params)

        when:
        reposeLintLauncher.start("verify-try-it-now")
        def debugport = reposeLintLauncher.debugPort
        def log = reposeLogSearch.logToString() - ("Listening for transport dt_socket at address: " + debugport)
        println log
        def slurper = new JsonSlurper()
        def jsonlog = slurper.parseText(log)

        then:
        reposeLogSearch.searchByString(debugport.toString())
        //top level status
        jsonlog["foyerStatus"] == "NotAllowed"
        jsonlog["foyerStatusDescription"] == notAllowedDesc

        jsonlog.clusters.clusterId.get(0) == "repose"
        jsonlog.clusters["authNCheck"][0]["filterName"] == "client-auth"
        jsonlog.clusters["authNCheck"][0]["filters"].size() != 0
        jsonlog.clusters["authNCheck"][0]["filters"][0]["missingConfiguration"] == true
        jsonlog.clusters["authNCheck"][0]["filters"][0]["foyerStatus"] == "NotAllowed"
        jsonlog.clusters["authNCheck"][0]["filters"][0]["foyerStatusDescription"] == notAllowedDesc
    }

    @Unroll("test with config: #configdir & #status")
    def "test individual components"() {
        given:
        def params = testProperties.getDefaultTemplateParams()
        reposeConfigurationProvider.cleanConfigDirectory()
        reposeConfigurationProvider.applyConfigs(configdir, params)
        def foyerAsIgnoreTenant
        if (checktype == "keystoneV2Check") {
            foyerAsIgnoreTenant = "foyerAsPreAuthorized"
        } else if (checktype == "keystoneV3Check") {
            foyerAsIgnoreTenant = "foyerAsBypassTenant"
        } else {
            foyerAsIgnoreTenant = "foyerAsIgnoreTenant"
        }

        when:
        reposeLintLauncher.start("verify-try-it-now")
        def debugport = reposeLintLauncher.debugPort
        def log = reposeLogSearch.logToString() - ("Listening for transport dt_socket at address: " + debugport)
        println log
        def slurper = new JsonSlurper()
        def jsonlog = slurper.parseText(log)

        then:
        reposeLogSearch.searchByString(debugport.toString())
        //top level status
        jsonlog["foyerStatus"] == status
        jsonlog["foyerStatusDescription"] == desc
        jsonlog.clusters.clusterId.get(0) == "repose"
        jsonlog.clusters[checktype][0]["filterName"] == filtername
        jsonlog.clusters[checktype][0]["filters"].size() != 0
        jsonlog.clusters[checktype][0]["filters"][0]["missingConfiguration"] == false
        jsonlog.clusters[checktype][0]["filters"][0][foyerAsIgnoreTenant] == foyerignore
        jsonlog.clusters[checktype][0]["filters"][0]["foyerStatus"] == status
        jsonlog.clusters[checktype][0]["filters"][0]["foyerStatusDescription"] == desc
        if (checktenantedmode == "yes") {
            assertTrue(jsonlog.clusters[checktype][0]["filters"][0]["inTenantedMode"] == tenantmode)
        }

        where:
        configdir                                            | checktype         | filtername             | checktenantedmode | tenantmode | foyerignore | status                        | desc
        "features/reposelint/clientauthn"                    | "authNCheck"      | "client-auth"          | "yes"             | false      | false       | "Allowed"                     | allowedDesc
        "features/reposelint/clientauthn/tenanted"           | "authNCheck"      | "client-auth"          | "yes"             | true       | false       | "NotAllowed"                  | notAllowedDesc
        "features/reposelint/clientauthn/tenantedwfoyerrole" | "authNCheck"      | "client-auth"          | "yes"             | true       | true        | "Allowed"                     | allowedDesc
        "features/reposelint/clientauthz"                    | "authZCheck"      | "client-authorization" | "no"              | false      | false       | "AllowedWithAuthorization"    | allowedWithAuthorizationDesc
        "features/reposelint/clientauthz/wfoyerrole"         | "authZCheck"      | "client-authorization" | "no"              | false      | true        | "AllowedWithoutAuthorization" | allowedWithoutAuthorizationDesc
        "features/reposelint/keystonev2"                     | "keystoneV2Check" | "keystone-v2"          | "yes"             | false      | false       | "AllowedWithoutAuthorization" | allowedWithoutAuthorizationDesc
        "features/reposelint/keystonev2/tenanted"            | "keystoneV2Check" | "keystone-v2"          | "yes"             | true       | false       | "NotAllowed"                  | notAllowedDesc
        "features/reposelint/keystonev2/tenanted/wfoyerrole" | "keystoneV2Check" | "keystone-v2"          | "yes"             | true       | true        | "AllowedWithoutAuthorization" | allowedWithoutAuthorizationDesc
        "features/reposelint/keystonev2/authz"               | "keystoneV2Check" | "keystone-v2"          | "no"              | false      | false       | "AllowedWithAuthorization"    | allowedWithAuthorizationDesc
        "features/reposelint/keystonev2/authzwfoyerrole"     | "keystoneV2Check" | "keystone-v2"          | "no"              | false      | true        | "AllowedWithoutAuthorization" | allowedWithoutAuthorizationDesc
    }

    @Unroll("test with multi config: #configdir")
    def "test with multi config"() {
        given: "config with authn and authz"
        def checktypes = ["authNCheck", "authZCheck"]
        def filternames = ["client-auth", "client-authorization"]
        def params = testProperties.getDefaultTemplateParams()
        reposeConfigurationProvider.cleanConfigDirectory()
        reposeConfigurationProvider.applyConfigs(configdir, params)
        def foyerAsIgnoreTenant
        checktypes.each { e ->
            if (e == "keystoneV2Check") {
                foyerAsIgnoreTenant = "foyerAsPreAuthorized"
            } else if (e == "keystoneV3Check") {
                foyerAsIgnoreTenant = "foyerAsBypassTenant"
            } else {
                foyerAsIgnoreTenant = "foyerAsIgnoreTenant"
            }
        }

        when:
        reposeLintLauncher.start("verify-try-it-now")
        def debugport = reposeLintLauncher.debugPort
        def log = reposeLogSearch.logToString() - ("Listening for transport dt_socket at address: " + debugport)
        println log
        def slurper = new JsonSlurper()
        def jsonlog = slurper.parseText(log)

        then:
        reposeLogSearch.searchByString(debugport.toString())
        jsonlog.clusters.clusterId.get(0) == "repose"
        jsonlog.clusters[checktypes[0]][0]["filterName"] == filternames[0]
        jsonlog.clusters[checktypes[0]][0]["filters"].size() != 0
        jsonlog.clusters[checktypes[0]][0]["filters"][0]["missingConfiguration"] == false
        jsonlog.clusters[checktypes[0]][0]["filters"][0][foyerAsIgnoreTenant] == foyerignore[0]
        jsonlog.clusters[checktypes[0]][0]["filters"][0]["foyerStatus"] == status[0]
        if (checktenantedmode == "yes") {
            assertTrue(jsonlog.clusters[checktypes[0]][0]["filters"][0]["inTenantedMode"] == tenantmode)
        }

        jsonlog.clusters[checktypes[1]][0]["filterName"] == filternames[1]
        jsonlog.clusters[checktypes[1]][0]["filters"].size() != 0
        jsonlog.clusters[checktypes[1]][0]["filters"][0]["missingConfiguration"] == false
        jsonlog.clusters[checktypes[1]][0]["filters"][0][foyerAsIgnoreTenant] == foyerignore[1]
        jsonlog.clusters[checktypes[1]][0]["filters"][0]["foyerStatus"] == status[1]

        where:
        configdir                                                   | checktenantedmode | tenantmode | foyerignore    | status
        "features/reposelint/authnandauthz"                         | "yes"             | false      | [false, false] | ["Allowed", "AllowedWithAuthorization"]
        "features/reposelint/authnandauthz/authnwfoyerrole"         | "yes"             | true       | [true, false]  | ["Allowed", "AllowedWithAuthorization"]
        "features/reposelint/authnandauthz/bothwfoyerrole"          | "yes"             | true       | [true, true]   | ["Allowed", "AllowedWithoutAuthorization"]
        "features/reposelint/authnandauthz/tenantedauthzwfoyerrole" | "yes"             | true       | [false, true]  | ["NotAllowed", "AllowedWithoutAuthorization"]
        "features/reposelint/authnandauthz/authzwfoyerrole"         | "yes"             | false      | [false, true]  | ["Allowed", "AllowedWithoutAuthorization"]
    }
}