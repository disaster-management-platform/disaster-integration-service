package platform.integration.api;

import jakarta.ws.rs.ApplicationPath;
import org.eclipse.microprofile.auth.LoginConfig;

@ApplicationPath("/api")
@LoginConfig(authMethod = "MP-JWT")
public class Application extends jakarta.ws.rs.core.Application { }
