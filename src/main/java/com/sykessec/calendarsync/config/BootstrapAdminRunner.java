package com.sykessec.calendarsync.config;

import com.sykessec.calendarsync.entity.enums.Role;
import com.sykessec.calendarsync.repository.AppUserRepository;
import com.sykessec.calendarsync.service.UserAdminService;
import com.sykessec.calendarsync.util.TokenGenerator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/**
 * If app_user is empty at startup, creates a single ADMIN account with a
 * randomly generated password and prints it to the log exactly once. The
 * credential is never written to a migration file or committed anywhere -
 * this is the only place it exists outside the bcrypt hash in the database.
 */
@Component
public class BootstrapAdminRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(BootstrapAdminRunner.class);

    private final AppUserRepository appUserRepository;
    private final UserAdminService userAdminService;

    public BootstrapAdminRunner(AppUserRepository appUserRepository, UserAdminService userAdminService) {
        this.appUserRepository = appUserRepository;
        this.userAdminService = userAdminService;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (appUserRepository.count() > 0) {
            return;
        }

        String username = "admin";
        String password = TokenGenerator.humanReadablePassword();
        userAdminService.createUser(username, password, Role.ADMIN);

        log.warn("""

                ============================================================
                CalendarSync: no users existed, created a first-run admin account.
                This password is shown ONLY this one time - it is not stored
                anywhere except as a bcrypt hash. Log in and change it, or
                create a personal admin account and disable this one.

                  username: {}
                  password: {}
                ============================================================
                """, username, password);
    }
}
