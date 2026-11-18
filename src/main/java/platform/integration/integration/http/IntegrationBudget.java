package platform.integration.integration.http;
import jakarta.enterprise.context.RequestScoped;
import jakarta.ws.rs.WebApplicationException;
@RequestScoped
public class IntegrationBudget {
    private final long end=System.nanoTime()+20_000_000_000L;
    public long remainingMillis(){long remaining=(end-System.nanoTime())/1_000_000;if(remaining<=0)throw new WebApplicationException(504);return Math.min(remaining,10_000);}
}
