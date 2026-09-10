package com.homefix.provider.domain;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

/**
 * A single service category a provider has selected, together with its selected subcategories
 * (Requirement 4.3: up to 5 active categories, up to 10 active subcategories per category).
 */
@Entity
@Table(name = "provider_category_selection")
public class ProviderCategorySelection {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "provider_id", nullable = false)
    private ProviderProfile provider;

    @Column(name = "category_id", nullable = false)
    private UUID categoryId;

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "provider_subcategory_selection",
            joinColumns = @JoinColumn(name = "selection_id"))
    @Column(name = "subcategory_id", nullable = false)
    private List<UUID> subcategoryIds = new ArrayList<>();

    protected ProviderCategorySelection() {
        // JPA
    }

    public ProviderCategorySelection(UUID categoryId, List<UUID> subcategoryIds) {
        this.id = UUID.randomUUID();
        this.categoryId = categoryId;
        this.subcategoryIds = new ArrayList<>(subcategoryIds);
    }

    void attachTo(ProviderProfile provider) {
        this.provider = provider;
    }

    public UUID getCategoryId() {
        return categoryId;
    }

    public List<UUID> getSubcategoryIds() {
        return subcategoryIds;
    }
}
