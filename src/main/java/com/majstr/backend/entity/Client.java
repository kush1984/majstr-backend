package com.majstr.backend.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "clients")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(of = "id")
public class Client {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "owner_id", nullable = false, updatable = false)
    private User owner;

    @Column(name = "full_name", nullable = false, length = 255)
    private String fullName;

    @Column(name = "phone", nullable = false, length = 50)
    private String phone;

    @Column(name = "address", length = 512)
    private String address;

    /** Optional — used to email the estimate portal link to the client. */
    @Column(name = "email", length = 255)
    private String email;

    // ---- Document requisites (acts iteration, V103) — decide what a PDF prints for this customer.
    //      A PERSON needs only fullName; FOP/COMPANY carry legal details + a signatory. -----------

    /** PERSON | FOP | COMPANY. Defaults to PERSON so every existing client stays a plain individual. */
    @Enumerated(EnumType.STRING)
    @Builder.Default
    @Column(name = "client_type", nullable = false, length = 20)
    private ClientType clientType = ClientType.PERSON;

    /** РНОКПП (FOP) / ЄДРПОУ (company). */
    @Column(name = "tax_id", length = 20)
    private String taxId;

    /** Повна назва (fallback for the PDF: fullName). */
    @Column(name = "legal_name", length = 255)
    private String legalName;

    @Column(name = "legal_address", length = 512)
    private String legalAddress;

    /** Посада підписанта (e.g. «Директор»). */
    @Column(name = "signatory_title", length = 120)
    private String signatoryTitle;

    /** ПІБ підписанта. */
    @Column(name = "signatory_name", length = 255)
    private String signatoryName;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @PrePersist
    void onCreate() {
        if (id == null) {
            id = UUID.randomUUID();
        }
        if (createdAt == null) {
            createdAt = Instant.now();
        }
    }
}
