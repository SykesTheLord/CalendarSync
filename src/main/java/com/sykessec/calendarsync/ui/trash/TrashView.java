package com.sykessec.calendarsync.ui.trash;

import com.sykessec.calendarsync.entity.DeletionAudit;
import com.sykessec.calendarsync.entity.enums.AuditStatus;
import com.sykessec.calendarsync.service.DeletionAuditService;
import com.sykessec.calendarsync.trash.RestoreOutcome;
import com.sykessec.calendarsync.ui.DisplayTime;
import com.sykessec.calendarsync.ui.MainLayout;
import com.sykessec.calendarsync.ui.UiLabels;
import com.sykessec.calendarsync.ui.ViewHeader;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.button.ButtonVariant;
import com.vaadin.flow.component.combobox.ComboBox;
import com.vaadin.flow.component.grid.Grid;
import com.vaadin.flow.component.grid.GridVariant;
import com.vaadin.flow.component.icon.VaadinIcon;
import com.vaadin.flow.component.notification.Notification;
import com.vaadin.flow.component.notification.NotificationVariant;
import com.vaadin.flow.component.orderedlayout.FlexComponent.Alignment;
import com.vaadin.flow.component.orderedlayout.HorizontalLayout;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.component.textfield.TextField;
import com.vaadin.flow.data.provider.Query;
import com.vaadin.flow.data.provider.SortDirection;
import com.vaadin.flow.data.value.ValueChangeMode;
import com.vaadin.flow.router.PageTitle;
import com.vaadin.flow.router.Route;
import jakarta.annotation.security.PermitAll;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;

import java.util.List;

/**
 * The primary trust mechanism for a destructive app: every deletion (real
 * or, once Stage 2 lands, feed-side) shows up here, searchable by title and
 * filterable by status, with Restore available whenever there's still a
 * snapshot to restore from.
 */
@Route(value = "trash", layout = MainLayout.class)
@PageTitle("Trash | CalendarSync")
@PermitAll
public class TrashView extends VerticalLayout {

    private final DeletionAuditService auditService;
    private final Grid<DeletionAudit> grid = new Grid<>(DeletionAudit.class, false);
    private final TextField search = new TextField("Search");
    private final ComboBox<AuditStatus> statusFilter = new ComboBox<>("Status");

    public TrashView(DeletionAuditService auditService) {
        this.auditService = auditService;

        setSizeFull();

        grid.addThemeVariants(GridVariant.LUMO_ROW_STRIPES, GridVariant.LUMO_NO_ROW_BORDERS);
        // occurred_at is TEXT in "yyyy-MM-dd HH:mm:ss" UTC form, so sorting on
        // the stored string is chronological; the cell renders it in the
        // application's own zone, named, rather than as a bare UTC string that
        // looked like a local time two hours in the past.
        grid.addColumn(a -> DisplayTime.format(a.getOccurredAt()))
                .setHeader("Occurred").setSortable(true).setSortProperty("occurredAt").setAutoWidth(true);
        grid.addColumn(DeletionAudit::getEventSummary).setHeader("Event").setFlexGrow(1);
        grid.addColumn(a -> UiLabels.of(a.getActionTaken())).setHeader("Action").setAutoWidth(true);
        grid.addColumn(a -> UiLabels.of(a.getStatus())).setHeader("Status").setAutoWidth(true);
        grid.addColumn(a -> UiLabels.yesNo(a.isSuccess())).setHeader("Succeeded").setAutoWidth(true);
        grid.addColumn(a -> DisplayTime.format(a.getRestoredAt())).setHeader("Restored").setAutoWidth(true);
        grid.addComponentColumn(this::restoreButtonFor).setHeader("").setFlexGrow(0).setAutoWidth(true);
        grid.setSizeFull();
        // Says "nothing matched" instead of leaving a filtered-to-empty grid
        // looking like a blank box.
        grid.setEmptyStateText("No deletions match these filters.");

        search.setPlaceholder("Search by event title");
        search.setClearButtonVisible(true);
        search.setPrefixComponent(VaadinIcon.SEARCH.create());
        // Default ON_CHANGE only fires on blur or Enter, so typing appeared to
        // do nothing at all until the user pressed one of them.
        search.setValueChangeMode(ValueChangeMode.LAZY);
        search.addValueChangeListener(e -> refresh());

        statusFilter.setItems(AuditStatus.values());
        statusFilter.setItemLabelGenerator(UiLabels::of);
        statusFilter.setClearButtonVisible(true);
        statusFilter.setPlaceholder("Any status");
        statusFilter.addValueChangeListener(e -> refresh());

        // Both fields carry a label, and the row is baseline-aligned, so the
        // two inputs line up instead of the unlabeled one riding high.
        HorizontalLayout filters = new HorizontalLayout(search, statusFilter);
        filters.setAlignItems(Alignment.BASELINE);
        filters.getStyle().set("flex-wrap", "wrap");

        add(new ViewHeader(VaadinIcon.TRASH, "Trash",
                        "Every deletion, real or feed-side, restorable while a snapshot exists."),
                filters, grid);
        setFlexGrow(1, grid);
        bindDataProvider();
    }

    /**
     * Pages and filters in the database. The grid asks for the slice it is
     * about to paint and for a count, so the size of the trash stops being the
     * cost of opening this page.
     */
    private void bindDataProvider() {
        grid.setItems(
                query -> auditService.search(search.getValue(), statusFilter.getValue(),
                        toPageRequest(query)).stream(),
                query -> (int) auditService.count(search.getValue(), statusFilter.getValue()));
    }

    private static PageRequest toPageRequest(Query<DeletionAudit, Void> query) {
        List<Sort.Order> orders = query.getSortOrders().stream()
                .map(order -> new Sort.Order(
                        order.getDirection() == SortDirection.ASCENDING ? Sort.Direction.ASC : Sort.Direction.DESC,
                        order.getSorted()))
                .toList();
        // Newest first is what someone opening a trash can is looking for.
        Sort sort = orders.isEmpty() ? Sort.by(Sort.Direction.DESC, "occurredAt") : Sort.by(orders);
        return PageRequest.of(query.getPage(), query.getPageSize(), sort);
    }

    private Button restoreButtonFor(DeletionAudit audit) {
        boolean eligible = audit.getStatus() == AuditStatus.DELETED && audit.getEventSnapshot() != null;
        Button restore = new Button("Restore", e -> {
            RestoreOutcome outcome = auditService.restore(audit.getId());
            if (outcome.success()) {
                Notification.show("Restored").addThemeVariants(NotificationVariant.LUMO_SUCCESS);
                refresh();
            } else {
                Notification.show("Restore failed: " + outcome.message(), 8000, Notification.Position.MIDDLE)
                        .addThemeVariants(NotificationVariant.LUMO_ERROR);
            }
        });
        restore.addThemeVariants(ButtonVariant.LUMO_TERTIARY, ButtonVariant.LUMO_SUCCESS);
        restore.setVisible(eligible);
        return restore;
    }

    private void refresh() {
        grid.getDataProvider().refreshAll();
    }
}
