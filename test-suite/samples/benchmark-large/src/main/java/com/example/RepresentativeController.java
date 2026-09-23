package com.example;

import io.micronaut.http.annotation.Controller;
import io.micronaut.http.annotation.Get;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;

/** Readiness path that forces bean discovery and a large class-path resource stream. */
@Controller("/")
public final class RepresentativeController {
    private final List<RepresentativeBeans.WorkItem> workItems;

    public RepresentativeController(List<RepresentativeBeans.WorkItem> workItems) {
        this.workItems = workItems;
    }

    @Get(uri = "/hello", produces = "text/plain")
    public String hello() throws IOException {
        int sum = workItems.stream().mapToInt(RepresentativeBeans.WorkItem::value).sum();
        int bytes;
        try (InputStream input = getClass().getResourceAsStream("/representative-payload.bin")) {
            if (input == null) {
                throw new IOException("representative-payload.bin is missing");
            }
            bytes = input.readAllBytes().length;
        }
        if (workItems.size() != 48 || sum != 1128 || bytes != 1024 * 1024) {
            throw new IOException("representative fixture mismatch: beans=" + workItems.size()
                    + ", sum=" + sum + ", bytes=" + bytes);
        }
        return "hello from representative workload";
    }
}
