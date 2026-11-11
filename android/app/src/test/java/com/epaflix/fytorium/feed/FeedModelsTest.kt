package com.epaflix.fytorium.feed

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class FeedModelsTest {
    private fun warnings(id: String, plant: String, vararg warnings: JSONObject) = parseFeedCard(JSONObject().put("id", id).put("kind", "plant_warnings")
        .put("href", "/plants/$id").put("plantName", plant).put("warnings", JSONArray(warnings.toList())))!!
    private fun gap(vararg inputs: String) = JSONObject().put("kind", "missing_info").put("reason", "Missing information.")
        .put("inputs", JSONArray(inputs.map { JSONObject().put("input", it).put("value", "not set").put("source", "setting").put("ruleId", "warning.missing_info@1") }))
    private fun missing(vararg inputs: String) = listOf(warnings("fern", "Synthetic fern", gap(*inputs))).warningCards().single().title
    private fun cold(minC: Double, startsOn: String = "2030-06-01", potRuleId: String? = "hardiness.pot_offset@1") = parseFeedCard(JSONObject(
        """{"id":"cold","kind":"weather_alerts","href":"/plants/fern","event":{"kind":"low_temperature","plant_name":"Synthetic fern",
        "starts_on":"$startsOn","ends_on":"2030-06-07","payload":{"minC":$minC,"thresholdC":6,
        "hardiness":{"source":"override","value":6,"ruleId":null},"potRuleId":${potRuleId?.let { "\"$it\"" } ?: "null"}}}}"""))!!
    private fun weather(unavailableSince: String?) = parseFeedCard(JSONObject().put("id", "current").put("kind", "current_weather")
        .put("href", "/settings").put("unavailableSince", unavailableSince ?: JSONObject.NULL).put("current", JSONObject.NULL))!!

    @Test fun missingInformationNamesEachGapInPlainWords() {
        assertEquals("Synthetic fern: missing pot size", missing("pot.pot_size_cm"))
        assertEquals("Synthetic fern: missing pot type and pot size", missing("pot.pot_type", "pot.pot_size_cm"))
        assertEquals("Synthetic fern: missing site light, pot type, species care data and weather location",
            missing("site.light", "pot.pot_type", "careProfile", "household.location"))
        assertEquals("Synthetic fern: missing information", missing("synthetic.future_input"))
        assertEquals("Synthetic fern: missing information", missing())
    }

    @Test fun warningsOfOneKindFormOneCardAndASinglePlantLeadsWithItsName() {
        val dark = JSONObject().put("kind", "too_dark").put("reason", "Too dark.").put("inputs", JSONArray())
        val cards = listOf(warnings("peony", "Chinese peony", gap("pot.pot_size_cm", "careProfile")),
            warnings("jade", "Jade Plant", dark, gap("pot.pot_size_cm")), warnings("thyme", "Thyme", gap("pot.pot_size_cm", "household.location")))
        val shown = cards.warningCards()
        assertEquals(listOf("Missing pot size for 3 plants", "Chinese peony: missing species care data", "Jade Plant: too dark", "Thyme: missing weather location"),
            shown.map { it.title })
        assertEquals(listOf("Chinese peony" to "/plants/peony", "Jade Plant" to "/plants/jade", "Thyme" to "/plants/thyme"), shown.first().plants)
        assertEquals(listOf("Too dark for 2 plants"), listOf(warnings("jade", "Jade Plant", dark), warnings("fern", "Synthetic fern", dark)).warningCards().map { it.title })
    }

    @Test fun coldAlertsSayFrostAtOrBelowZeroAndColdAbove() {
        assertEquals("Synthetic fern: cold today, bring inside", cold(0.5).headline("2030-06-01"))
        assertEquals("Synthetic fern: frost today, bring inside", cold(0.0).headline("2030-06-01"))
        assertEquals("Synthetic fern: frost today, bring inside", cold(-3.0).headline("2030-06-01"))
    }

    @Test fun coldAlertsUseTodayTomorrowOrTheirFirstDay() {
        assertEquals("Synthetic fern: frost today, bring inside", cold(0.0).headline("2030-06-02"))
        assertEquals("Synthetic fern: frost tomorrow, bring inside", cold(0.0).headline("2030-05-31"))
        assertEquals("Synthetic fern: frost from Sat 1 Jun, bring inside", cold(0.0).headline("2030-05-30"))
        assertEquals("Synthetic fern: frost from Sat 1 Jun, bring inside", cold(0.0).headline(null))
    }

    @Test fun anInGroundPlantIsProtectedNotBroughtInside() {
        assertEquals("Synthetic fern: cold today, protect it", cold(2.0, potRuleId = null).headline("2030-06-01"))
    }

    @Test fun rainUsesTodayYesterdayOrItsDay() {
        val rain = parseFeedCard(JSONObject(feedFixture).getJSONArray("cards").getJSONObject(3))!!
        assertEquals("Rain today, 6 mm", rain.headline("2030-06-01"))
        assertEquals("Rain yesterday, 6 mm", rain.headline("2030-06-02"))
        assertEquals("Rain on Sat 1 Jun, 6 mm", rain.headline("2030-06-05"))
        assertEquals("Rain today", rain.copy(text = "").headline("2030-06-01"))
    }

    @Test fun weatherWithoutCurrentDataSaysWhy() {
        assertEquals("Weather not loaded yet", weather(null).headline("2030-06-01"))
        assertEquals("Weather unavailable", weather("2030-06-01T08:00:00Z").headline("2030-06-01"))
        val since = java.time.format.DateTimeFormatter.ofPattern("d MMM HH:mm").withZone(java.time.ZoneId.systemDefault()).format(java.time.Instant.parse("2030-06-01T08:00:00.000Z"))
        assertEquals(listOf("Weather unavailable since $since. Showing last good data where available."), weather("2030-06-01T08:00:00.000Z").why)
    }

    @Test fun anUnreadableTimeIsLeftOutInsteadOfShownRaw() {
        assertEquals(listOf("Weather unavailable. Showing last good data where available."), weather("Monday morning").why)
        val current = parseFeedCard(JSONObject().put("id", "current").put("kind", "current_weather").put("href", "/settings").put("unavailableSince", JSONObject.NULL)
            .put("current", JSONObject().put("minC", 1).put("maxC", 9).put("fetchedAt", "Monday morning")))!!
        assertEquals(listOf("Today: low 1 °C, high 9 °C."), current.why)
    }

    @Test fun whyUsesPlainWordsAndReadableDates() {
        val why = cold(0.0).why
        assertEquals("Low temperature 0 °C, below 6 °C hardiness, Sat 1 Jun to Fri 7 Jun. Bring it in or protect it.", why.first())
        for (line in why) for (raw in listOf("@1", "2030-", "override", "source")) assertFalse("$line shows $raw", raw in line)
    }
}
