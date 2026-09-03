package com.sykessec.calendarsync.entity;

import com.sykessec.calendarsync.entity.enums.MatchLogic;
import com.sykessec.calendarsync.entity.enums.RuleAction;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "deletion_rule")
public class DeletionRule {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(nullable = false)
    private String name;

    @Column(nullable = false)
    private boolean enabled = true;

    @Enumerated(EnumType.STRING)
    @Column(name = "match_logic", nullable = false)
    private MatchLogic matchLogic = MatchLogic.ANY;

    // Safety default: new rules never delete anything until a user explicitly
    // switches them to DELETE.
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private RuleAction action = RuleAction.DRY_RUN;

    @Column(nullable = false)
    private int priority = 0;

    public Long getId() {
        return id;
    }

    public Long getUserId() {
        return userId;
    }

    public void setUserId(Long userId) {
        this.userId = userId;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public MatchLogic getMatchLogic() {
        return matchLogic;
    }

    public void setMatchLogic(MatchLogic matchLogic) {
        this.matchLogic = matchLogic;
    }

    public RuleAction getAction() {
        return action;
    }

    public void setAction(RuleAction action) {
        this.action = action;
    }

    public int getPriority() {
        return priority;
    }

    public void setPriority(int priority) {
        this.priority = priority;
    }
}
