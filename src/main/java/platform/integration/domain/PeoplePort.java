package platform.integration.domain;
import java.util.List;
import java.util.UUID;
public interface PeoplePort {
    List<PeopleRecord> list(String organization,String search,int offset,int limit,List<String> ids);
    jakarta.json.JsonObject relationships(String organization,List<String> ids);
    PeopleRecord get(String organization,String id);
    PeopleRecord create(String organization,UUID operation,PeopleInput input);
    PeopleRecord update(String organization,String id,PeopleInput input,UUID operation,UUID expectedIdentity,java.util.function.BooleanSupplier beginWrite);
    String event(String organization,UUID operation,int disaster,String name,String source);

}
