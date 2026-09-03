package com.sykessec.calendarsync.security;

import com.sykessec.calendarsync.repository.AppUserRepository;
import org.springframework.security.authentication.LockedException;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

@Service
public class AppUserDetailsService implements UserDetailsService {

    private final AppUserRepository appUserRepository;
    private final LoginAttemptService loginAttemptService;

    public AppUserDetailsService(AppUserRepository appUserRepository, LoginAttemptService loginAttemptService) {
        this.appUserRepository = appUserRepository;
        this.loginAttemptService = loginAttemptService;
    }

    @Override
    public UserDetails loadUserByUsername(String username) throws UsernameNotFoundException {
        // Refused before the user is even looked up, so a blocked address
        // cannot use response timing to tell an existing username from an
        // unknown one, and never gets a bcrypt verification done on its behalf.
        String clientAddress = loginAttemptService.currentClientAddress();
        if (loginAttemptService.isBlocked(clientAddress)) {
            throw new LockedException("Too many failed sign-in attempts from this address - try again later");
        }

        return appUserRepository.findByUsername(username)
                .map(AppUserPrincipal::new)
                .orElseThrow(() -> new UsernameNotFoundException("Unknown user: " + username));
    }
}
