package com.majstr.backend.controller;

import com.majstr.backend.dto.ClientRequest;
import com.majstr.backend.dto.ClientResponse;
import com.majstr.backend.security.UserPrincipal;
import com.majstr.backend.service.ClientService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/clients")
@RequiredArgsConstructor
@Tag(name = "Clients", description = "Contractor's clients")
@SecurityRequirement(name = "bearer-jwt")
public class ClientController {

    private final ClientService clientService;

    @Operation(summary = "Create a client")
    @PostMapping
    public ResponseEntity<ClientResponse> create(@Valid @RequestBody ClientRequest req,
                                                 @AuthenticationPrincipal UserPrincipal principal) {
        return ResponseEntity.status(HttpStatus.CREATED).body(clientService.create(req, principal.id()));
    }

    @Operation(summary = "List my clients")
    @GetMapping
    public List<ClientResponse> list(@AuthenticationPrincipal UserPrincipal principal) {
        return clientService.listForOwner(principal.id());
    }

    @Operation(summary = "Get a client by id")
    @GetMapping("/{id}")
    public ClientResponse get(@PathVariable UUID id,
                              @AuthenticationPrincipal UserPrincipal principal) {
        return clientService.get(id, principal.id());
    }

    @Operation(summary = "Update a client")
    @PutMapping("/{id}")
    public ClientResponse update(@PathVariable UUID id,
                                 @Valid @RequestBody ClientRequest req,
                                 @AuthenticationPrincipal UserPrincipal principal) {
        return clientService.update(id, req, principal.id());
    }

    @Operation(summary = "Delete a client")
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable UUID id,
                                       @AuthenticationPrincipal UserPrincipal principal) {
        clientService.delete(id, principal.id());
        return ResponseEntity.noContent().build();
    }
}
