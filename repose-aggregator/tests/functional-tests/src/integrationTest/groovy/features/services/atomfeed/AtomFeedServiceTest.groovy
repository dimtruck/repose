package features.services.atomfeed

import features.filters.keystonev2.AtomFeedResponseSimulator
import framework.ReposeValveTest
import framework.category.Slow
import framework.mocks.MockIdentityV2Service
import org.junit.experimental.categories.Category
import org.rackspace.deproxy.Deproxy
import org.rackspace.deproxy.Endpoint

import java.util.concurrent.TimeUnit

@Category(Slow.class)
class AtomFeedServiceTest extends ReposeValveTest {
    Endpoint originEndpoint
    Endpoint atomEndpoint
    MockIdentityV2Service fakeIdentityV2Service
    AtomFeedResponseSimulator fakeAtomFeed

    def setup() {
        deproxy = new Deproxy()
        reposeLogSearch.cleanLog()

        int atomPort = properties.atomPort
        fakeAtomFeed = new AtomFeedResponseSimulator(atomPort)
        atomEndpoint = deproxy.addEndpoint(atomPort, 'atom service', null, fakeAtomFeed.handler)

        Map params = properties.defaultTemplateParams
        repose.configurationProvider.applyConfigs("common", params)
        repose.configurationProvider.applyConfigs("features/filters/keystonev2/common", params)
        repose.configurationProvider.applyConfigs("features/filters/keystonev2/atom", params)
        repose.start()

        originEndpoint = deproxy.addEndpoint(properties.targetPort, 'origin service')

        fakeIdentityV2Service = new MockIdentityV2Service(properties.identityPort, properties.targetPort)
        deproxy.addEndpoint(properties.identityPort, 'identity service', null, fakeIdentityV2Service.handler)
    }

    def cleanup() {
        deproxy?.shutdown()
        repose?.stop()
    }

    def "when an atom feed entry is received, it is passed to the filter"() {
        given: "there is an atom feed entry available for consumption"
        String atomFeedEntry = fakeAtomFeed.createAtomEntry(id: 'urn:uuid:101')
        fakeAtomFeed.atomEntries << atomFeedEntry
        fakeAtomFeed.hasEntry = true

        when: "we wait for the Keystone V2 filter to read the feed"
        reposeLogSearch.awaitByString("</atom:entry>", 1, 11, TimeUnit.SECONDS)

        then: "the Keystone V2 filter logs receiving the atom feed entry"
        atomFeedEntry.eachLine { line ->
            assert reposeLogSearch.searchByString(line.trim()).size() == 1
            true
        }
    }

    def "when multiple atom feed entries are received, they are passed in-order to the filter"() {
        given: "there is a list of atom feed entries available for consumption"
        List<String> ids = (201..210).collect {it as String}
        fakeAtomFeed.atomEntries.addAll(ids.collect { fakeAtomFeed.createAtomEntry(id: "urn:uuid:$it") })
        fakeAtomFeed.hasEntry = true

        when: "we wait for the Keystone V2 filter to read the feed"
        reposeLogSearch.awaitByString("</atom:entry>", ids.size(), 11, TimeUnit.SECONDS)

        then: "the Keystone V2 filter logs receiving the atom feed entries in order"
        def logLines = reposeLogSearch.searchByString("<atom:id>.*</atom:id>")
        logLines.size() == ids.size()
        logLines.collect { (it =~ /\s*<atom:id>urn:uuid:(\d+)<\/atom:id>.*/)[0][1] } == ids
    }

    def "when multiple pages of atom feed entries are received, they are all processed by the filter"() {
        given: "there is a list of atom feed entries available for consumption"
        List<String> ids = (301..325).collect {it as String}
        fakeAtomFeed.atomEntries.addAll(ids.collect { fakeAtomFeed.createAtomEntry(id: "urn:uuid:$it") })
        fakeAtomFeed.hasEntry = true

        when: "we wait for the Keystone V2 filter to read the feed"
        reposeLogSearch.awaitByString("</atom:entry>", ids.size(), 11, TimeUnit.SECONDS)

        then: "the Keystone V2 filter logs receiving the atom feed entries in order"
        def logLines = reposeLogSearch.searchByString("<atom:id>.*</atom:id>")
        logLines.size() == ids.size()
        logLines.collect { (it =~ /\s*<atom:id>urn:uuid:(\d+)<\/atom:id>.*/)[0][1] } == ids

        when: "there are more entries on the next page"
        def moreIds = (401..425).collect {it as String}
        fakeAtomFeed.atomEntries.addAll(moreIds.collect { fakeAtomFeed.createAtomEntry(id: "urn:uuid:$it") })
        fakeAtomFeed.hasEntry = true

        and: "we wait for the Keystone V2 filter to read the feed"
        reposeLogSearch.awaitByString("</atom:entry>", ids.size() + moreIds.size(), 11, TimeUnit.SECONDS)

        then: "the Keystone V2 filter logs receiving the atom feed entries in order"
        def moreLogLines = reposeLogSearch.searchByString("<atom:id>.*4\\d{2}</atom:id>")
        moreLogLines.size() == moreIds.size()
        moreLogLines.collect { (it =~ /\s*<atom:id>urn:uuid:(\d+)<\/atom:id>.*/)[0][1] } == moreIds
    }
}
