package platform.integration.domain;

import jakarta.json.*;
import jakarta.ws.rs.BadRequestException;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.Set;
import java.util.regex.Pattern;

public record PeopleInput(String firstName, String lastName, String identificationRef,
                          String birthDate, String locationId, String phone, String email,
                          String organizationId, Integer disasterId) {
    private static final Set<String> FIELDS = Set.of("firstName", "lastName", "identificationRef",
        "birthDate", "locationId", "phone", "email", "organizationId", "disasterId");
    private static final Pattern EDEN_ID = Pattern.compile("[1-9][0-9]{0,17}");

    public static PeopleInput parse(JsonObject value) {
        if (value == null || !FIELDS.containsAll(value.keySet())) throw new BadRequestException();
        String first = field(value, "firstName", 100);
        if (first == null || first.isBlank()) throw new BadRequestException();
        String last = field(value, "lastName", 100);
        String reference = field(value, "identificationRef", 100);
        String date = field(value, "birthDate", 10);
        if (date != null) {
            try { LocalDate.parse(date); } catch (DateTimeParseException invalid) { throw new BadRequestException(); }
        }
        String location = field(value, "locationId", 18);
        if (location != null && !EDEN_ID.matcher(location).matches()) throw new BadRequestException();
        String organization = field(value, "organizationId", 200);
        String phone = field(value, "phone", 40);
        String email = field(value, "email", 200);
        Integer disaster = null;
        if (value.containsKey("disasterId") && !value.isNull("disasterId")) {
            try {
                disaster = value.getJsonNumber("disasterId").intValueExact();
            } catch (RuntimeException invalid) { throw new BadRequestException(); }
            if (disaster <= 0) throw new BadRequestException();
        }
        return new PeopleInput(first, last, reference, date, location, phone, email, organization, disaster);
    }

    private static String field(JsonObject input, String name, int limit) {
        if (!input.containsKey(name) || input.isNull(name)) return null;
        JsonValue field = input.get(name);
        if (!(field instanceof JsonString text) || text.getString().length() > limit ||
            text.getString().indexOf('\0') >= 0) throw new BadRequestException();
        return text.getString();
    }
}
