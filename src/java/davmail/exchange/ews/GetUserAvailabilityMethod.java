/*
 * DavMail POP/IMAP/SMTP/CalDav/LDAP Exchange Gateway
 * Copyright (C) 2010  Mickael Guessant
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU General Public License
 * as published by the Free Software Foundation; either version 2
 * of the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program; if not, write to the Free Software
 * Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston, MA  02110-1301, USA.
 */
package davmail.exchange.ews;

import davmail.exchange.XMLStreamUtil;
import davmail.util.StringUtil;

import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;
import java.io.IOException;
import java.io.Writer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * GetUserAvailability method.
 */
public class GetUserAvailabilityMethod extends EWSMethod {
    protected final String attendee;
    protected final String start;
    protected final String end;
    protected String mergedFreeBusy;
    protected final int interval;
    protected final boolean detailed;
    protected final List<CalendarEvent> calendarEvents = new ArrayList<>();

    /**
     * Build EWS method
     *
     * @param attendee attendee email address
     * @param start    start date in Exchange zulu format
     * @param end      end date in Exchange zulu format
     * @param interval freebusy interval in minutes
     */
    public GetUserAvailabilityMethod(String attendee, String start, String end, int interval) {
        this(attendee, start, end, interval, false);
    }

    /**
     * Build EWS method.
     *
     * @param attendee attendee email address
     * @param start    start date in Exchange zulu format
     * @param end      end date in Exchange zulu format
     * @param interval freebusy interval in minutes
     * @param detailed request DetailedMerged calendar events instead of the existing MergedOnly view
     */
    public GetUserAvailabilityMethod(String attendee, String start, String end, int interval, boolean detailed) {
        super("FreeBusy", "GetUserAvailabilityRequest");
        this.attendee = attendee;
        this.start = start;
        this.end = end;
        this.interval = interval;
        this.detailed = detailed;
    }

    @Override
    protected void writeSoapBody(Writer writer) throws IOException {
        // write UTC timezone
        writer.write("<t:TimeZone>" +
                "<t:Bias>0</t:Bias>" +
                "<t:StandardTime>" +
                "<t:Bias>0</t:Bias>" +
                "<t:Time>02:00:00</t:Time>" +
                "<t:DayOrder>1</t:DayOrder>" +
                "<t:Month>3</t:Month>" +
                "<t:DayOfWeek>Sunday</t:DayOfWeek>" +
                "</t:StandardTime>" +
                "<t:DaylightTime>" +
                "<t:Bias>0</t:Bias>" +
                "<t:Time>02:00:00</t:Time>" +
                "<t:DayOrder>1</t:DayOrder>" +
                "<t:Month>10</t:Month>" +
                "<t:DayOfWeek>Sunday</t:DayOfWeek>" +
                "</t:DaylightTime>" +
                "</t:TimeZone>");
        // write attendee address
        writer.write("<m:MailboxDataArray>" +
                "<t:MailboxData>" +
                "<t:Email>" +
                "<t:Address>");
        writer.write(StringUtil.xmlEncode(attendee));
        writer.write("</t:Address>" +
                "</t:Email>" +
                "<t:AttendeeType>Required</t:AttendeeType>" +
                "</t:MailboxData>" +
                "</m:MailboxDataArray>");
        // freebusy range
        writer.write("<t:FreeBusyViewOptions>" +
                "<t:TimeWindow>" +
                "<t:StartTime>");
        writer.write(start);
        writer.write("</t:StartTime>" +
                "<t:EndTime>");
        writer.write(end);
        writer.write("</t:EndTime>" +
                "</t:TimeWindow>" +
                "<t:MergedFreeBusyIntervalInMinutes>" + interval + "</t:MergedFreeBusyIntervalInMinutes>" +
                "<t:RequestedView>" + (detailed ? "DetailedMerged" : "MergedOnly") + "</t:RequestedView>" +
                "</t:FreeBusyViewOptions>");
    }

    @Override
    protected void handleCustom(XMLStreamReader reader) throws XMLStreamException {
        if (XMLStreamUtil.isStartTag(reader, "MergedFreeBusy")) {
            this.mergedFreeBusy = XMLStreamUtil.getElementText(reader);
        } else if (detailed && XMLStreamUtil.isStartTag(reader, "CalendarEvent")) {
            calendarEvents.add(parseCalendarEvent(reader));
        }
    }

    protected CalendarEvent parseCalendarEvent(XMLStreamReader reader) throws XMLStreamException {
        CalendarEvent event = new CalendarEvent();
        while (reader.hasNext() && !XMLStreamUtil.isEndTag(reader, "CalendarEvent")) {
            reader.next();
            if (XMLStreamUtil.isStartTag(reader)) {
                String name = reader.getLocalName();
                if ("StartTime".equals(name)) {
                    event.start = XMLStreamUtil.getElementText(reader);
                } else if ("EndTime".equals(name)) {
                    event.end = XMLStreamUtil.getElementText(reader);
                } else if ("BusyType".equals(name)) {
                    event.busyType = XMLStreamUtil.getElementText(reader);
                } else if ("Subject".equals(name)) {
                    event.subject = XMLStreamUtil.getElementText(reader);
                } else if ("Location".equals(name)) {
                    event.location = XMLStreamUtil.getElementText(reader);
                } else if ("ID".equals(name)) {
                    event.id = XMLStreamUtil.getElementText(reader);
                } else if ("IsMeeting".equals(name)) {
                    event.isMeeting = parseBoolean(XMLStreamUtil.getElementText(reader));
                } else if ("IsRecurring".equals(name)) {
                    event.isRecurring = parseBoolean(XMLStreamUtil.getElementText(reader));
                } else if ("IsException".equals(name)) {
                    event.isException = parseBoolean(XMLStreamUtil.getElementText(reader));
                } else if ("IsPrivate".equals(name)) {
                    event.isPrivate = parseBoolean(XMLStreamUtil.getElementText(reader));
                }
            }
        }
        return event;
    }

    private static Boolean parseBoolean(String value) {
        if ("true".equalsIgnoreCase(value)) {
            return Boolean.TRUE;
        } else if ("false".equalsIgnoreCase(value)) {
            return Boolean.FALSE;
        }
        return null;
    }

    /**
     * Get merged freebusy string.
     *
     * @return freebusy string
     */
    public String getMergedFreeBusy() {
        return mergedFreeBusy;
    }

    /**
     * Get detailed availability events.
     *
     * @return immutable event list
     */
    public List<CalendarEvent> getCalendarEvents() {
        return Collections.unmodifiableList(calendarEvents);
    }

    /**
     * Calendar event returned by GetUserAvailability DetailedMerged.
     * Optional values remain null when Exchange omits them.
     */
    public static final class CalendarEvent {
        private String start;
        private String end;
        private String busyType;
        private String subject;
        private String location;
        private String id;
        private Boolean isMeeting;
        private Boolean isRecurring;
        private Boolean isException;
        private Boolean isPrivate;

        public String getStart() {
            return start;
        }

        public String getEnd() {
            return end;
        }

        public String getBusyType() {
            return busyType;
        }

        public String getSubject() {
            return subject;
        }

        public String getLocation() {
            return location;
        }

        public String getId() {
            return id;
        }

        public Boolean getMeeting() {
            return isMeeting;
        }

        public Boolean getRecurring() {
            return isRecurring;
        }

        public Boolean getException() {
            return isException;
        }

        public Boolean getPrivate() {
            return isPrivate;
        }
    }
}
