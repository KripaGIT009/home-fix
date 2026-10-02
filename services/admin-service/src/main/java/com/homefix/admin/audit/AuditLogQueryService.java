package com.homefix.admin.audit;

import java.util.List;
import java.util.Locale;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.homefix.admin.sysconfig.SystemConfigService;
import com.homefix.admin.sysconfig.SystemSettingRegistry;

/**
 * The Admin Portal's Audit Logs view (Requirement 19.8): the whole log, newest first, optionally
 * narrowed by action and entity type, in keyset-paginated pages.
 *
 * <p>The page size and whether summaries show values are System Configuration settings
 * ({@code audit.pageSize}, {@code audit.summaryShowsValues}). One extra row is fetched to learn
 * whether a next page exists without a count query.
 */
@Service
public class AuditLogQueryService {

    private final AuditLogStore store;
    private final SystemConfigService systemConfig;
    private final ObjectMapper objectMapper;

    public AuditLogQueryService(AuditLogStore store, SystemConfigService systemConfig, ObjectMapper objectMapper) {
        this.store = store;
        this.systemConfig = systemConfig;
        this.objectMapper = objectMapper;
    }

    /** One page of entries with their change summaries, and the cursor of the next page if any. */
    public record Page(List<Item> entries, String nextCursor) {
    }

    /** One entry with its derived change summary. */
    public record Item(AuditLogEntry entry, String changeSummary) {
    }

    /**
     * @param action     action name ({@code CREATE}, {@code UPDATE}, ...); blank for all
     * @param entityType entity type text to match (contains, ignoring case); blank for all
     * @param cursor     the {@code nextCursor} of the previous page; blank for the first page
     * @throws InvalidAuditQueryException if the action is unknown or the cursor malformed
     */
    @Transactional(readOnly = true)
    public Page page(String action, String entityType, String cursor) {
        AdminAction actionFilter = parseAction(action);
        String entityTypeFilter = isBlank(entityType) ? null : entityType.trim();
        AuditCursor after = isBlank(cursor) ? null : AuditCursor.decode(cursor.trim());
        int pageSize = Math.toIntExact(systemConfig.numberValue(SystemSettingRegistry.AUDIT_PAGE_SIZE));
        boolean showValues = systemConfig.booleanValue(SystemSettingRegistry.AUDIT_SUMMARY_SHOWS_VALUES);

        List<AuditLogEntry> rows = store.findPage(actionFilter, entityTypeFilter, after, pageSize + 1);
        boolean hasMore = rows.size() > pageSize;
        List<AuditLogEntry> page = hasMore ? rows.subList(0, pageSize) : rows;
        String nextCursor = hasMore ? AuditCursor.after(page.get(page.size() - 1)).encode() : null;
        List<Item> items = page.stream()
                .map(entry -> new Item(entry, ChangeSummaries.summarise(entry, showValues, objectMapper)))
                .toList();
        return new Page(items, nextCursor);
    }

    private static AdminAction parseAction(String action) {
        if (isBlank(action)) {
            return null;
        }
        try {
            return AdminAction.valueOf(action.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException unknown) {
            throw new InvalidAuditQueryException("action must be one of " + List.of(AdminAction.values()));
        }
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
