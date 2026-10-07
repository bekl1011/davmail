/*
 * DavMail POP/IMAP/SMTP/CalDav/LDAP Exchange Gateway
 * Copyright (C) 2026  DavMail contributors
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU General Public License
 * as published by the Free Software Foundation; either version 2
 * of the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 */
package davmail.caldav;

import davmail.exchange.ICSBufferedWriter;
import davmail.exchange.ews.GetUserAvailabilityMethod;
import org.apache.commons.codec.digest.DigestUtils;

import java.io.IOException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Date;
import java.util.List;

/**
 * Build a read-only iCalendar representation of EWS DetailedMerged
 * availability events.
 */
final class AvailabilityCalendar {
    private static final DateTimeFormatter ICALENDAR_UTC_FORMATTER =
            DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC);

    private AvailabilityCalendar() {
    }

    static String build(String mailbox, List<GetUserAvailabilityMethod.CalendarEvent> events, Date generatedAt)
            throws IOException {
        ICSBufferedWriter writer = new ICSBufferedWriter();
        writer.writeLine("BEGIN:VCALENDAR");
        writer.writeLine("VERSION:2.0");
        writer.writeLine("PRODID:-//DavMail//EWS Availability Calendar//EN");
        writer.writeLine("CALSCALE:GREGORIAN");
        writer.writeLine("METHOD:PUBLISH");
        String dtstamp = ICALENDAR_UTC_FORMATTER.format(generatedAt.toInstant());

        for (GetUserAvailabilityMethod.CalendarEvent event : events) {
            if (event.getStart() == null || event.getEnd() == null) {
                continue;
            }
            writer.writeLine("BEGIN:VEVENT");
            writer.appendProperty("UID", buildUid(mailbox, event));
            writer.appendProperty("DTSTAMP", dtstamp);
            writer.appendProperty("DTSTART", toIcalendarUtc(event.getStart()));
            writer.appendProperty("DTEND", toIcalendarUtc(event.getEnd()));
            writer.appendTextProperty("SUMMARY", event.getSubject());
            writer.appendTextProperty("LOCATION", event.getLocation());
            writer.appendProperty("TRANSP", "Free".equalsIgnoreCase(event.getBusyType())
                    ? "TRANSPARENT" : "OPAQUE");
            if ("Tentative".equalsIgnoreCase(event.getBusyType())) {
                writer.appendProperty("STATUS", "TENTATIVE");
            }
            if (Boolean.TRUE.equals(event.getPrivate())) {
                writer.appendProperty("CLASS", "PRIVATE");
            }
            writer.writeLine("END:VEVENT");
        }
        writer.writeLine("END:VCALENDAR");
        return writer.toString();
    }

    static String buildUid(String mailbox, GetUserAvailabilityMethod.CalendarEvent event) {
        StringBuilder source = new StringBuilder(mailbox).append('\n');
        if (event.getId() != null) {
            source.append(event.getId());
        }
        source.append('\n').append(event.getStart());
        if (event.getId() == null) {
            source.append('\n').append(event.getEnd());
        }
        return DigestUtils.sha256Hex(source.toString()) + "@davmail-availability";
    }

    private static String toIcalendarUtc(String value) throws IOException {
        try {
            return ICALENDAR_UTC_FORMATTER.format(parseInstant(value));
        } catch (DateTimeParseException e) {
            throw new IOException("Invalid availability event date", e);
        }
    }

    private static Instant parseInstant(String value) {
        try {
            return Instant.parse(value);
        } catch (DateTimeParseException e) {
            try {
                return OffsetDateTime.parse(value, DateTimeFormatter.ISO_OFFSET_DATE_TIME).toInstant();
            } catch (DateTimeParseException ignored) {
                // GetUserAvailability uses the UTC timezone supplied in the request,
                // but Exchange commonly omits the suffix in CalendarEvent values.
                return LocalDateTime.parse(value, DateTimeFormatter.ISO_LOCAL_DATE_TIME).toInstant(ZoneOffset.UTC);
            }
        }
    }
}
