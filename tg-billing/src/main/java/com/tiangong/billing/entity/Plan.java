package com.tiangong.billing.entity;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@Entity
@NoArgsConstructor
@Table(name = "plans")
public class Plan {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String code;

    private String name;

    /** SUBSCRIPTION / CREDIT_PACK */
    private String type;

    private Integer priceCents;

    private String currency;

    private Integer periodDays;

    private Integer monthlyCredits;

    private Integer credits;

    private String description;

    /** ON_SALE / OFF_SHELF */
    private String status;

    @PrePersist
    void onCreate() {
        if (this.currency == null) {
            this.currency = "CNY";
        }
        if (this.periodDays == null) {
            this.periodDays = 0;
        }
        if (this.monthlyCredits == null) {
            this.monthlyCredits = 0;
        }
        if (this.credits == null) {
            this.credits = 0;
        }
        if (this.status == null) {
            this.status = "ON_SALE";
        }
    }
}
