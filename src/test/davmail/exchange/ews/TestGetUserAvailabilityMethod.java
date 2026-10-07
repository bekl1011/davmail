package davmail.exchange.ews;

import junit.framework.TestCase;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

public class TestGetUserAvailabilityMethod extends TestCase {
    private static final class TestMethod extends GetUserAvailabilityMethod {
        private TestMethod(boolean detailed) {
            super("user@example.org", "2026-10-01T00:00:00Z",
                    "2026-10-08T00:00:00Z", 15, detailed);
        }

        private void parse(InputStream inputStream) {
            processResponseStream(inputStream);
        }

        private String getRequestBody() {
            return new String(generateSoapEnvelope(), StandardCharsets.UTF_8);
        }
    }

    private List<GetUserAvailabilityMethod.CalendarEvent> parseFixture() {
        TestMethod method = new TestMethod(true);
        InputStream inputStream = getClass().getResourceAsStream("availability-detailed.xml");
        assertNotNull(inputStream);
        method.parse(inputStream);
        return method.getCalendarEvents();
    }

    public void testDetailedRequestView() {
        assertTrue(new TestMethod(true).getRequestBody().contains(
                "<t:RequestedView>DetailedMerged</t:RequestedView>"));
        assertTrue(new TestMethod(false).getRequestBody().contains(
                "<t:RequestedView>MergedOnly</t:RequestedView>"));
    }

    public void testParseSubjectAndLocation() {
        GetUserAvailabilityMethod.CalendarEvent event = parseFixture().get(0);
        assertEquals("2026-10-01T08:00:00", event.getStart());
        assertEquals("2026-10-01T09:00:00", event.getEnd());
        assertEquals("Busy", event.getBusyType());
        assertEquals("Plan, review; path\\share\nÜberblick", event.getSubject());
        assertEquals("Room, 1; West\\Wing", event.getLocation());
        assertEquals("AAMkAGeneral", event.getId());
        assertEquals(Boolean.TRUE, event.getMeeting());
    }

    public void testParseEventWithoutDetails() {
        GetUserAvailabilityMethod.CalendarEvent event = parseFixture().get(1);
        assertNull(event.getSubject());
        assertNull(event.getLocation());
        assertNull(event.getId());
        assertNull(event.getPrivate());
    }

    public void testParsePrivateEvent() {
        GetUserAvailabilityMethod.CalendarEvent event = parseFixture().get(2);
        assertEquals(Boolean.TRUE, event.getPrivate());
        assertNull(event.getSubject());
        assertNull(event.getLocation());
    }

    public void testParseRecurringOccurrence() {
        GetUserAvailabilityMethod.CalendarEvent event = parseFixture().get(3);
        assertEquals("AAMkARecurring", event.getId());
        assertEquals(Boolean.TRUE, event.getRecurring());
        assertEquals(Boolean.FALSE, event.getException());
    }
}
