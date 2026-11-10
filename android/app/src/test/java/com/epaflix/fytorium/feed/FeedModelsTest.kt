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
    private fun missing(vararg inputs: String) = parseFeedCard(JSONObject().put("id", "warnings").put("kind", "plant_warnings")
        .put("href", "/plants/fern").put("plantName", "Synthetic fern").put("warnings", JSONArray().put(JSONObject()
            .put("kind", "missing_info").put("reason", "Missing information.")
            .put("inputs", JSONArray(inputs.map { JSONObject().put("input", it).put("value", "not set").put("source", "setting") })))))!!
        .warnings.single().text
    private fun cold(minC: Double, startsOn: String = "2030-06-01", potRuleId: String? = "hardiness.pot_offset@1") = parseFeedCard(JSONObject(
        """{"id":"cold","kind":"weather_alerts","href":"/plants/fern","event":{"kind":"low_temperature","plant_name":"Synthetic fern",
        "starts_on":"$startsOn","ends_on":"2030-06-07","payload":{"minC":$minC,"thresholdC":6,
        "hardiness":{"source":"override","value":6,"ruleId":null},"potRuleId":${potRuleId?.let { "\"$it\"" } ?: "null"}}}}"""))!!
    private fun weather(unavailableSince: String?) = parseFeedCard(JSONObject().put("id", "current").put("kind", "current_weather")
        .put("href", "/settings").put("unavailableSince", unavailableSince ?: JSONObject.NULL).put("current", JSONObject.NULL))!!

    @Test fun missingInformationNamesEachGapInPlainWords() {
        assertEquals("Missing pot size for Synthetic fern", missing("pot.pot_size_cm"))
        assertEquals("Missing pot type and pot size for Synthetic fern", missing("pot.pot_type", "pot.pot_size_cm"))
        assertEquals("Missing site light, pot type, species care data and weather location for Synthetic fern",
            missing("site.light", "pot.pot_type", "careProfile", "household.location"))
        assertEquals("Missing information for Synthetic fern", missing("synthetic.future_input"))
        assertEquals("Missing information for Synthetic fern", missing())
    }

    @Test fun coldAlertsSayFrostAtOrBelowZeroAndColdAbove() {
        assertEquals("Cold today: bring Synthetic fern inside", cold(0.5).headline("2030-06-01"))
        assertEquals("Frost today: bring Synthetic fern inside", cold(0.0).headline("2030-06-01"))
        assertEquals("Frost today: bring Synthetic fern inside", cold(-3.0).headline("2030-06-01"))
    }

    @Test fun coldAlertsUseTodayTomorrowOrTheirFirstDay() {
        assertEquals("Frost today: bring Synthetic fern inside", cold(0.0).headline("2030-06-02"))
        assertEquals("Frost tomorrow: bring Synthetic fern inside", cold(0.0).headline("2030-05-31"))
        assertEquals("Frost from Sat 1 Jun: bring Synthetic fern inside", cold(0.0).headline("2030-05-30"))
        assertEquals("Frost from Sat 1 Jun: bring Synthetic fern inside", cold(0.0).headline(null))
    }

    @Test fun anInGroundPlantIsProtectedNotBroughtInside() {
        assertEquals("Cold today: protect Synthetic fern", cold(2.0, potRuleId = null).headline("2030-06-01"))
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
        assertEquals(listOf("Weather unavailable since 2030-06-01T08:00:00Z. Showing last good data where available."), weather("2030-06-01T08:00:00Z").why)
    }
}
