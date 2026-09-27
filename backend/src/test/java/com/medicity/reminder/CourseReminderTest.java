package com.medicity.reminder;

import com.medicity.jobs.CourseReminderJob;
import com.medicity.request.NetworkTestSupport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Courses and reminders, on the shared fixture: omeprazole for 14 days (taken
 * long-term, so it earns "running out") and cetirizine for 5 (a course).
 */
@AutoConfigureMockMvc
@DisplayName("Refill and course reminders")
class CourseReminderTest extends NetworkTestSupport {

    @Autowired CourseReminderJob job;

    @Test
    @DisplayName("before the medicines are handed over, nothing has started and nothing is reminded")
    void notStarted() throws Exception {
        mvc.perform(get("/api/v1/patients/me/courses").header("Authorization", bearer(meeraUser)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].status").value("NOT_STARTED"))
                .andExpect(jsonPath("$[1].status").value("NOT_STARTED"));
        assertThat(job.run()).isZero();
    }

    @Test
    @DisplayName("handed over 11 days ago: the 14-day medicine runs out in 2 days, the 5-day course is over")
    void runningOut() throws Exception {
        dispensedDaysAgo(11);

        mvc.perform(get("/api/v1/patients/me/courses").header("Authorization", bearer(meeraUser)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].status").value("RUNNING_OUT"))
                .andExpect(jsonPath("$[0].daysLeft").value(2))
                .andExpect(jsonPath("$[0].startedWhere").value("the hospital pharmacy"))
                .andExpect(jsonPath("$[1].status").value("FINISHED"));

        assertThat(job.run()).isEqualTo(1);
        // The next run, or the one after, sees the same state and says nothing new.
        assertThat(job.run()).isZero();

        relay.drain();
        String title = jdbc.queryForObject("SELECT title FROM notifications WHERE user_id = ? AND kind = 'MEDICINE_RUNNING_OUT'", String.class,
                meeraUser.getId());
        assertThat(title).startsWith("Omeprazole").endsWith("runs out in 2 days");
        assertThat(jdbc.queryForObject("SELECT link FROM notifications WHERE user_id = ? AND kind = 'MEDICINE_RUNNING_OUT'", String.class,
                meeraUser.getId())).isEqualTo("/portal/prescriptions?ask=" + prescription());
    }

    @Test
    @DisplayName("a short course gets 'finish it' the day before its last dose, never 'buy more'")
    void courseEnding() throws Exception {
        dispensedDaysAgo(3);

        assertThat(job.run()).isEqualTo(1);
        relay.drain();

        assertThat(jdbc.queryForObject("SELECT kind FROM notifications WHERE user_id = ? AND kind LIKE 'COURSE%'", String.class,
                meeraUser.getId())).isEqualTo("COURSE_ENDING");
        assertThat(jdbc.queryForObject("SELECT body FROM notifications WHERE user_id = ? AND kind = 'COURSE_ENDING'", String.class,
                meeraUser.getId())).isEqualTo("Finish the course, even if you feel better.");
    }

    @Test
    @DisplayName("another patient sees only their own medicines")
    void ownCoursesOnly() throws Exception {
        dispensedDaysAgo(11);
        mvc.perform(get("/api/v1/patients/me/courses").header("Authorization", bearer(otherUser)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
    }

    private void dispensedDaysAgo(int days) {
        jdbc.update("""
                INSERT INTO prescription_dispensations (prescription_id, dispensed_at)
                VALUES (?, now() - make_interval(days => ?))
                """, prescription(), days);
    }

    private UUID prescription() {
        return prescriptionId;
    }
}
