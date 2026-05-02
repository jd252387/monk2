package dev.monk.translator.api;

import dev.monk.translator.config.ConfigStatus;
import dev.monk.translator.config.FileBackedConfigRepository;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;

/**
 * Agent-facing diagnostics for the active material routing configuration.
 */
@Path("/v1/config/status")
@Produces(MediaType.APPLICATION_JSON)
public class ConfigStatusResource {
    private final FileBackedConfigRepository repository;

    @Inject
    public ConfigStatusResource(FileBackedConfigRepository repository) {
        this.repository = repository;
    }

    @GET
    public ConfigStatus status() {
        return repository.status();
    }
}
