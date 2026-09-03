package com.sykessec.calendarsync.repository;

import com.sykessec.calendarsync.AbstractIntegrationTest;
import com.sykessec.calendarsync.entity.AppUser;
import com.sykessec.calendarsync.entity.CalendarConnection;
import com.sykessec.calendarsync.entity.DeletionRule;
import com.sykessec.calendarsync.entity.enums.MatchLogic;
import com.sykessec.calendarsync.entity.enums.ProviderType;
import com.sykessec.calendarsync.entity.enums.Role;
import com.sykessec.calendarsync.entity.enums.RuleAction;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Confirms a query for one user's data can never return another user's rows.
 * Written to be trivially extended: as new user-scoped entities land in
 * later stages (DeletionAudit, PublishedFeed, ...), add one more block to
 * this class rather than a new file, so isolation coverage can't silently
 * lag behind schema growth.
 */
@Transactional
class UserScopingIsolationTest extends AbstractIntegrationTest {

    @Autowired
    private AppUserRepository appUserRepository;
    @Autowired
    private CalendarConnectionRepository calendarConnectionRepository;
    @Autowired
    private DeletionRuleRepository deletionRuleRepository;

    private AppUser userA;
    private AppUser userB;

    @BeforeEach
    void seedTwoUsers() {
        userA = appUserRepository.save(newUser("alice"));
        userB = appUserRepository.save(newUser("bob"));
    }

    private AppUser newUser(String username) {
        AppUser user = new AppUser();
        user.setUsername(username);
        user.setPasswordHash("irrelevant-for-this-test");
        user.setRole(Role.USER);
        user.setEnabled(true);
        return user;
    }

    @Test
    void calendarConnectionFindersAreScopedToOwningUser() {
        CalendarConnection connectionA = calendarConnectionRepository.save(newConnection(userA.getId()));
        CalendarConnection connectionB = calendarConnectionRepository.save(newConnection(userB.getId()));

        assertThat(calendarConnectionRepository.findAllByUserId(userA.getId()))
                .extracting(CalendarConnection::getId)
                .containsExactly(connectionA.getId());

        assertThat(calendarConnectionRepository.findByIdAndUserId(connectionA.getId(), userA.getId()))
                .isPresent();

        // The negative case: asking for user B's row under user A's id must
        // come back empty, not user B's row.
        assertThat(calendarConnectionRepository.findByIdAndUserId(connectionB.getId(), userA.getId()))
                .isEmpty();
    }

    private CalendarConnection newConnection(Long userId) {
        CalendarConnection connection = new CalendarConnection();
        connection.setUserId(userId);
        connection.setProvider(ProviderType.ICS_SOURCE);
        connection.setDisplayName("test connection");
        connection.setAuthType("url");
        return connection;
    }

    @Test
    void deletionRuleFindersAreScopedToOwningUser() {
        DeletionRule ruleA = deletionRuleRepository.save(newRule(userA.getId()));
        DeletionRule ruleB = deletionRuleRepository.save(newRule(userB.getId()));

        assertThat(deletionRuleRepository.findAllByUserIdOrderByPriorityAsc(userA.getId()))
                .extracting(DeletionRule::getId)
                .containsExactly(ruleA.getId());

        assertThat(deletionRuleRepository.findByIdAndUserId(ruleB.getId(), userA.getId()))
                .isEmpty();
    }

    private DeletionRule newRule(Long userId) {
        DeletionRule rule = new DeletionRule();
        rule.setUserId(userId);
        rule.setName("test rule");
        rule.setMatchLogic(MatchLogic.ANY);
        rule.setAction(RuleAction.DRY_RUN);
        rule.setEnabled(true);
        rule.setPriority(0);
        return rule;
    }
}
