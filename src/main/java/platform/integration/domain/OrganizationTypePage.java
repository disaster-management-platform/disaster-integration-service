package platform.integration.domain;
import java.util.List;
import jakarta.json.bind.annotation.JsonbNillable;
@JsonbNillable
public record OrganizationTypePage(List<OrganizationTypeDto> items, int page, int pageSize, Integer total, boolean hasMore) { }
