package davmail.caldav;

import davmail.Settings;
import davmail.exchange.VCalendar;
import davmail.exchange.ews.GetUserAvailabilityMethod;
import davmail.http.URIUtil;
import junit.framework.TestCase;

import java.io.InputStream;
import java.util.Date;
import java.util.HashMap;
import java.util.List;

public class TestAvailabilityCalendar extends TestCase {
    private static final class TestMethod extends GetUserAvailabilityMethod {
        private TestMethod() {
            super("user@example.org", "2026-10-01T00:00:00Z",
                    "2026-10-08T00:00:00Z", 15, true);
        }

        private void parse(InputStream inputStream) {
            processResponseStream(inputStream);
        }
    }

    private List<GetUserAvailabilityMethod.CalendarEvent> parseFixture() {
        TestMethod method = new TestMethod();
        InputStream inputStream = getClass().getResourceAsStream(
                "/davmail/exchange/ews/availability-detailed.xml");
        assertNotNull(inputStream);
        method.parse(inputStream);
        return method.getCalendarEvents();
    }

    private String buildCalendar() throws Exception {
        return AvailabilityCalendar.build("user@example.org", parseFixture(), new Date(0));
    }

    public void testStableUid() {
        GetUserAvailabilityMethod.CalendarEvent event = parseFixture().get(3);
        assertEquals(AvailabilityCalendar.buildUid("user@example.org", event),
                AvailabilityCalendar.buildUid("user@example.org", event));
    }

    public void testIcalendarEscapingAndUtf8() throws Exception {
        String calendar = buildCalendar();
        assertTrue(calendar.contains("SUMMARY:Plan\\, review\\; path\\\\share\\nÜberblick\r\n"));
        assertTrue(calendar.contains("LOCATION:Room\\, 1\\; West\\\\Wing\r\n"));
    }

    public void testValidCalendarAndMapping() throws Exception {
        String calendar = buildCalendar();
        VCalendar parsed = new VCalendar(calendar, "user@example.org", null);
        assertEquals("VCALENDAR", parsed.type);
        assertTrue(calendar.startsWith("BEGIN:VCALENDAR\r\nVERSION:2.0\r\n"));
        assertTrue(calendar.contains("PRODID:-//DavMail//EWS Availability Calendar//EN\r\n"));
        assertTrue(calendar.contains("CALSCALE:GREGORIAN\r\nMETHOD:PUBLISH\r\n"));
        assertTrue(calendar.contains("DTSTART:20261001T080000Z\r\n"));
        assertTrue(calendar.contains("DTEND:20261001T090000Z\r\n"));
        assertTrue(calendar.contains("TRANSP:TRANSPARENT\r\n"));
        assertTrue(calendar.contains("CLASS:PRIVATE\r\n"));
        assertEquals(4, count(calendar, "BEGIN:VEVENT\r\n"));
    }

    public void testFeatureDisabledByDefault() {
        Settings.setDefaultSettings();
        assertFalse(Settings.getBooleanProperty("davmail.enableAvailabilityCalendar"));
    }

    public void testAvailabilityMailboxPath() throws Exception {
        CaldavConnection.CaldavRequest request = new CaldavConnection.CaldavRequest(
                "GET", URIUtil.decode("/users/user%40example.org/calendar-availability.ics"),
                new HashMap<String, String>(), null);
        assertTrue(request.isAvailabilityCalendar());
        assertEquals("user@example.org", request.getAvailabilityMailbox());
    }

    private static int count(String value, String token) {
        int result = 0;
        int offset = 0;
        while ((offset = value.indexOf(token, offset)) >= 0) {
            result++;
            offset += token.length();
        }
        return result;
    }
}
