package app.hermesconduit.android

import app.hermesconduit.android.model.ConduitJson
import app.hermesconduit.android.model.CronJob
import app.hermesconduit.android.model.CronJobFilter
import app.hermesconduit.android.model.CronParser
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CronParserTest {
    private fun obj(json: String) = ConduitJson.parseToJsonElement(json) as JsonObject

    @Test
    fun jobsParseFromArrayAndSortByName() {
        val jobs = CronParser.jobs(
            obj(
                """{"_array":[
                  {"id":"b","name":"zeta","enabled":true,"schedule":{"display":"every day","expr":"0 9 * * *","kind":"cron"},"next_run_at":"2026-01-01T09:00:00Z"},
                  {"id":"a","name":"Alpha","enabled":false,"schedule_display":"hourly","deliver":"telegram","last_error":"boom"},
                  {"name":"no id"}
                ]}""",
            ),
        )
        assertEquals(listOf("a", "b"), jobs.map { it.id })
        assertEquals("hourly", jobs[0].scheduleText)
        assertEquals("every day", jobs[1].scheduleDisplay)
        assertEquals("0 9 * * *", jobs[1].scheduleExpr)
        assertEquals("telegram", jobs[0].deliver)
        assertEquals("boom", jobs[0].lastError)
    }

    @Test
    fun jobsAlsoReadTheJobsKeyAndStringSchedules() {
        val jobs = CronParser.jobs(obj("""{"jobs":[{"id":"x","enabled":true,"schedule":"*/5 * * * *"}]}"""))
        assertEquals("*/5 * * * *", jobs.single().scheduleText)
        assertEquals("x", jobs.single().displayName)
    }

    @Test
    fun finishedOneShotIsNotActive() {
        val job = CronJob(id = "1", enabled = true, state = "completed")
        assertTrue(job.isFinished)
        assertFalse(job.isActive)
        assertFalse(CronJobFilter.ACTIVE.includes(job))
        assertTrue(CronJobFilter.INACTIVE.includes(job))
    }

    @Test
    fun visibleJobsPutActiveFirstAndFilterBySearch() {
        val jobs = listOf(
            CronJob(id = "1", enabled = false, name = "Backup"),
            CronJob(id = "2", enabled = true, name = "Digest", prompt = "Summarise news"),
            CronJob(id = "3", enabled = true, name = "alarm"),
        )
        assertEquals(listOf("3", "2", "1"), CronParser.visibleJobs(jobs, CronJobFilter.ALL, "").map { it.id })
        assertEquals(listOf("2"), CronParser.visibleJobs(jobs, CronJobFilter.ALL, " news ").map { it.id })
        assertEquals(listOf("1"), CronParser.visibleJobs(jobs, CronJobFilter.INACTIVE, "").map { it.id })
        assertEquals(2, CronParser.shownJobs(jobs.take(2), showAll = false, isSearching = false).size)
        assertEquals(3, CronParser.shownJobs(jobs + jobs, showAll = false, isSearching = false).size)
        assertEquals(6, CronParser.shownJobs(jobs + jobs, showAll = false, isSearching = true).size)
    }

    @Test
    fun emptyStatesMatchIos() {
        assertEquals("No Matching Jobs" to "Try a different search.", CronParser.emptyState(CronJobFilter.ALL, true, true))
        assertEquals("No Active Jobs", CronParser.emptyState(CronJobFilter.ACTIVE, true, false).first)
        assertEquals("No Scheduled Jobs", CronParser.emptyState(CronJobFilter.ACTIVE, false, false).first)
    }

    @Test
    fun runsReadNestedShapesAndFilterByProfile() {
        val rows = CronParser.runValues(
            obj(
                """{"data":{"runs":[
                  {"session":{"session_id":"s1","title":"Run 1","last_active":1700000000,"profile":"Work"}},
                  {"id":"s2","summary":"Other","profile":"home"},
                  {"run_id":"s3","name":"Unowned"}
                ]}}""",
            ),
        )
        val runs = CronParser.runs(rows, "work")
        assertEquals(listOf("s1", "s3"), runs.map { it.id })
        assertEquals("Run 1", runs[0].title)
        assertEquals(1_700_000_000_000, runs[0].lastActive)
        assertEquals("Unowned", runs[1].title)
    }

    @Test
    fun mutedJobsNeedOk() {
        assertEquals(setOf("a", "b"), CronParser.mutedJobs(obj("""{"ok":true,"muted":["a","b"]}""")))
        assertNull(CronParser.mutedJobs(obj("""{"ok":false,"muted":[]}""")))
        assertNull(CronParser.mutedJobs(obj("""{"ok":true}""")))
    }
}
