package platform.integration.domain;

import java.util.Set;

/** Source categories are not business types or provider-generated disaster IDs. */
public final class ExternalReportSource {
    public static final Set<String> CATEGORIES = Set.of("monitoring", "gis", "hospital", "administrative", "financial");
    private ExternalReportSource() { }
    public static String identify(String category, String issuer, String organization, String actor) {
        if (!CATEGORIES.contains(category) || issuer == null || issuer.isBlank() || organization == null ||
            organization.isBlank() || actor == null || actor.isBlank()) throw new IllegalArgumentException();
        return category + ":" + OperationIdentity.scoped(issuer, organization, actor);
    }
}
