package platform.integration.api;
import jakarta.ws.rs.*;
import jakarta.ws.rs.container.*;
import jakarta.ws.rs.ext.Provider;
import java.io.*;
@Provider
public class RequestLimit implements ContainerRequestFilter {
    public void filter(ContainerRequestContext request)throws IOException{
        if(request.hasEntity() && request.getMethod().matches("POST|PUT|PATCH")){
            byte[] body=request.getEntityStream().readNBytes(262145);
            if(body.length>262144)throw new WebApplicationException(413);
            request.setEntityStream(new ByteArrayInputStream(body));
        }
    }
}
